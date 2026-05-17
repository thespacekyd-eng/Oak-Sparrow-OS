package dev.governance.android.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Oak's own neural TTS engine powered by sherpa-onnx + Kokoro.
 *
 * Fully on-device, no internet needed. Produces natural-sounding
 * speech at 24kHz via the Kokoro-82M int8 model.
 *
 * ## Model location
 *
 * The model files are stored on the device filesystem at
 * [MODEL_DEVICE_PATH]. Push them with `setup-kokoro-tts.sh`.
 * The engine checks [isAvailable] before use and falls back
 * to Android's built-in TTS if the model isn't present.
 *
 * ## Voices
 *
 * | ID | Name        | Gender/Accent      |
 * |----|-------------|--------------------|
 * | 0  | af          | Female American    |
 * | 1  | af_bella    | Female American    |
 * | 2  | af_nicole   | Female American    |
 * | 3  | af_sarah    | Female American    |
 * | 4  | af_sky      | Female American    |
 * | 5  | am_adam     | Male American      |
 * | 6  | am_michael  | Male American      |
 * | 7  | bf_emma     | Female British     |
 * | 8  | bf_isabella | Female British     |
 * | 9  | bm_george   | Male British       |
 * | 10 | bm_lewis    | Male British       |
 */
class OakTtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var isSpeaking = false

    /** Callback invoked when speech finishes or is interrupted. */
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Returns true if the Kokoro model files are present on the device. */
    val isAvailable: Boolean
        get() {
            val modelDir = modelDir()
            return modelDir != null &&
                File(modelDir, "model.int8.onnx").exists() &&
                File(modelDir, "voices.bin").exists() &&
                File(modelDir, "tokens.txt").exists()
        }

    /**
     * Initialize the engine. Call once at startup.
     * Returns true if initialization succeeded.
     */
    fun init(): Boolean {
        if (tts != null) return true
        val dir = modelDir() ?: return false

        return try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$dir/model.int8.onnx",
                        voices = "$dir/voices.bin",
                        tokens = "$dir/tokens.txt",
                        dataDir = "$dir/espeak-ng-data",
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                    debug = false,
                    provider = "cpu",
                ),
            )
            tts = OfflineTts(config = config)
            Log.i(TAG, "Kokoro TTS initialized: ${tts!!.numSpeakers()} voices, ${tts!!.sampleRate()}Hz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Kokoro TTS", e)
            tts = null
            false
        }
    }

    /**
     * Speak text using the Kokoro neural voice.
     *
     * Splits text into sentences and pipelines generation with playback:
     * generates sentence 1, starts playing it, generates sentence 2
     * while sentence 1 plays, etc. This cuts perceived latency
     * dramatically — the user hears the first sentence in ~1-2s
     * instead of waiting for the entire response to generate.
     */
    suspend fun speak(
        text: String,
        voiceId: Int = DEFAULT_VOICE,
        speed: Float = 1.0f,
    ) = withContext(Dispatchers.IO) {
        val engine = tts ?: run {
            onError?.invoke("TTS engine not initialized")
            return@withContext
        }

        if (text.isBlank()) {
            onDone?.invoke()
            return@withContext
        }

        val clean = cleanText(text)
        if (clean.isBlank()) {
            onDone?.invoke()
            return@withContext
        }

        isSpeaking = true

        try {
            val sampleRate = engine.sampleRate()
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .build()

            audioTrack = track
            track.play()

            val genConfig = GenerationConfig(
                sid = voiceId,
                speed = speed,
                silenceScale = 0.2f,
            )

            // Split into sentences for pipelined generation + playback.
            // First sentence plays as soon as it's generated while the
            // rest are still being synthesized.
            val sentences = splitSentences(clean)

            for (sentence in sentences) {
                if (!isSpeaking) break
                val audio = engine.generateWithConfig(
                    text = sentence,
                    config = genConfig,
                )
                if (!isSpeaking) break
                if (audio.samples.isNotEmpty()) {
                    track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                }
            }

            track.stop()
            track.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Kokoro TTS playback error", e)
            onError?.invoke("TTS playback failed: ${e.message}")
        } finally {
            isSpeaking = false
            onDone?.invoke()
        }
    }

    /** Stop current speech immediately. */
    fun stop() {
        isSpeaking = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    /** Release all resources. */
    fun shutdown() {
        stop()
        tts?.release()
        tts = null
    }

    private fun modelDir(): String? {
        // Check device-pushed location first
        val devicePath = File(MODEL_DEVICE_PATH)
        if (devicePath.exists()) return devicePath.absolutePath

        // Check app-private files dir
        val appPath = File(context.filesDir, "kokoro-tts")
        if (appPath.exists()) return appPath.absolutePath

        return null
    }

    /**
     * Splits text into sentences for pipelined TTS. Keeps short
     * sentences together to avoid too many tiny chunks (overhead).
     */
    private fun splitSentences(text: String): List<String> {
        // Split on sentence-ending punctuation followed by space
        val raw = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (raw.size <= 1) return listOf(text)

        // Merge short fragments (< 40 chars) with the next sentence
        // to avoid tiny chunks that add generation overhead
        val merged = mutableListOf<String>()
        var buffer = ""
        for (s in raw) {
            buffer = if (buffer.isEmpty()) s else "$buffer $s"
            if (buffer.length >= 40 || s == raw.last()) {
                merged.add(buffer)
                buffer = ""
            }
        }
        if (buffer.isNotBlank()) merged.add(buffer)
        return merged
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("[\\x{1F600}-\\x{1F64F}]"), "")
            .replace(Regex("[\\x{1F300}-\\x{1F5FF}]"), "")
            .replace(Regex("[\\x{1F680}-\\x{1F6FF}]"), "")
            .replace(Regex("[\\x{1F1E0}-\\x{1F1FF}]"), "")
            .replace(Regex("[\\x{2600}-\\x{27BF}]"), "")
            .replace(Regex("[\\x{FE00}-\\x{FE0F}]"), "")
            .replace(Regex("[\\x{1F900}-\\x{1F9FF}]"), "")
            .replace(Regex("[\\x{200D}]"), "")
            .replace(Regex("[\\x{20E3}]"), "")
            .replace(Regex("[\\x{2702}-\\x{27B0}]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    companion object {
        private const val TAG = "OakTTS"
        const val MODEL_DEVICE_PATH = "/data/local/tmp/oak-tts/kokoro-int8-en-v0_19"
        const val DEFAULT_VOICE = 1 // af_bella — warm female American

        // Voice name lookup
        val VOICE_NAMES = mapOf(
            0 to "Default (Female)",
            1 to "Bella (Female)",
            2 to "Nicole (Female)",
            3 to "Sarah (Female)",
            4 to "Sky (Female)",
            5 to "Adam (Male)",
            6 to "Michael (Male)",
            7 to "Emma (British F)",
            8 to "Isabella (British F)",
            9 to "George (British M)",
            10 to "Lewis (British M)",
        )
    }
}
