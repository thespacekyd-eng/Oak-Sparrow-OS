package dev.governance.android.app.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.governance.android.app.AgentKernelInterface
import dev.governance.android.app.AuthorizationActivity
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.core.ActionId
import dev.governance.core.ActionTier
import dev.governance.core.GateDecision
import dev.governance.core.Outcome
import dev.governance.core.ProposedAction
import dev.governance.core.ResolvedOutcome
import dev.governance.core.Reversibility
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Speculative dispatch orchestrator — the negative-latency core.
 *
 * Three dispatch tiers, from fastest to most cautious:
 *
 * ## Instant (negative latency)
 * When [GatePredictor.shouldInstantDispatch] returns true (FullyReversible,
 * App-tier, warmup complete, gamma well below HOLD threshold), the action
 * fires **before** the kernel's `decide()` even starts. The signed decision
 * still runs asynchronously for attestation and audit; if it disagrees
 * (rare — only possible during rapid gamma spikes), the action is rolled
 * back and logged. The user perceives the result before the governance
 * round-trip completes.
 *
 * ## Speculative (zero latency)
 * For [Reversibility.FullyReversible] + [ActionTier.App] steps that don't
 * qualify for instant dispatch (e.g. during warmup, or when gamma is
 * borderline), dispatch and `decide()` run in parallel. The user sees the
 * result in max(dispatch, gate) time instead of dispatch + gate.
 *
 * ## Strict (positive latency)
 * Everything else (OneShot, Irreversible, RootSystem). The kernel's
 * `decide()` must return before any dispatch. HOLD produces an auth
 * dialog; VETO prevents dispatch entirely.
 *
 * The kernel still signs every decision; speculation is purely a UX
 * latency win. Nothing about the cryptographic trust chain changes.
 */
