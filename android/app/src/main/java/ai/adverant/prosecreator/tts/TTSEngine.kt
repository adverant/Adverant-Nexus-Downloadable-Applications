package ai.adverant.prosecreator.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TTSEngine — Wraps sherpa-onnx OfflineTts for Kokoro model inference.
 *
 * Loads the Kokoro-en-v0_19 ONNX model and generates PCM audio,
 * then encodes as WAV (16-bit, 24000 Hz mono).
 *
 * Thread-safe: synthesize() is synchronized to prevent concurrent ONNX sessions
 * from corrupting each other (sherpa-onnx is not inherently thread-safe for a
 * single model instance).
 */
class TTSEngine private constructor(
    private val tts: OfflineTts,
    private val modelDir: File,
) {

    companion object {
        private const val TAG = "TTSEngine"
        const val SAMPLE_RATE = 24000
        private const val BITS_PER_SAMPLE = 16
        private const val NUM_CHANNELS = 1

        /**
         * All Kokoro voice presets — 54 voices organized by language/gender.
         * These match the .bin files in the voices/ directory.
         */
        val KOKORO_VOICES: Map<String, String> = mapOf(
            // American Female
            "af_alloy" to "Alloy (American Female)",
            "af_aoede" to "Aoede (American Female)",
            "af_bella" to "Bella (American Female)",
            "af_heart" to "Heart (American Female)",
            "af_jessica" to "Jessica (American Female)",
            "af_kore" to "Kore (American Female)",
            "af_nicole" to "Nicole (American Female)",
            "af_nova" to "Nova (American Female)",
            "af_river" to "River (American Female)",
            "af_sarah" to "Sarah (American Female)",
            "af_sky" to "Sky (American Female)",
            // American Male
            "am_adam" to "Adam (American Male)",
            "am_echo" to "Echo (American Male)",
            "am_eric" to "Eric (American Male)",
            "am_fenrir" to "Fenrir (American Male)",
            "am_liam" to "Liam (American Male)",
            "am_michael" to "Michael (American Male)",
            "am_onyx" to "Onyx (American Male)",
            "am_puck" to "Puck (American Male)",
            // British Female
            "bf_alice" to "Alice (British Female)",
            "bf_emma" to "Emma (British Female)",
            "bf_isabella" to "Isabella (British Female)",
            "bf_lily" to "Lily (British Female)",
            // British Male
            "bm_daniel" to "Daniel (British Male)",
            "bm_fable" to "Fable (British Male)",
            "bm_george" to "George (British Male)",
            "bm_lewis" to "Lewis (British Male)",
        )

        /** Default voice for narration */
        const val DEFAULT_VOICE = "af_sky"

        /**
         * Create a TTSEngine from model files in the given directory.
         * Returns null if initialization fails.
         */
        fun create(modelDir: File): TTSEngine? {
            return try {
                val modelPath = File(modelDir, "model.onnx")
                val tokensPath = File(modelDir, "tokens.txt")
                val dataDir = File(modelDir, "espeak-ng-data")

                if (!modelPath.exists()) {
                    Log.e(TAG, "Model file not found: ${modelPath.absolutePath}")
                    return null
                }
                if (!tokensPath.exists()) {
                    Log.e(TAG, "Tokens file not found: ${tokensPath.absolutePath}")
                    return null
                }

                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = modelPath.absolutePath,
                    tokens = tokensPath.absolutePath,
                    dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
                    lengthScale = 1.0f,
                )

                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                )

                val config = OfflineTtsConfig(
                    model = modelConfig,
                    maxNumSentences = 1,
                    ruleFsts = "",
                    ruleFars = "",
                )

                val tts = OfflineTts(config = config)
                Log.i(TAG, "TTSEngine initialized: sampleRate=${tts.sampleRate()}")

                TTSEngine(tts, modelDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TTSEngine", e)
                null
            }
        }
    }

    /**
     * Synthesize text to WAV audio bytes.
     *
     * @param text Input text to synthesize.
     * @param voice Voice preset ID (e.g., "af_sky"). Must match a .bin file in voices/.
     * @param speed Speech speed multiplier (0.5 - 2.0). Default 1.0.
     * @return WAV audio as ByteArray, or null on failure.
     */
    @Synchronized
    fun synthesize(text: String, voice: String = DEFAULT_VOICE, speed: Float = 1.0f): ByteArray? {
        if (text.isBlank()) return null

        return try {
            val speakerId = resolveVoiceSpeakerId(voice)
            val audio = tts.generateWithCallback(
                text = text,
                sid = speakerId,
                speed = speed,
                callback = { _ -> 0 } // 0 = continue generating
            )

            if (audio.samples.isEmpty()) {
                Log.w(TAG, "Empty audio generated for text: ${text.take(50)}")
                return null
            }

            encodeWav(audio.samples, audio.sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed for voice=$voice speed=$speed text=${text.take(50)}", e)
            null
        }
    }

    /**
     * Get the sample rate of the loaded model.
     */
    fun getSampleRate(): Int = tts.sampleRate()

    /**
     * Get list of available voices (those with .bin files present).
     */
    fun getAvailableVoices(): Map<String, String> {
        val voicesDir = File(modelDir, "voices")
        if (!voicesDir.exists()) return KOKORO_VOICES // return all if no voices dir

        val availableBins = voicesDir.listFiles()
            ?.filter { it.extension == "bin" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: return KOKORO_VOICES

        return KOKORO_VOICES.filter { (id, _) -> availableBins.contains(id) }
            .ifEmpty { KOKORO_VOICES } // fallback to full list if filter yields nothing
    }

    /**
     * Check if a specific voice is available.
     */
    fun isVoiceAvailable(voiceId: String): Boolean {
        val voiceFile = File(modelDir, "voices/$voiceId.bin")
        return voiceFile.exists()
    }

    /**
     * Release native resources.
     */
    fun release() {
        try {
            // OfflineTts handles its own cleanup via finalizer;
            // explicit release not exposed in the Java API.
            Log.i(TAG, "TTSEngine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing TTSEngine", e)
        }
    }

    // ── Private Helpers ─────────────────────────────────────────────────

    /**
     * Resolve a voice name to a speaker ID.
     *
     * sherpa-onnx Kokoro uses speaker ID (int) for voice selection.
     * The mapping is alphabetical order of voice .bin files.
     * We maintain a sorted list and find the index.
     */
    private fun resolveVoiceSpeakerId(voice: String): Int {
        val voicesDir = File(modelDir, "voices")
        if (!voicesDir.exists()) return 0

        val sortedVoices = voicesDir.listFiles()
            ?.filter { it.extension == "bin" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: return 0

        val idx = sortedVoices.indexOf(voice)
        return if (idx >= 0) idx else 0
    }

    /**
     * Encode PCM float samples as a WAV byte array.
     * Input: float samples in [-1.0, 1.0] range.
     * Output: 16-bit PCM WAV, mono, at the given sample rate.
     */
    private fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcmBytes = samples.size * 2 // 16-bit = 2 bytes per sample
        val wavSize = 44 + pcmBytes

        val buffer = ByteBuffer.allocate(wavSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(wavSize - 8)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // chunk size
        buffer.putShort(1) // PCM format
        buffer.putShort(NUM_CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8) // byte rate
        buffer.putShort((NUM_CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
        buffer.putShort(BITS_PER_SAMPLE.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(pcmBytes)

        // Convert float [-1.0, 1.0] to 16-bit signed integers
        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val intVal = (clamped * 32767.0f).toInt().toShort()
            buffer.putShort(intVal)
        }

        return buffer.array()
    }
}
