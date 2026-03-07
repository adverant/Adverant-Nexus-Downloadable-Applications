package ai.adverant.prosecreator.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

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

                // Verbose file existence and size logging
                Log.i(TAG, "=== TTSEngine Initialization ===")
                Log.i(TAG, "Model dir: ${modelDir.absolutePath}")
                Log.i(TAG, "  model.onnx: exists=${modelPath.exists()}, size=${modelPath.length()} bytes (${modelPath.length() / 1_000_000}MB)")
                Log.i(TAG, "  tokens.txt: exists=${tokensPath.exists()}, size=${tokensPath.length()} bytes")
                Log.i(TAG, "  voices.bin: exists=${voicesPath.exists()}, size=${voicesPath.length()} bytes (${voicesPath.length() / 1_000}KB)")
                Log.i(TAG, "  espeak-ng-data: exists=${dataDir.exists()}, isDir=${dataDir.isDirectory}")

                if (dataDir.exists() && dataDir.isDirectory) {
                    val espeakFiles = dataDir.listFiles()?.map { "${it.name} (${it.length()})" } ?: emptyList()
                    Log.i(TAG, "  espeak-ng-data contents (${espeakFiles.size} files): $espeakFiles")
                } else {
                    Log.e(TAG, "  espeak-ng-data MISSING or not a directory — phonemizer will fail!")
                }

                // Also check for individual voice .bin files (wrong format)
                val binFiles = modelDir.listFiles()?.filter { it.name.endsWith(".bin") && it.name != "voices.bin" }
                if (!binFiles.isNullOrEmpty()) {
                    Log.w(TAG, "  Found ${binFiles.size} individual .bin files (wrong format for this config): ${binFiles.take(5).map { it.name }}")
                }

                if (!modelPath.exists()) {
                    Log.e(TAG, "FATAL: Model file not found: ${modelPath.absolutePath}")
                    return null
                }
                if (!tokensPath.exists()) {
                    Log.e(TAG, "FATAL: Tokens file not found: ${tokensPath.absolutePath}")
                    return null
                }
                if (!voicesPath.exists()) {
                    Log.w(TAG, "WARNING: voices.bin not found — speaker selection will not work")
                }
                if (!dataDir.exists()) {
                    Log.w(TAG, "WARNING: espeak-ng-data not found — phonemizer may fail, audio may be garbled")
                }

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

                Log.i(TAG, "KokoroConfig: model=${kokoroConfig.model}")
                Log.i(TAG, "KokoroConfig: voices=${kokoroConfig.voices}")
                Log.i(TAG, "KokoroConfig: tokens=${kokoroConfig.tokens}")
                Log.i(TAG, "KokoroConfig: dataDir=${kokoroConfig.dataDir}")
                Log.i(TAG, "KokoroConfig: lang=${kokoroConfig.lang}")

                val modelConfig = OfflineTtsModelConfig(
                    kokoro = kokoroConfig,
                    numThreads = 2,
                    debug = true, // Enable debug for diagnostics
                    provider = "cpu",
                )

                val config = OfflineTtsConfig(
                    model = modelConfig,
                    maxNumSentences = 1,
                    ruleFsts = "",
                    ruleFars = "",
                )

                Log.i(TAG, "Creating OfflineTts instance...")
                val tts = OfflineTts(config = config)
                Log.i(TAG, "TTSEngine initialized: sampleRate=${tts.sampleRate()}, numSpeakers=${tts.numSpeakers()}")

                if (tts.numSpeakers() == 0) {
                    Log.e(TAG, "WARNING: numSpeakers=0 — voices.bin may not have loaded correctly")
                }

                val engine = TTSEngine(tts, modelDir)

                // Run a quick test synthesis to verify the engine works
                Log.i(TAG, "Running test synthesis...")
                val testResult = engine.synthesizeWithDiagnostics("Hello", DEFAULT_VOICE, 1.0f)
                if (testResult.first != null) {
                    Log.i(TAG, "Test synthesis OK: ${testResult.first!!.size} bytes")
                    val diag = testResult.second
                    Log.i(TAG, "Test audio stats: ${diag["audio_stats"]}")
                } else {
                    Log.e(TAG, "Test synthesis FAILED — engine may not produce valid audio")
                }

                Log.i(TAG, "=== TTSEngine Ready ===")
                engine
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
        return synthesizeWithDiagnostics(text, voice, speed).first
    }

    /**
     * Synthesize text and return both WAV bytes and diagnostic info.
     */
    @Synchronized
    fun synthesizeWithDiagnostics(
        text: String,
        voice: String = DEFAULT_VOICE,
        speed: Float = 1.0f,
    ): Pair<ByteArray?, Map<String, Any>> {
        val diagnostics = mutableMapOf<String, Any>()
        diagnostics["voice_requested"] = voice
        diagnostics["speed"] = speed
        diagnostics["text_length"] = text.length
        diagnostics["text_preview"] = text.take(100)

        if (text.isBlank()) {
            diagnostics["error"] = "blank_text"
            return Pair(null, diagnostics)
        }

        return try {
            val speakerId = resolveVoiceSpeakerId(voice)
            diagnostics["speaker_id"] = speakerId
            diagnostics["voice_resolved"] = SORTED_VOICE_IDS.getOrElse(speakerId) { "unknown" }

            Log.d(TAG, "Synthesizing: voice=$voice sid=$speakerId speed=$speed text=${text.take(80)}")

            val startTime = System.nanoTime()
            val audio = tts.generateWithCallback(
                text = text,
                sid = speakerId,
                speed = speed,
                callback = { _ -> 0 }
            )
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

            diagnostics["synthesis_time_ms"] = elapsedMs
            diagnostics["sample_rate"] = audio.sampleRate
            diagnostics["num_samples"] = audio.samples.size

            if (audio.samples.isEmpty()) {
                Log.w(TAG, "Empty audio generated for text: ${text.take(80)}")
                diagnostics["error"] = "empty_audio"
                return Pair(null, diagnostics)
            }

            // Audio quality diagnostics
            val stats = analyzeAudioSamples(audio.samples)
            diagnostics["audio_stats"] = stats
            Log.d(TAG, "Audio stats: $stats")

            // Warn if audio looks garbled
            val zeroRatio = stats["zero_ratio"] as? Float ?: 0f
            val maxAbs = stats["max_abs"] as? Float ?: 0f
            val rms = stats["rms"] as? Float ?: 0f

            if (zeroRatio > 0.95f) {
                Log.w(TAG, "GARBLED WARNING: >95% samples are zero — likely model failure")
                diagnostics["quality_warning"] = "mostly_silence"
            }
            if (maxAbs < 0.001f) {
                Log.w(TAG, "GARBLED WARNING: max amplitude < 0.001 — nearly silent")
                diagnostics["quality_warning"] = "nearly_silent"
            }
            if (rms > 0.9f) {
                Log.w(TAG, "GARBLED WARNING: RMS > 0.9 — audio is clipping/noise")
                diagnostics["quality_warning"] = "clipping_noise"
            }

            val durationSec = audio.samples.size.toFloat() / audio.sampleRate
            diagnostics["audio_duration_s"] = durationSec

            val wavBytes = encodeWav(audio.samples, audio.sampleRate)
            diagnostics["wav_size_bytes"] = wavBytes.size

            Log.i(TAG, "Synthesized ${wavBytes.size} bytes (${String.format("%.1f", durationSec)}s) in ${elapsedMs}ms for voice=$voice sid=$speakerId")

            Pair(wavBytes, diagnostics)
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed for voice=$voice speed=$speed text=${text.take(80)}", e)
            diagnostics["error"] = e.message ?: "unknown_exception"
            diagnostics["error_type"] = e.javaClass.simpleName
            Pair(null, diagnostics)
        }
    }

    /**
     * Get comprehensive engine diagnostics for the /diagnostics endpoint.
     */
    fun getDiagnostics(): Map<String, Any> {
        val diag = mutableMapOf<String, Any>()
        diag["engine"] = "sherpa-onnx"
        diag["model_dir"] = modelDir.absolutePath
        diag["sample_rate"] = tts.sampleRate()
        diag["num_speakers"] = tts.numSpeakers()
        diag["available_voices"] = KOKORO_VOICES.size
        diag["sorted_voice_ids"] = SORTED_VOICE_IDS

        // Model file details
        val modelFiles = mutableMapOf<String, Any>()
        val filesToCheck = listOf("model.onnx", "tokens.txt", "voices.bin")
        for (fname in filesToCheck) {
            val f = File(modelDir, fname)
            modelFiles[fname] = mapOf(
                "exists" to f.exists(),
                "size_bytes" to f.length(),
                "size_mb" to String.format("%.1f", f.length() / 1_000_000.0),
            )
        }

        // espeak-ng-data directory
        val espeakDir = File(modelDir, "espeak-ng-data")
        if (espeakDir.exists() && espeakDir.isDirectory) {
            val files = espeakDir.listFiles() ?: emptyArray()
            val dirContents = mutableListOf<Map<String, Any>>()
            for (f in files) {
                if (f.isFile) {
                    dirContents.add(mapOf("name" to f.name, "size" to f.length()))
                } else if (f.isDirectory) {
                    val subCount = f.listFiles()?.size ?: 0
                    dirContents.add(mapOf("name" to "${f.name}/", "files" to subCount))
                }
            }
            modelFiles["espeak-ng-data"] = mapOf(
                "exists" to true,
                "file_count" to files.size,
                "contents" to dirContents,
            )
        } else {
            modelFiles["espeak-ng-data"] = mapOf("exists" to false)
        }

        // Check for stray individual .bin voice files (wrong format)
        val strayBins = modelDir.listFiles()
            ?.filter { it.name.endsWith(".bin") && it.name != "voices.bin" }
            ?.map { it.name } ?: emptyList()
        if (strayBins.isNotEmpty()) {
            modelFiles["stray_voice_bins"] = strayBins
        }

        // All files in model directory
        val allFiles = modelDir.listFiles()?.map { "${it.name} (${it.length()})" } ?: emptyList()
        diag["all_model_dir_files"] = allFiles

        diag["model_files"] = modelFiles
        return diag
    }

    fun getSampleRate(): Int = tts.sampleRate()

    fun getNumSpeakers(): Int = tts.numSpeakers()

    fun getAvailableVoices(): Map<String, String> = KOKORO_VOICES

    fun isVoiceAvailable(voiceId: String): Boolean = KOKORO_VOICES.containsKey(voiceId)

    fun release() {
        try {
            tts.release()
            Log.i(TAG, "TTSEngine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing TTSEngine", e)
        }
    }

    fun resolveVoiceSpeakerId(voice: String): Int {
        val idx = SORTED_VOICE_IDS.indexOf(voice)
        return if (idx >= 0) idx else {
            Log.w(TAG, "Unknown voice '$voice', falling back to $DEFAULT_VOICE")
            SORTED_VOICE_IDS.indexOf(DEFAULT_VOICE).coerceAtLeast(0)
        }
    }

    /**
     * Analyze audio samples for quality diagnostics.
     */
    private fun analyzeAudioSamples(samples: FloatArray): Map<String, Any> {
        if (samples.isEmpty()) return mapOf("empty" to true)

        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        var sum = 0.0
        var sumSq = 0.0
        var zeroCount = 0
        var nanCount = 0
        var infCount = 0
        var zeroCrossings = 0
        var prevSign = samples[0] >= 0

        for (i in samples.indices) {
            val s = samples[i]
            if (s.isNaN()) { nanCount++; continue }
            if (s.isInfinite()) { infCount++; continue }
            if (s < min) min = s
            if (s > max) max = s
            sum += s
            sumSq += s * s
            if (abs(s) < 0.0001f) zeroCount++

            val sign = s >= 0
            if (i > 0 && sign != prevSign) zeroCrossings++
            prevSign = sign
        }

        val n = samples.size
        val mean = sum / n
        val rms = sqrt(sumSq / n).toFloat()

        return mapOf(
            "num_samples" to n,
            "min" to min,
            "max" to max,
            "max_abs" to maxOf(abs(min), abs(max)),
            "mean" to String.format("%.6f", mean),
            "rms" to rms,
            "zero_ratio" to (zeroCount.toFloat() / n),
            "nan_count" to nanCount,
            "inf_count" to infCount,
            "zero_crossings" to zeroCrossings,
            "zero_crossing_rate" to (zeroCrossings.toFloat() / n),
            "first_10_samples" to samples.take(10).map { String.format("%.4f", it) },
            "last_10_samples" to samples.takeLast(10).map { String.format("%.4f", it) },
        )
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