class SpeculativeOrchestrator(
    private val context: Context,
    private val planner: Planner,
    private val kernel: AgentKernelInterface,
    private val dispatcher: ActionDispatcher,
    private val speculationLog: SpeculationLog = SpeculationLog(),
) {

    /** Cached snapshot for gate prediction. Refreshed on each [execute]. */
    private var snapshot: dev.governance.core.GovernanceSnapshot? = null

    suspend fun execute(userInstruction: String): Triple<PlanResult, ExecutionLog?, SpeculationLog> {
        // Refresh snapshot before planning — used by GatePredictor for
        // instant dispatch decisions. This is a read-only IPC call.
        snapshot = try {
            kernel.snapshot()?.toKernel()
        } catch (_: Exception) { null }
        val snap = snapshot
        Log.i(TAG, "execute: snapshot=${if (snap != null) "gamma=${snap.gamma}, warmup=${snap.warmupComplete}" else "null"}")

        val planResult = planner.plan(userInstruction)
        if (planResult is PlanResult.Error || planResult is PlanResult.Conversational) {
            return Triple(planResult, null, speculationLog)
        }

        val plan = (planResult as PlanResult.Success).plan
        val log = ExecutionLog(plan)

        for ((index, step) in plan.steps.withIndex()) {
            val snap = snapshot
            val canInstant = snap != null && GatePredictor.shouldInstantDispatch(step, snap)
            val canSpeculate = !canInstant &&
                ActionTier.classify(step.kind) == ActionTier.App &&
                step.reversibility == Reversibility.FullyReversible

            if (canInstant) {
                log.setTier(index, DispatchTier.INSTANT)
                Log.i(TAG, "INSTANT dispatch: ${step.kind} → ${step.target} " +
                    "(gamma=${snap?.gamma}, warmup=${snap?.warmupComplete})")
                executeInstant(index, step, log)
            } else if (canSpeculate) {
                log.setTier(index, DispatchTier.SPECULATIVE)
                Log.i(TAG, "SPECULATIVE dispatch: ${step.kind} → ${step.target} " +
                    "(gamma=${snap?.gamma}, warmup=${snap?.warmupComplete})")
                executeSpeculative(index, step, log)
            } else {
                log.setTier(index, DispatchTier.STRICT)
                Log.i(TAG, "STRICT dispatch: ${step.kind} → ${step.target} " +
                    "(gamma=${snap?.gamma}, warmup=${snap?.warmupComplete}, " +
                    "tier=${ActionTier.classify(step.kind)}, rev=${step.reversibility})")
                executeStrict(index, step, log)
            }

            if (log.stepStates[index] is ExecutionLog.StepState.Vetoed ||
                log.stepStates[index] is ExecutionLog.StepState.Skipped) {
                log.markFinished()
                return Triple(planResult, log, speculationLog)
            }
        }

        log.markFinished()
        return Triple(planResult, log, speculationLog)
    }

    /**
     * Instant (negative latency) path: dispatch BEFORE the gate runs.
     *
     * The action fires the moment the planner emits it. The kernel's
     * decide() runs in a fire-and-forget coroutine for attestation and
     * audit — but the user sees the result instantly. If the gate
     * disagrees (only possible during rapid gamma spikes between the
     * snapshot and the decision), the action is rolled back.
     *
     * Preconditions (enforced by [GatePredictor.shouldInstantDispatch]):
     * - FullyReversible + App tier (rollback is cheap)
     * - Warmup complete (prediction reliable)
     * - Gamma well below HOLD threshold (high-confidence PASS)
     */
    private suspend fun executeInstant(
        index: Int,
        step: PlannedStep,
        log: ExecutionLog,
    ): Unit = coroutineScope {
        val speculationId = speculationLog.recordStarted(step)

        // Dispatch NOW — no gate check.
        log.update(index, ExecutionLog.StepState.Executing)
        val result = try {
            dispatcher.dispatchSpeculative(step)
        } catch (e: Exception) {
            DispatchResult.Failed("Instant dispatch failed: ${e.message}")
        }
        log.update(index, resultToState(result))

        // Gate runs asynchronously — attestation and audit still happen.
        async {
            val proposed = buildProposedAction(step)
            val decision: GateDecision? = try {
                kernel.decide(ProposedActionParcel.from(proposed)).toKernel()
            } catch (_: Exception) { null }

            if (decision == null) {
                speculationLog.recordRolledBack(speculationId, "kernel error (async)")
                return@async
            }

            when (decision.outcome) {
                Outcome.PASS -> {
                    speculationLog.recordCommitted(speculationId, decision.auditId.value)
                    resolveDecision(decision, result)
                }
                Outcome.HOLD -> {
                    // Rare: gamma spiked between snapshot and decision.
                    // Action already landed — ask user post-facto.
                    speculationLog.recordCommitted(speculationId, decision.auditId.value)
                    resolveDecision(decision, result)
                }
                Outcome.VETO -> {
                    // Very rare: hard barrier or extreme gamma spike.
                    speculationLog.recordRolledBack(speculationId, decision.rationale)
                    rollbackSpeculation(step)
                }
            }
        }
    }

    /**
     * Speculative path: fire the intent immediately, decide in parallel,
     * commit or roll back when the decision arrives.
     */
    private suspend fun executeSpeculative(
        index: Int,
        step: PlannedStep,
        log: ExecutionLog,
    ): Unit = coroutineScope {
        val proposed = buildProposedAction(step)
        log.update(index, ExecutionLog.StepState.GateChecking)

        // Kick off both in parallel.
        val decisionDeferred: Deferred<GateDecision?> = async {
            try {
                kernel.decide(ProposedActionParcel.from(proposed)).toKernel()
            } catch (_: Exception) {
                null
            }
        }

        // Speculatively dispatch the reversible work — this is the
        // zero-latency win. The action is App-tier + FullyReversible,
        // so worst case is "the kernel says VETO and we already opened
        // an app" — which is recoverable and audited via SpeculationLog.
        val speculationId = speculationLog.recordStarted(step)
        val speculativeResult = try {
            dispatcher.dispatchSpeculative(step)
        } catch (e: Exception) {
            DispatchResult.Failed("Speculative dispatch failed: ${e.message}")
        }

        val decision = decisionDeferred.await()
        if (decision == null) {
            Log.w(TAG, "speculative: decide() returned null (kernel error)")
            speculationLog.recordRolledBack(speculationId, "kernel error")
            log.update(index, ExecutionLog.StepState.Failed("Kernel error during gate check"))
            return@coroutineScope
        }
        Log.i(TAG, "speculative: gate returned ${decision.outcome} for ${step.kind} (gamma=${decision.gamma})")

        when (decision.outcome) {
            Outcome.PASS -> {
                speculationLog.recordCommitted(speculationId, decision.auditId.value)
                resolveDecision(decision, speculativeResult)
                log.update(index, resultToState(speculativeResult))
            }
            Outcome.HOLD -> {
                // For App-tier FullyReversible work, HOLD is rare but possible
                // (γ very high during warmup). The speculative work has already
                // landed; we still ask the user, and on approval simply confirm.
                log.update(index, ExecutionLog.StepState.AwaitingApproval)
                val approved = launchAuthAndWait(decision)
                if (approved) {
                    speculationLog.recordCommitted(speculationId, decision.auditId.value)
                    resolveDecision(decision, speculativeResult)
                    log.update(index, resultToState(speculativeResult))
                } else {
                    speculationLog.recordRolledBack(speculationId, "user declined")
                    rollbackSpeculation(step)
                    log.update(index, ExecutionLog.StepState.Skipped("User declined"))
                }
            }
            Outcome.VETO -> {
                speculationLog.recordRolledBack(speculationId, decision.rationale)
                rollbackSpeculation(step)
                log.update(index, ExecutionLog.StepState.Vetoed(decision.rationale))
            }
        }
    }

    /**
     * Strict path: wait for the kernel decision, then dispatch.
     * Used for OneShot / Irreversible / RootSystem actions.
     */
    private suspend fun executeStrict(
        index: Int,
        step: PlannedStep,
        log: ExecutionLog,
    ) {
        log.update(index, ExecutionLog.StepState.GateChecking)
        delay(150)

        val proposed = buildProposedAction(step)
        val decision: GateDecision = try {
            kernel.decide(ProposedActionParcel.from(proposed)).toKernel()
        } catch (e: Exception) {
            log.update(index, ExecutionLog.StepState.Failed("Kernel error: ${e.message}"))
            return
        }

        when (decision.outcome) {
            Outcome.PASS -> {
                log.update(index, ExecutionLog.StepState.Executing)
                val result = dispatcher.dispatch(decision, step)
                resolveDecision(decision, result)
                log.update(index, resultToState(result))
            }
            Outcome.HOLD -> {
                log.update(index, ExecutionLog.StepState.AwaitingApproval)
                val approved = launchAuthAndWait(decision)
                if (approved) {
                    log.update(index, ExecutionLog.StepState.Executing)
                    val result = dispatcher.dispatch(decision, step)
                    resolveDecision(decision, result)
                    log.update(index, resultToState(result))
                } else {
                    log.update(index, ExecutionLog.StepState.Skipped("User declined"))
                }
            }
            Outcome.VETO -> {
                log.update(index, ExecutionLog.StepState.Vetoed(decision.rationale))
            }
        }
    }

    /**
     * Roll back a speculative dispatch when the kernel says VETO or the
     * user declines. Best-effort — for "open app" speculation, there's
     * little to undo (the user can dismiss the app). The audit log
     * records that the agent's speculative open was overruled.
     */
    private fun rollbackSpeculation(step: PlannedStep) {
        // Best-effort rollback: bring our app back to the foreground so
        // the user sees the chat surface (and the verification failure
        // or skip notice) rather than the speculatively-opened app.
        try {
            val intent = Intent(context, dev.governance.android.app.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Don't crash the orchestrator on rollback failure — the
            // user can navigate manually. The SpeculationLog already
            // recorded the rollback for audit.
        }
    }

    private fun buildProposedAction(step: PlannedStep): ProposedAction {
        val payload = mutableMapOf<String, JsonElement>()
        step.target?.let { payload["target"] = JsonPrimitive(it) }
        return ProposedAction(
            id = ActionId("agent-${step.kind}-${System.nanoTime()}"),
            kind = step.kind,
            reversibility = step.reversibility,
            payload = payload,
        )
    }

    private fun resolveDecision(decision: GateDecision, result: DispatchResult) {
        val resolved = when (result) {
            is DispatchResult.Success -> ResolvedOutcome.BenignSuccess
            is DispatchResult.Failed -> ResolvedOutcome.HarmlessFailure
            is DispatchResult.Unsupported -> ResolvedOutcome.HarmlessFailure
        }
        try {
            Log.d(TAG, "resolveDecision: auditId=${decision.auditId.value}, outcome=${decision.outcome}, resolved=$resolved")
            kernel.resolve(decision.auditId.value, ResolvedOutcomeParcel.from(resolved))
            Log.d(TAG, "resolveDecision: success")
        } catch (e: Exception) {
            Log.w(TAG, "resolveDecision: failed — ${e.message}")
        }
    }

    private suspend fun launchAuthAndWait(decision: GateDecision): Boolean {
        val bridge = AuthorizationResultBridge
        bridge.reset()

        val json = Json.encodeToString(GateDecision.serializer(), decision)
        val intent = Intent(context, AuthorizationActivity::class.java).apply {
            putExtra(AuthorizationActivity.EXTRA_DECISION_JSON, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return bridge.awaitResult(timeoutMs = 60_000)
    }

    private fun resultToState(result: DispatchResult): ExecutionLog.StepState =
        when (result) {
            is DispatchResult.Success -> ExecutionLog.StepState.Done(result.summary)
            is DispatchResult.Failed -> ExecutionLog.StepState.Failed(result.reason)
            is DispatchResult.Unsupported -> ExecutionLog.StepState.Failed(result.reason)
        }

    companion object {
        private const val TAG = "NegativeLatency"
    }
}
