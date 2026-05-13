package dev.governance.android.app.voice

import android.service.voice.VoiceInteractionService

/**
 * Minimal [VoiceInteractionService] that registers Oak & Sparrow OS
 * in the system's "Digital assistant app" picker (Settings → Apps →
 * Default apps → Digital assistant app). Samsung One UI requires this
 * service to be present — a bare ACTION_ASSIST activity is not enough.
 *
 * The actual assistant UI lives in [dev.governance.android.app.AssistantActivity];
 * this service + session just bridges the system gesture to that activity.
 */
class OakVoiceInteractionService : VoiceInteractionService()
