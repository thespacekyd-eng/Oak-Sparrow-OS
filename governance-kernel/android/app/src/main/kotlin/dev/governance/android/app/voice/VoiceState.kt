package dev.governance.android.app.voice

/**
 * The state of the voice interaction loop.
 *
 * Transitions:
 * ```
 *   Idle ──startListening──▶ Listening ──onResults──▶ Heard
 *    ▲                          │                       │
 *    │                          └──onError──▶ Error     │
 *    │                                          │       │
 *    │                                          └───┐   │
 *    │                                              ▼   ▼
 *    └────────────────────────────── speak() done ◀── Speaking
 * ```
 */
sealed interface VoiceState {

    /** Not listening, not speaking. The UI shows a tappable mic. */
    data object Idle : VoiceState

    /** Microphone is open; partial transcripts may stream in. */
    data class Listening(val partial: String = "", val amplitude: Float = 0f) : VoiceState

    /** Recognition complete. The full transcript is ready to dispatch. */
    data class Heard(val transcript: String) : VoiceState

    /** TTS is currently speaking the agent's response. */
    data class Speaking(val text: String, val amplitude: Float = 0f) : VoiceState

    /** Something failed. Reason is human-readable. */
    data class Error(val reason: String) : VoiceState

    companion object {
        fun next(current: VoiceState, event: Event): VoiceState? = when (event) {
            is Event.Start -> when (current) {
                is Idle, is Heard, is Error -> Listening()
                else -> null
            }
            is Event.Partial -> when (current) {
                is Listening -> Listening(event.text, current.amplitude)
                else -> null
            }
            is Event.Final -> when (current) {
                is Listening -> Heard(event.text)
                else -> null
            }
            is Event.SpeakStart -> when (current) {
                is Heard, is Idle, is Error -> Speaking(event.text)
                else -> null
            }
            is Event.SpeakDone -> when (current) {
                is Speaking -> Idle
                else -> null
            }
            is Event.Amplitude -> when (current) {
                is Listening -> current.copy(amplitude = event.level)
                is Speaking -> current.copy(amplitude = event.level)
                else -> null
            }
            is Event.Cancel -> Idle
            is Event.Fail -> Error(event.reason)
        }
    }

    sealed interface Event {
        data object Start : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class SpeakStart(val text: String) : Event
        data object SpeakDone : Event
        /** Audio amplitude update (0.0-1.0) for reactive animation. */
        data class Amplitude(val level: Float) : Event
        data object Cancel : Event
        data class Fail(val reason: String) : Event
    }
}
