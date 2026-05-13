package dev.governance.android.app.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates [OakVoiceInteractionSession] instances for each assist
 * invocation. The session immediately launches [AssistantActivity]
 * and finishes — it's a trampoline, not a persistent UI host.
 */
class OakVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OakVoiceInteractionSession(this)
    }
}
