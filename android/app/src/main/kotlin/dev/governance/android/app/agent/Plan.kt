package dev.governance.android.app.agent

import androidx.compose.runtime.mutableStateListOf
import dev.governance.core.Reversibility

/**
 * A plan produced by the LLM planner: a summary and ordered
 * list of actions to execute.
 */
data class Plan(
    val summary: String,
    val steps: List<PlannedStep>,
)

data class PlannedStep(
    val kind: String,
    val target: String?,
    val rationale: String,
    val reversibility: Reversibility,
)

/** Result of attempting to plan from a user instruction. */
sealed class PlanResult {
    data class Success(val plan: Plan) : PlanResult()
    data class Error(val message: String) : PlanResult()
}

/** Result of attempting to dispatch a single action. */
sealed class DispatchResult {
    data class Success(val summary: String) : DispatchResult()
    data class Failed(val reason: String) : DispatchResult()
    data class Unsupported(val reason: String) : DispatchResult()
}

/**
 * Observable execution state for a plan. The chat UI observes
 * [stepStates] to render per-step status in real time.
 */
class ExecutionLog(val plan: Plan) {
    val stepStates = mutableStateListOf<StepState>().apply {
        repeat(plan.steps.size) { add(StepState.Pending) }
    }
    var finished = false
        private set

    fun update(index: Int, state: StepState) {
        if (index in stepStates.indices) stepStates[index] = state
    }

    fun markFinished() { finished = true }

    sealed class StepState {
        data object Pending : StepState()
        data object GateChecking : StepState()
        data object AwaitingApproval : StepState()
        data object Executing : StepState()
        data class Done(val result: String) : StepState()
        data class Failed(val reason: String) : StepState()
        data class Skipped(val reason: String) : StepState()
        data class Vetoed(val reason: String) : StepState()
    }
}
