package dev.governance.android.app.agent

import android.content.Context
import android.content.Intent
import dev.governance.android.app.AuthorizationActivity
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.android.app.AgentKernelInterface
import dev.governance.core.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * End-to-end agent orchestrator. Ties the LLM planner to the
 * governance kernel and the action dispatcher.
 *
 * For each planned step:
 * 1. Proposes the action to the kernel via AIDL
 * 2. On PASS → dispatches immediately
 * 3. On HOLD → launches AuthorizationActivity, waits for result
 * 4. On VETO → stops the plan
 *
 * The [ExecutionLog] is observable — the chat UI watches it
 * for real-time status updates.
 */
class AgentOrchestrator(
    private val context: Context,
    private val planner: Planner,
    private val kernel: AgentKernelInterface,
    private val dispatcher: ActionDispatcher,
) {
    suspend fun execute(userInstruction: String): Pair<PlanResult, ExecutionLog?> {
        val planResult = planner.plan(userInstruction)
        if (planResult is PlanResult.Error) return planResult to null

        val plan = (planResult as PlanResult.Success).plan
        val log = ExecutionLog(plan)

        for ((index, step) in plan.steps.withIndex()) {
            log.update(index, ExecutionLog.StepState.GateChecking)
            delay(300) // brief pause so UI shows the transition

            val proposed = ProposedAction(
                id = ActionId("agent-${step.kind}-${System.nanoTime()}"),
                kind = step.kind,
                reversibility = step.reversibility,
                payload = buildPayload(step),
            )

            val decision: GateDecision
            try {
                decision = kernel.decide(ProposedActionParcel.from(proposed)).toKernel()
            } catch (e: Exception) {
                log.update(index, ExecutionLog.StepState.Failed("Kernel error: ${e.message}"))
                log.markFinished()
                return planResult to log
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

                    // Launch the auth dialog and wait for user decision
                    val approved = launchAuthAndWait(decision)

                    if (approved) {
                        log.update(index, ExecutionLog.StepState.Executing)
                        val result = dispatcher.dispatch(decision, step)
                        resolveDecision(decision, result)
                        log.update(index, resultToState(result))
                    } else {
                        log.update(index, ExecutionLog.StepState.Skipped("User declined"))
                        log.markFinished()
                        return planResult to log
                    }
                }
                Outcome.VETO -> {
                    log.update(index, ExecutionLog.StepState.Vetoed(decision.rationale))
                    log.markFinished()
                    return planResult to log
                }
            }
        }

        log.markFinished()
        return planResult to log
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
            // Best-effort resolve — don't crash the orchestrator
        }
    }

    private suspend fun launchAuthAndWait(decision: GateDecision): Boolean {
        val bridge = AuthorizationResultBridge
        val auditId = decision.auditId.value
        bridge.reset()
        bridge.create(auditId)

        val json = Json.encodeToString(GateDecision.serializer(), decision)
        val intent = Intent(context, AuthorizationActivity::class.java).apply {
            putExtra(AuthorizationActivity.EXTRA_DECISION_JSON, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        // Wait for user to approve or skip (max 60 seconds)
        // Must exceed the dialog's 14s auto-deny plus normal user
        // reaction time. 60s gives the user ample time to read and decide.
        return bridge.awaitResult(auditId, timeoutMs = 60_000)
    }

    private fun resultToState(result: DispatchResult): ExecutionLog.StepState =
        when (result) {
            is DispatchResult.Success -> ExecutionLog.StepState.Done(result.summary)
            is DispatchResult.Failed -> ExecutionLog.StepState.Failed(result.reason)
            is DispatchResult.Unsupported -> ExecutionLog.StepState.Failed(result.reason)
        }

    private fun buildPayload(step: PlannedStep): Map<String, kotlinx.serialization.json.JsonElement> {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        step.target?.let {
            map["target"] = kotlinx.serialization.json.JsonPrimitive(it)
        }
        return map
    }
}
