package dev.governance.android.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dev.governance.android.app.AssistantActivity

/**
 * Trampoline session: launches [AssistantActivity] and hides itself.
 * The assistant overlay handles mic capture, LLM planning, and TTS
 * response — this session just bridges the system assist gesture to it.
 */
class OakVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        hide()
    }
}
