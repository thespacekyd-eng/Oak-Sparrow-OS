package dev.governance.android.app.voice

import android.content.Intent
import android.speech.RecognitionService

/**
 * Stub [RecognitionService] required by Samsung One UI to consider
 * the VoiceInteractionService "qualified" for the digital assistant
 * role. Without this, Samsung's assistant picker silently rejects
 * the app even though stock Android accepts it fine.
 *
 * This service is never actually used for recognition — Oak uses
 * [android.speech.SpeechRecognizer] directly via [VoiceController].
 */
class OakRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {
        callback?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(callback: Callback?) {}
    override fun onStopListening(callback: Callback?) {}
}
