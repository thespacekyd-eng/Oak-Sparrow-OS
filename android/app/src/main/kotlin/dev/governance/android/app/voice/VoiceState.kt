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
 *
 * Pure data — does not own any Android resources. The
 * [VoiceController] is responsible for transitioning between states
 * and exposing them via a [kotlinx.coroutines.flow.StateFlow].
 *
 * Designed for test-friendly state-machine assertions: the
 * [Companion.next] helper enforces the legal transitions in one place
 * so the controller and the tests agree.
 */
sealed interface VoiceState {

    /** Not listening, not speaking. The UI shows a tappable mic. */
    data object Idle : VoiceState

    /** Microphone is open; partial transcripts may stream in. */
    data class Listening(val partial: String = "") : VoiceState

    /** Recognition complete. The full transcript is ready to dispatch. */
    data class Heard(val transcript: String) : VoiceState

    /** TTS is currently speaking the agent's response. */
    data class Speaking(val text: String) : VoiceState

    /** Something failed. Reason is human-readable. */
    data class Error(val reason: String) : VoiceState

    companion object {
        /**
         * Returns the next legal state given a current state and an
         * event, or `null` if the transition is illegal. Centralizes the
         * state machine so the controller and tests share one source of
         * truth.
         */
        fun next(current: VoiceState, event: Event): VoiceState? = when (event) {
            is Event.Start -> when (current) {
                is Idle, is Heard, is Error -> Listening()
                else -> null
            }
            is Event.Partial -> when (current) {
                is Listening -> Listening(event.text)
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
            is Event.Cancel -> Idle
            is Event.Fail -> Error(event.reason)
        }
    }

    /** Inputs to the state machine. */
    sealed interface Event {
        /** User tapped the mic / assist invocation. */
        data object Start : Event
        /** Speech recognizer emitted a partial transcript. */
        data class Partial(val text: String) : Event
        /** Speech recognizer emitted a final transcript. */
        data class Final(val text: String) : Event
        /** TTS is starting an utterance. */
        data class SpeakStart(val text: String) : Event
        /** TTS finished an utterance. */
        data object SpeakDone : Event
        /** User cancelled or system aborted. */
        data object Cancel : Event
        /** Anything went wrong; transitions to Error with reason. */
        data class Fail(val reason: String) : Event
    }
}
