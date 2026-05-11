package dev.governance.android.app.agent

import android.content.Context
import android.content.Intent
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
 * Speculative dispatch orchestrator — the zero-latency core.
 *
 * For [Reversibility.FullyReversible] steps in [ActionTier.App], the
 * dispatcher's reversible work (intent fire, app preload, share sheet
 * open) starts the moment the planner emits the candidate. The kernel's
 * `decide()` runs in parallel. The user perceives the loaded app
 * before they finish speaking.
 *
 * Speculation rules:
 * - [Reversibility.FullyReversible] + [ActionTier.App] → speculate (preflight)
 * - All other combinations → wait for kernel decision before any dispatch
 *
 * Speculation outcomes:
 * - PASS → commit the speculative work (mark [SpeculationLog] confirmed).
 *   The intent already fired and the app is on screen; nothing more to do.
 * - HOLD → speculation stays as-is (the reversible action is informational;
 *   when the user approves, the agent reports completion; when the user
 *   denies, the agent records the speculative dispatch as undone).
 * - VETO → roll back. Best-effort: if the dispatcher recorded a closeable
 *   side effect, attempt to close it. For pure reversible work like
 *   "open Instagram," there's nothing to roll back beyond logging the
 *   speculative dispatch as overruled.
 *
 * This orchestrator never speculates on root-tier or non-reversible
 * actions. The kernel still gates the eventual commit; the kernel signs
 * the attestation; nothing about the cryptographic trust chain changes.
 * Speculation is purely a UX latency win on actions where the gate's
 * answer is statistically very likely to be PASS.
 */
class SpeculativeOrchestrator(
    private val context: Context,
    private val planner: Planner,
    private val kernel: AgentKernelInterface,
    private val dispatcher: ActionDispatcher,
    private val speculationLog: SpeculationLog = SpeculationLog(),
) {

    suspend fun execute(userInstruction: String): Triple<PlanResult, ExecutionLog?, SpeculationLog> {
        val planResult = planner.plan(userInstruction)
        if (planResult is PlanResult.Error) return Triple(planResult, null, speculationLog)

        val plan = (planResult as PlanResult.Success).plan
        val log = ExecutionLog(plan)

        for ((index, step) in plan.steps.withIndex()) {
            val tier = ActionTier.classify(step.kind)
            val canSpeculate = tier == ActionTier.App &&
                step.reversibility == Reversibility.FullyReversible

            if (canSpeculate) {
                executeSpeculative(index, step, log)
            } else {
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
            speculationLog.recordRolledBack(speculationId, "kernel error")
            log.update(index, ExecutionLog.StepState.Failed("Kernel error during gate check"))
            return@coroutineScope
        }

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
            kernel.resolve(decision.auditId.value, ResolvedOutcomeParcel.from(resolved))
        } catch (_: Exception) {
            // Best-effort resolve
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
}
