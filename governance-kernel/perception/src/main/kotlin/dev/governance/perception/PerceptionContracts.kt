package dev.governance.perception

/**
 * Contract for capturing the current screen state. Implemented by
 * the Android accessibility service layer (`AccessibilityObservationService`).
 */
interface TreeCapture {
    fun captureTree(): ScreenTree?
}

/**
 * Contract for executing typed UI actions. Implemented by the Android
 * action executor (`UiInteractor`).
 */
interface NodeActionExecutor {
    suspend fun execute(action: NodeAction, currentTree: ScreenTree?): ActionOutcome
}
