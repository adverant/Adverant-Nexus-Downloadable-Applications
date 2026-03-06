package ai.adverant.prosecreator.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TTSEngine — Wraps sherpa-onnx OfflineTts for Kokoro model inference.
 *
 * Uses OfflineTtsKokoroModelConfig (NOT VitsModelConfig) for Kokoro-82M.
 * Generates PCM audio encoded as WAV (16-bit, 24000 Hz mono).
 *
 * Voice selection uses speaker IDs mapped from voices.bin. The voices
 * are embedded in alphabetical order within the single voices.bin file.
 *
 * Thread-safe: synthesize() is synchronized.
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
         * All Kokoro voice presets — sorted alphabetically.
         * Speaker IDs in voices.bin follow this exact order (0-indexed).
         */
        val KOKORO_VOICES: Map<String, String> = linkedMapOf(
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
            "am_adam" to "Adam (American Male)",
            "am_echo" to "Echo (American Male)",
            "am_eric" to "Eric (American Male)",
            "am_fenrir" to "Fenrir (American Male)",
            "am_liam" to "Liam (American Male)",
            "am_michael" to "Michael (American Male)",
            "am_onyx" to "Onyx (American Male)",
            "am_puck" to "Puck (American Male)",
            "bf_alice" to "Alice (British Female)",
            "bf_emma" to "Emma (British Female)",
            "bf_isabella" to "Isabella (British Female)",
            "bf_lily" to "Lily (British Female)",
            "bm_daniel" to "Daniel (British Male)",
            "bm_fable" to "Fable (British Male)",
            "bm_george" to "George (British Male)",
            "bm_lewis" to "Lewis (British Male)",
        )

        /** Sorted voice IDs — index = speaker ID in voices.bin. */
        private val SORTED_VOICE_IDS: List<String> = KOKORO_VOICES.keys.sorted()

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
                val voicesPath = File(modelDir, "voices.bin")
                val dataDir = File(modelDir, "espeak-ng-data")

                if (!modelPath.exists()) {
                    Log.e(TAG, "Model file not found: ${modelPath.absolutePath}")
                    return null
                }
                if (!tokensPath.exists()) {
                    Log.e(TAG, "Tokens file not found: ${tokensPath.absolutePath}")
                    return null
                }

                Log.i(TAG, "Loading Kokoro model: ${modelPath.absolutePath} " +
                        "(${modelPath.length() / 1_000_000}MB)")
                Log.i(TAG, "Voices: ${voicesPath.absolutePath} (exists=${voicesPath.exists()})")
                Log.i(TAG, "DataDir: ${dataDir.absolutePath} (exists=${dataDir.exists()})")

                // Use OfflineTtsKokoroModelConfig for Kokoro models (NOT VitsModelConfig)
                val kokoroConfig = OfflineTtsKokoroModelConfig(
                    model = modelPath.absolutePath,
                    voices = if (voicesPath.exists()) voicesPath.absolutePath else "",
                    tokens = tokensPath.absolutePath,
                    dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
                    lexicon = "",
                    lang = "en",
                    dictDir = "",
                    lengthScale = 1.0f,
                )

                val modelConfig = OfflineTtsModelConfig(
                    kokoro = kokoroConfig,
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
                Log.i(TAG, "TTSEngine initialized: sampleRate=${tts.sampleRate()}, " +
                        "numSpeakers=${tts.numSpeakers()}")

                TTSEngine(tts, modelDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TTSEngine", e)
                null
            }
        }
    }

    /**
     * Synthesize text to WAV audio bytes.
     */
    @Synchronized
    fun synthesize(text: String, voice: String = DEFAULT_VOICE, speed: Float = 1.0f): ByteArray? {
        if (text.isBlank()) return null

        return try {
            val speakerId = resolveVoiceSpeakerId(voice)
            Log.d(TAG, "Synthesizing: voice=$voice sid=$speakerId speed=$speed text=${text.take(50)}")

            val audio = tts.generateWithCallback(
                text = text,
                sid = speakerId,
                speed = speed,
                callback = { _ -> 0 }
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

    fun getSampleRate(): Int = tts.sampleRate()

    fun getAvailableVoices(): Map<String, String> = KOKORO_VOICES

    fun isVoiceAvailable(voiceId: String): Boolean = KOKORO_VOICES.containsKey(voiceId)

    fun release() {
        try {
            Log.i(TAG, "TTSEngine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing TTSEngine", e)
        }
    }

    private fun resolveVoiceSpeakerId(voice: String): Int {
        val idx = SORTED_VOICE_IDS.indexOf(voice)
        return if (idx >= 0) idx else {
            Log.w(TAG, "Unknown voice '$voice', falling back to $DEFAULT_VOICE")
            SORTED_VOICE_IDS.indexOf(DEFAULT_VOICE).coerceAtLeast(0)
        }
    }

    private fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcmBytes = samples.size * 2
        val wavSize = 44 + pcmBytes

        val buffer = ByteBuffer.allocate(wavSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(wavSize - 8)
        buffer.put("WAVE".toByteArray())

        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(NUM_CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8)
        buffer.putShort((NUM_CHANNELS * BITS_PER_SAMPLE / 8).toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())

        buffer.put("data".toByteArray())
        buffer.putInt(pcmBytes)

        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val intVal = (clamped * 32767.0f).toInt().toShort()
            buffer.putShort(intVal)
        }

        return buffer.array()
    }
}
