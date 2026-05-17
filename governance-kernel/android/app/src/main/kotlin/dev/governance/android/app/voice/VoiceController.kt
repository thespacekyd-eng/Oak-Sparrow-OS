package dev.governance.android.app.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Wraps Android's on-device speech recognition + text-to-speech behind
 * a single [VoiceState]-shaped surface.
 *
 * ## Local-only by design
 *
 * - Recognition uses [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 *   when available (API 31+), with [RecognizerIntent.EXTRA_PREFER_OFFLINE]
 *   set true on the intent as a backstop. On Pixel devices this routes
 *   through Google's on-device speech model — no network, no audio
 *   upload.
 * - Synthesis uses Android's default [TextToSpeech] engine, which on
 *   modern devices is on-device.
 * - Combined with the app's lack of `INTERNET` permission, the entire
 *   voice loop is provably local: even a misbehaving recognition
 *   service cannot reach the network from this app's process.
 *
 * ## Lifecycle
 *
 * Construct once per Activity. Call [shutdown] in `onDestroy`.
 * Listening calls are mutually exclusive — calling [startListening]
 * while [state] is [VoiceState.Listening] is a no-op.
 *
 * ## Permission
 *
 * Caller must hold [Manifest.permission.RECORD_AUDIO]. Use
 * [hasMicrophonePermission] to check before invoking [startListening];
 * the controller will fail-soft if permission is missing.
 *
 * @param context any Activity-or-Application context — used only for
 *                permission checks and TTS engine lookup
 * @param onTranscript callback invoked when a final transcript lands.
 *                     Typically forwards the transcript to the planner.
 */
class VoiceController(
    private val context: Context,
    private val onTranscript: (String) -> Unit,
) {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false

    // Neural TTS (Kokoro via sherpa-onnx) — preferred when available
    private val oakTts = OakTtsEngine(context)
    private val useOakTts: Boolean get() = oakTts.isAvailable
    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Try to initialize Kokoro neural TTS first
        if (oakTts.isAvailable) {
            val ok = oakTts.init()
            Log.i(TAG, "Kokoro neural TTS: ${if (ok) "ready" else "init failed, will use Android TTS"}")
            oakTts.onDone = { transition(VoiceState.Event.SpeakDone) }
            oakTts.onError = { msg -> transition(VoiceState.Event.Fail(msg)) }
        }

        // Pre-initialize Android TTS as fallback
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                selectBestVoice(tts!!)
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(progressListener)
                ttsReady = true
                Log.i(TAG, "Android TTS fallback ready, voice: ${tts?.voice?.name}")
            } else {
                Log.e(TAG, "Android TTS fallback init failed")
                tts = null
            }
        }
    }

    /**
     * Picks the most natural-sounding voice available on the device.
     * Prefers voices with "network" or "natural" quality, en-US locale,
     * and female voices (typically warmer for assistant use).
     */
    private fun selectBestVoice(engine: TextToSpeech) {
        try {
            val voices = engine.voices ?: return
            val enVoices = voices.filter {
                it.locale.language == "en" && !it.isNetworkConnectionRequired
            }
            if (enVoices.isEmpty()) return

            // Prefer voices with higher quality (lower features set = simpler = often better)
            // Look for voices with "natural", "premium", or "enhanced" in the name
            val preferred = enVoices.sortedWith(
                compareByDescending<android.speech.tts.Voice> { v ->
                    val name = v.name.lowercase()
                    when {
                        name.contains("natural") -> 4
                        name.contains("premium") -> 3
                        name.contains("enhanced") -> 2
                        name.contains("female") || name.contains("woman") -> 1
                        else -> 0
                    }
                }.thenBy { it.quality } // higher quality = better
            )

            val best = preferred.firstOrNull() ?: return
            engine.voice = best
            Log.i(TAG, "Selected voice: ${best.name} (quality=${best.quality}, locale=${best.locale})")
        } catch (e: Exception) {
            Log.w(TAG, "Voice selection failed, using default: ${e.message}")
        }
    }
    /** Tracks whether we tried the on-device recognizer and it failed. */
    private var onDeviceFailed: Boolean = false

    /** Returns true if the microphone permission is currently granted. */
    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns true if any speech recognition service is available. */
    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    /**
     * Starts listening. Idempotent — calling while already listening
     * is a no-op. Transitions [state] through [VoiceState.Listening]
     * and (on success) [VoiceState.Heard], then invokes [onTranscript].
     */
    fun startListening() {
        if (_state.value is VoiceState.Listening) return

        if (!hasMicrophonePermission()) {
            transition(VoiceState.Event.Fail("Microphone permission not granted."))
            return
        }
        if (!isRecognitionAvailable()) {
            transition(VoiceState.Event.Fail(
                "No on-device speech recognition available on this device."))
            return
        }

        try {
            val r = createRecognizer()
            r.setRecognitionListener(buildListener())
            r.startListening(buildRecognizerIntent())
            recognizer = r
            transition(VoiceState.Event.Start)
        } catch (e: Throwable) {
            Log.e(TAG, "startListening threw", e)
            transition(VoiceState.Event.Fail("Recognizer init failed: ${e.message}"))
        }
    }

    /** Cancels in-flight listening and returns to Idle. */
    fun cancelListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        transition(VoiceState.Event.Cancel)
    }

    /**
     * Speaks [text] via TTS. Initializes the engine on first call.
     * If TTS init is in flight, queues the utterance for after init.
     */
    fun speak(text: String) {
        if (text.isBlank()) {
            transition(VoiceState.Event.SpeakDone)
            return
        }

        // Use Kokoro neural TTS if available — much more natural voice
        if (useOakTts) {
            // Stop any in-progress speech first
            oakTts.stop()
            transition(VoiceState.Event.SpeakStart(text))
            ttsScope.launch {
                oakTts.speak(text)
            }
            return
        }

        // Fallback to Android TTS
        // Strip emojis and special unicode symbols so TTS doesn't read
        // "smiling face with open mouth" etc. — speak like a human.
        val clean = text
            .replace(Regex("[\\x{1F600}-\\x{1F64F}]"), "")  // emoticons
            .replace(Regex("[\\x{1F300}-\\x{1F5FF}]"), "")  // symbols & pictographs
            .replace(Regex("[\\x{1F680}-\\x{1F6FF}]"), "")  // transport & map
            .replace(Regex("[\\x{1F1E0}-\\x{1F1FF}]"), "")  // flags
            .replace(Regex("[\\x{2600}-\\x{27BF}]"), "")    // misc symbols
            .replace(Regex("[\\x{FE00}-\\x{FE0F}]"), "")    // variation selectors
            .replace(Regex("[\\x{1F900}-\\x{1F9FF}]"), "")  // supplemental
            .replace(Regex("[\\x{200D}]"), "")               // zero-width joiner
            .replace(Regex("[\\x{20E3}]"), "")               // combining enclosing keycap
            .replace(Regex("[\\x{2702}-\\x{27B0}]"), "")    // dingbats
            .replace(Regex("\\s{2,}"), " ")                  // collapse double spaces
            .trim()
        if (clean.isBlank()) {
            transition(VoiceState.Event.SpeakDone)
            return
        }
        ensureTts {
            val engine = tts ?: return@ensureTts run {
                transition(VoiceState.Event.Fail("TTS engine unavailable."))
            }
            transition(VoiceState.Event.SpeakStart(clean))
            val utteranceId = "u-${System.nanoTime()}"
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /** Stops any in-flight TTS. */
    fun stopSpeaking() {
        oakTts.stop()
        tts?.stop()
        if (_state.value is VoiceState.Speaking) {
            transition(VoiceState.Event.SpeakDone)
        }
    }

    /** Releases all native resources. Call from `onDestroy`. */
    fun shutdown() {
        recognizer?.destroy()
        recognizer = null
        oakTts.shutdown()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        _state.value = VoiceState.Idle
    }

    // ---------- internals ----------

    private fun transition(event: VoiceState.Event) {
        val next = VoiceState.next(_state.value, event)
        if (next != null) {
            Log.d(TAG, "voice: ${_state.value::class.simpleName} -> ${next::class.simpleName}")
            _state.value = next
        } else {
            Log.d(TAG, "voice: rejected ${event::class.simpleName} from ${_state.value::class.simpleName}")
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        // Prefer the explicit on-device path if available AND it hasn't
        // failed before (e.g. missing language pack → error 13).
        // Falls back to the system recognizer with EXTRA_PREFER_OFFLINE.
        return if (!onDeviceFailed &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            Log.i(TAG, "voice: using createOnDeviceSpeechRecognizer (guaranteed local)")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.w(TAG, "voice: using system recognizer with EXTRA_PREFER_OFFLINE")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Only request offline for the on-device recognizer.
            // When falling back to the system recognizer, allow cloud
            // for better accuracy.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !onDeviceFailed)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun buildListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) transition(VoiceState.Event.Partial(text))
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            recognizer?.destroy()
            recognizer = null
            if (text.isBlank()) {
                transition(VoiceState.Event.Fail("Didn't catch that — say it again?"))
                return
            }
            transition(VoiceState.Event.Final(text))
            onTranscript(text)
        }

        override fun onError(error: Int) {
            recognizer?.destroy()
            recognizer = null

            // Error 13 = language pack missing on the on-device recognizer.
            // Retry once with the system (cloud) recognizer.
            if (!onDeviceFailed && (error == 13 || error == SpeechRecognizer.ERROR_SERVER)) {
                Log.w(TAG, "voice: on-device recognizer failed ($error), retrying with system recognizer")
                onDeviceFailed = true
                // Reset state to allow startListening again
                _state.value = VoiceState.Idle
                startListening()
                return
            }

            val reason = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network recognition unavailable — try a Pixel device for on-device speech."
                SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — say it again?"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy."
                SpeechRecognizer.ERROR_SERVER -> "Speech server error."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I waited but didn't hear anything."
                else -> "Recognition error ($error)."
            }
            transition(VoiceState.Event.Fail(reason))
        }
    }

    private fun ensureTts(then: () -> Unit) {
        if (ttsReady) {
            then()
            return
        }
        if (tts != null) {
            // Init still in flight; chain the action.
            tts?.setOnUtteranceProgressListener(progressListener)
            return
        }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                // Slightly faster and lower pitch for a more natural, confident voice
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(0.95f)
                tts?.setOnUtteranceProgressListener(progressListener)
                ttsReady = true
                then()
            } else {
                Log.e(TAG, "TextToSpeech.OnInitListener failed status=$status")
                tts = null
                transition(VoiceState.Event.Fail("TTS init failed."))
            }
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            transition(VoiceState.Event.SpeakDone)
        }
        @Deprecated("Deprecated in Android 21+")
        override fun onError(utteranceId: String?) {
            transition(VoiceState.Event.Fail("TTS playback failed."))
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            transition(VoiceState.Event.Fail("TTS playback failed (code $errorCode)."))
        }
    }

    companion object {
        private const val TAG = "OakSparrowVoice"

        /**
         * Returns true if the device has an on-device TTS engine
         * installed. Useful for surfacing a setup hint when missing.
         */
        fun hasTextToSpeechEngine(context: Context): Boolean {
            val pm = context.packageManager
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            return pm.queryIntentServices(intent, 0).isNotEmpty()
        }
    }
}
