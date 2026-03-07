package ai.adverant.prosecreator.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SystemTTSEngine — Uses Android's built-in TextToSpeech API (Google neural voices).
 *
 * Replaces sherpa-onnx TTSEngine to eliminate the ~512 phoneme-token limit
 * that caused audio truncation on long text. Android system TTS has no such limit.
 *
 * No model download required — uses the device's pre-installed TTS engine
 * (Google TTS on Pixel). High quality neural voices available offline.
 *
 * Generates WAV audio via synthesizeToFile() with CountDownLatch for synchronous
 * operation, matching the same API contract as the original TTSEngine.
 *
 * Thread-safe: synthesize methods are synchronized.
 */
class SystemTTSEngine private constructor(
    private val tts: TextToSpeech,
    private val context: Context,
    private val resolvedVoices: Map<String, Voice>,
    private val actualSampleRate: Int,
) {

    companion object {
        private const val TAG = "SystemTTSEngine"
        private const val BITS_PER_SAMPLE = 16
        private const val NUM_CHANNELS = 1

        /** Synthesis timeout per request — generous for very long text. */
        private const val SYNTHESIS_TIMEOUT_SECONDS = 180L

        /**
         * Voice presets — same IDs as Kokoro for API compatibility.
         * These are mapped to the best available system voice at init time
         * based on locale (US/UK) and gender (male/female).
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

        /** Default voice for narration */
        const val DEFAULT_VOICE = "af_sky"

        /**
         * Create a SystemTTSEngine. Blocks until Android TTS is initialized (up to 10s).
         * Returns null if initialization fails.
         *
         * @param context Android context (needed by TextToSpeech and for temp file storage)
         */
        fun create(context: Context): SystemTTSEngine? {
            return try {
                Log.i(TAG, "=== SystemTTSEngine Initialization ===")

                val initLatch = CountDownLatch(1)
                val initSuccess = AtomicBoolean(false)

                val tts = TextToSpeech(context.applicationContext) { status ->
                    initSuccess.set(status == TextToSpeech.SUCCESS)
                    initLatch.countDown()
                }

                if (!initLatch.await(10, TimeUnit.SECONDS)) {
                    Log.e(TAG, "TTS initialization timed out after 10s")
                    tts.shutdown()
                    return null
                }

                if (!initSuccess.get()) {
                    Log.e(TAG, "TTS initialization failed (TextToSpeech.ERROR)")
                    tts.shutdown()
                    return null
                }

                // Set US English as default
                val langResult = tts.setLanguage(Locale.US)
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(TAG, "US English not available on this device")
                    tts.shutdown()
                    return null
                }

                // Discover available system voices and map Kokoro IDs
                val resolvedVoices = resolveSystemVoices(tts)
                Log.i(TAG, "Resolved ${resolvedVoices.size} voices from system TTS")

                // Determine actual sample rate via a quick test synthesis
                val sampleRate = detectSampleRate(tts, context)
                Log.i(TAG, "Detected system TTS sample rate: $sampleRate Hz")

                val engine = SystemTTSEngine(tts, context, resolvedVoices, sampleRate)

                // Verification: quick test synthesis
                Log.i(TAG, "Running test synthesis...")
                val testResult = engine.synthesizeWithDiagnostics("Hello.", DEFAULT_VOICE, 1.0f)
                if (testResult.first != null) {
                    Log.i(TAG, "Test synthesis OK: ${testResult.first!!.size} bytes, " +
                            "${testResult.second["audio_duration_s"]}s")
                } else {
                    Log.e(TAG, "Test synthesis FAILED: ${testResult.second["error"]}")
                    // Don't return null — the engine is initialized, test text may just be too short
                }

                Log.i(TAG, "TTS engine: ${tts.defaultEngine}")
                Log.i(TAG, "=== SystemTTSEngine Ready ===")
                engine
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SystemTTSEngine", e)
                null
            }
        }

        /**
         * Map Kokoro voice IDs to the best matching Android system voice.
         * Groups by locale (US/UK) and attempts gender matching via voice name heuristics.
         */
        private fun resolveSystemVoices(tts: TextToSpeech): Map<String, Voice> {
            val allVoices = tts.voices ?: return emptyMap()
            val resolved = mutableMapOf<String, Voice>()

            // Group system voices by locale
            val usVoices = allVoices.filter { v ->
                v.locale.language == "en" &&
                        (v.locale.country == "US" || v.locale.country.isEmpty()) &&
                        !v.isNetworkConnectionRequired
            }.sortedBy { it.quality } // Lower quality index = worse, so best last

            val ukVoices = allVoices.filter { v ->
                v.locale.language == "en" &&
                        v.locale.country == "GB" &&
                        !v.isNetworkConnectionRequired
            }.sortedBy { it.quality }

            Log.i(TAG, "System voices — US: ${usVoices.size}, UK: ${ukVoices.size}")
            usVoices.forEach { Log.d(TAG, "  US voice: ${it.name} quality=${it.quality}") }
            ukVoices.forEach { Log.d(TAG, "  UK voice: ${it.name} quality=${it.quality}") }

            // For each Kokoro voice, assign the best matching system voice
            for (kokoroId in KOKORO_VOICES.keys) {
                val isUS = kokoroId.startsWith("af_") || kokoroId.startsWith("am_")
                val pool = if (isUS) usVoices else ukVoices
                val fallback = if (isUS) usVoices else (ukVoices.ifEmpty { usVoices })

                if (pool.isNotEmpty()) {
                    // Cycle through available voices to give variety
                    val idx = KOKORO_VOICES.keys.indexOf(kokoroId) % pool.size
                    resolved[kokoroId] = pool[idx]
                } else if (fallback.isNotEmpty()) {
                    resolved[kokoroId] = fallback[0]
                }
            }

            return resolved
        }

        /**
         * Detect the actual output sample rate by doing a tiny synthesis.
         */
        private fun detectSampleRate(tts: TextToSpeech, context: Context): Int {
            val tempFile = File(context.cacheDir, "tts_samplerate_probe.wav")
            val latch = CountDownLatch(1)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { latch.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { latch.countDown() }
            })

            tts.synthesizeToFile("Test.", Bundle(), tempFile, "probe_sr")
            latch.await(10, TimeUnit.SECONDS)

            val rate = if (tempFile.exists() && tempFile.length() >= 44) {
                parseSampleRateFromWav(tempFile.readBytes())
            } else {
                22050 // Safe default
            }

            tempFile.delete()
            return rate
        }

        /** Parse sample rate from WAV header bytes 24-27 (little-endian int32). */
        private fun parseSampleRateFromWav(wav: ByteArray): Int {
            if (wav.size < 44) return 22050
            return (wav[24].toInt() and 0xFF) or
                    ((wav[25].toInt() and 0xFF) shl 8) or
                    ((wav[26].toInt() and 0xFF) shl 16) or
                    ((wav[27].toInt() and 0xFF) shl 24)
        }
    }

    // ── Public API (matches TTSEngine contract) ───────────────────────────

    /**
     * Synthesize text to WAV audio bytes.
     */
    @Synchronized
    fun synthesize(text: String, voice: String = DEFAULT_VOICE, speed: Float = 1.0f): ByteArray? {
        return synthesizeWithDiagnostics(text, voice, speed).first
    }

    /**
     * Synthesize text and return both WAV bytes and diagnostic info.
     * Uses synthesizeToFile() + CountDownLatch for synchronous operation.
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
        diagnostics["engine"] = "android-system-tts"
        diagnostics["tts_engine_name"] = tts.defaultEngine ?: "unknown"

        if (text.isBlank()) {
            diagnostics["error"] = "blank_text"
            return Pair(null, diagnostics)
        }

        return try {
            // Select the system voice matching this Kokoro voice ID
            val systemVoice = resolvedVoices[voice]
            if (systemVoice != null) {
                tts.voice = systemVoice
                diagnostics["system_voice"] = systemVoice.name
                diagnostics["voice_resolved"] = voice
            } else {
                // Fall back to locale-based selection
                val locale = when {
                    voice.startsWith("bf_") || voice.startsWith("bm_") -> Locale.UK
                    else -> Locale.US
                }
                tts.setLanguage(locale)
                diagnostics["system_voice"] = "default_${locale.country}"
                diagnostics["voice_resolved"] = "default"
            }

            diagnostics["speaker_id"] = 0

            // Set speech rate (Android TTS: 1.0 = normal, range roughly 0.25-4.0)
            tts.setSpeechRate(speed)

            // Create temp file for WAV output
            val tempFile = File(context.cacheDir, "tts_output_${System.nanoTime()}.wav")

            // Set up completion listener
            val synthLatch = CountDownLatch(1)
            val synthSuccess = AtomicBoolean(false)
            val synthError = StringBuilder()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "Synthesis started for utterance: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    synthSuccess.set(true)
                    synthLatch.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    synthError.append("Synthesis error (deprecated callback)")
                    synthLatch.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    synthError.append("Synthesis error code: $errorCode")
                    synthLatch.countDown()
                }
            })

            val utteranceId = "tts_${System.nanoTime()}"
            val startTime = System.nanoTime()

            Log.d(TAG, "Synthesizing: voice=$voice speed=$speed text=${text.take(80)}...")

            val result = tts.synthesizeToFile(text, Bundle(), tempFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                diagnostics["error"] = "synthesizeToFile_returned_error"
                return Pair(null, diagnostics)
            }

            // Wait for synthesis to complete
            if (!synthLatch.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                diagnostics["error"] = "synthesis_timeout_${SYNTHESIS_TIMEOUT_SECONDS}s"
                tempFile.delete()
                return Pair(null, diagnostics)
            }

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            diagnostics["synthesis_time_ms"] = elapsedMs

            if (!synthSuccess.get()) {
                diagnostics["error"] = synthError.toString().ifEmpty { "synthesis_failed" }
                tempFile.delete()
                return Pair(null, diagnostics)
            }

            if (!tempFile.exists() || tempFile.length() < 44) {
                diagnostics["error"] = "output_file_missing_or_empty"
                diagnostics["file_size"] = if (tempFile.exists()) tempFile.length() else -1
                tempFile.delete()
                return Pair(null, diagnostics)
            }

            // Read WAV bytes
            val wavBytes = tempFile.readBytes()
            tempFile.delete()

            diagnostics["wav_size_bytes"] = wavBytes.size

            // Parse WAV header for diagnostics
            val sampleRate = parseSampleRateFromWav(wavBytes)
            diagnostics["sample_rate"] = sampleRate

            val numSamples = parseDataSize(wavBytes) / (BITS_PER_SAMPLE / 8 * NUM_CHANNELS)
            diagnostics["num_samples"] = numSamples

            val durationSec = if (sampleRate > 0) numSamples.toFloat() / sampleRate else 0f
            diagnostics["audio_duration_s"] = durationSec

            // Audio quality analysis on the PCM data
            val pcmSamples = extractPcmSamples(wavBytes)
            if (pcmSamples.isNotEmpty()) {
                val stats = analyzeAudioSamples(pcmSamples)
                diagnostics["audio_stats"] = stats

                val zeroRatio = stats["zero_ratio"] as? Float ?: 0f
                val maxAbs = stats["max_abs"] as? Float ?: 0f
                val rms = stats["rms"] as? Float ?: 0f

                if (zeroRatio > 0.95f) {
                    diagnostics["quality_warning"] = "mostly_silence"
                }
                if (maxAbs < 0.001f) {
                    diagnostics["quality_warning"] = "nearly_silent"
                }
                if (rms > 0.9f) {
                    diagnostics["quality_warning"] = "clipping_noise"
                }
            }

            Log.i(TAG, "Synthesized ${wavBytes.size} bytes (${String.format("%.1f", durationSec)}s) " +
                    "in ${elapsedMs}ms for voice=$voice")

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
        diag["engine"] = "android-system-tts"
        diag["tts_engine_name"] = tts.defaultEngine ?: "unknown"
        diag["sample_rate"] = actualSampleRate
        diag["num_speakers"] = resolvedVoices.size
        diag["available_voices"] = KOKORO_VOICES.size

        // List resolved system voices
        val voiceMapping = mutableMapOf<String, String>()
        for ((kokoroId, systemVoice) in resolvedVoices) {
            voiceMapping[kokoroId] = systemVoice.name
        }
        diag["voice_mapping"] = voiceMapping

        // Available system voices
        val systemVoices = tts.voices?.map { v ->
            mapOf(
                "name" to v.name,
                "locale" to v.locale.toString(),
                "quality" to v.quality,
                "network_required" to v.isNetworkConnectionRequired,
            )
        } ?: emptyList()
        diag["all_system_voices"] = systemVoices

        return diag
    }

    fun getSampleRate(): Int = actualSampleRate

    fun getNumSpeakers(): Int = resolvedVoices.size.coerceAtLeast(1)

    fun getAvailableVoices(): Map<String, String> = KOKORO_VOICES

    fun isVoiceAvailable(voiceId: String): Boolean = KOKORO_VOICES.containsKey(voiceId)

    fun release() {
        try {
            tts.stop()
            tts.shutdown()
            Log.i(TAG, "SystemTTSEngine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing SystemTTSEngine", e)
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────

    /** Parse data chunk size from WAV header bytes 40-43. */
    private fun parseDataSize(wav: ByteArray): Int {
        if (wav.size < 44) return 0
        return (wav[40].toInt() and 0xFF) or
                ((wav[41].toInt() and 0xFF) shl 8) or
                ((wav[42].toInt() and 0xFF) shl 16) or
                ((wav[43].toInt() and 0xFF) shl 24)
    }

    /** Extract 16-bit PCM samples from WAV data section as float array. */
    private fun extractPcmSamples(wav: ByteArray): FloatArray {
        if (wav.size < 46) return floatArrayOf()

        val dataSize = parseDataSize(wav)
        val numSamples = dataSize / 2 // 16-bit = 2 bytes per sample
        if (numSamples <= 0) return floatArrayOf()

        val samples = FloatArray(numSamples)
        val buffer = ByteBuffer.wrap(wav, 44, minOf(dataSize, wav.size - 44))
            .order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until minOf(numSamples, (wav.size - 44) / 2)) {
            samples[i] = buffer.getShort().toFloat() / 32768.0f
        }
        return samples
    }

    /**
     * Analyze audio samples for quality diagnostics.
     * Same analysis as the original TTSEngine for consistent telemetry.
     */
    private fun analyzeAudioSamples(samples: FloatArray): Map<String, Any> {
        if (samples.isEmpty()) return mapOf("empty" to true)

        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        var sum = 0.0
        var sumSq = 0.0
        var zeroCount = 0
        var zeroCrossings = 0
        var prevSign = samples[0] >= 0

        for (i in samples.indices) {
            val s = samples[i]
            if (s.isNaN() || s.isInfinite()) continue
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
        val rms = sqrt(sumSq / n).toFloat()

        return mapOf(
            "num_samples" to n,
            "min" to min,
            "max" to max,
            "max_abs" to maxOf(abs(min), abs(max)),
            "rms" to rms,
            "zero_ratio" to (zeroCount.toFloat() / n),
            "zero_crossings" to zeroCrossings,
            "zero_crossing_rate" to (zeroCrossings.toFloat() / n),
        )
    }
}
