package ai.adverant.prosecreator.tts

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * TTSServer — NanoHTTPD-based HTTP server implementing the OpenAI-compatible TTS API.
 *
 * Runs on port 8881 and serves audio to the ProseCreator web app on the same WiFi network.
 *
 * Endpoints:
 *   GET  /health                   — Health check (matches Mac TTS server contract)
 *   GET  /v1/models                — List loaded models
 *   GET  /v1/audio/voices          — List available Kokoro voices
 *   POST /v1/audio/speech          — Generate speech (returns WAV audio)
 *   POST /v1/audio/speech/validate — Generate + validate with Gemini transcription
 *   GET  /diagnostics              — Full engine diagnostics
 *
 * CORS headers are added to all responses so the browser can call this server
 * directly from the ProseCreator web app (different origin).
 */
class TTSServer(
    port: Int = DEFAULT_PORT,
    private val engine: TTSEngine,
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "TTSServer"
        const val DEFAULT_PORT = 8881

        private const val MIME_JSON = "application/json"
        private const val MIME_WAV = "audio/wav"
    }

    private val geminiValidator = GeminiValidator()

    /** Track total requests served (for status display) */
    @Volatile
    var requestCount: Long = 0
        private set

    /** Track total audio seconds generated */
    @Volatile
    var totalAudioSeconds: Double = 0.0
        private set

    override fun serve(session: IHTTPSession): Response {
        requestCount++

        // Handle CORS preflight
        if (session.method == Method.OPTIONS) {
            return newCorsResponse(Response.Status.OK, MIME_JSON, "{}")
        }

        val uri = session.uri.trimEnd('/')
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                method == Method.GET && uri == "/health" -> handleHealth()
                method == Method.GET && uri == "/v1/models" -> handleModels()
                method == Method.GET && uri == "/v1/audio/voices" -> handleVoices()
                method == Method.POST && uri == "/v1/audio/speech/validate" -> handleSpeechValidate(session)
                method == Method.POST && uri == "/v1/audio/speech" -> handleSpeech(session)
                method == Method.GET && uri == "/diagnostics" -> handleDiagnostics()
                else -> newCorsResponse(
                    Response.Status.NOT_FOUND,
                    MIME_JSON,
                    errorJson("Not found: $method $uri")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri", e)
            newCorsResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                errorJson("Internal error: ${e.message}", "server_error",
                    mapOf("exception" to (e.javaClass.simpleName), "stack" to (e.stackTraceToString().take(500))))
            )
        }
    }

    // ── GET /health ─────────────────────────────────────────────────────

    private fun handleHealth(): Response {
        val json = JSONObject().apply {
            put("status", "ok")
            put("loaded_models", JSONArray().put("kokoro"))
            put("models_available", JSONArray().put("kokoro"))
            put("port", DEFAULT_PORT)
            put("platform", "android")
            put("engine", "sherpa-onnx")
            put("sample_rate", engine.getSampleRate())
            put("num_speakers", engine.getNumSpeakers())
            put("total_requests", requestCount)
            put("total_audio_seconds", totalAudioSeconds)
        }
        return newCorsResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    // ── GET /v1/models ──────────────────────────────────────────────────

    private fun handleModels(): Response {
        val model = JSONObject().apply {
            put("id", "kokoro")
            put("object", "model")
            put("loaded", true)
            put("owned_by", "k2-fsa")
        }
        val json = JSONObject().apply {
            put("object", "list")
            put("data", JSONArray().put(model))
        }
        return newCorsResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    // ── GET /v1/audio/voices ────────────────────────────────────────────

    private fun handleVoices(): Response {
        val voices = engine.getAvailableVoices()
        val kokoroVoices = JSONObject()
        for ((id, name) in voices) {
            kokoroVoices.put(id, name)
        }

        val json = JSONObject().apply {
            put("kokoro", kokoroVoices)
        }
        return newCorsResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    // ── POST /v1/audio/speech ───────────────────────────────────────────

    private fun handleSpeech(session: IHTTPSession): Response {
        val body = parseJsonBody(session) ?: return newCorsResponse(
            Response.Status.BAD_REQUEST, MIME_JSON, errorJson("Missing or invalid JSON body")
        )

        val text = body.optString("input", "").ifBlank { body.optString("text", "") }
        if (text.isBlank()) {
            return newCorsResponse(
                Response.Status.BAD_REQUEST, MIME_JSON, errorJson("Missing 'input' or 'text' field")
            )
        }

        val voice = body.optString("voice", TTSEngine.DEFAULT_VOICE)
        val speed = body.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4.0f)
        val shouldValidate = body.optBoolean("validate", false)

        Log.i(TAG, "Speech request: voice=$voice speed=$speed validate=$shouldValidate text=${text.take(80)}...")

        // Generate audio with diagnostics
        val startTime = System.currentTimeMillis()
        val (wavBytes, synthDiag) = engine.synthesizeWithDiagnostics(text, voice, speed)
        val synthElapsed = System.currentTimeMillis() - startTime

        if (wavBytes == null) {
            val diagJson = JSONObject(synthDiag.mapValues { it.value.toString() })
            return newCorsResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                errorJson("Speech synthesis failed", "synthesis_error", mapOf(
                    "diagnostics" to diagJson.toString(),
                    "voice" to voice,
                    "speaker_id" to (synthDiag["speaker_id"]?.toString() ?: "unknown"),
                ))
            )
        }

        // Calculate audio duration for stats
        val audioDuration = calculateWavDuration(wavBytes)
        totalAudioSeconds += audioDuration

        Log.i(TAG, "Generated ${wavBytes.size} bytes (${String.format("%.1f", audioDuration)}s) in ${synthElapsed}ms for voice=$voice")

        // Optional Gemini validation
        var validationJson: String? = null
        if (shouldValidate) {
            try {
                val validationResult = runBlocking {
                    geminiValidator.validate(wavBytes, text)
                }
                validationJson = validationResult.toJson().toString()
                Log.i(TAG, "Validation: passed=${validationResult.passed}, accuracy=${String.format("%.1f%%", validationResult.wordAccuracy * 100)}")
            } catch (e: Exception) {
                Log.w(TAG, "Validation failed: ${e.message}")
                validationJson = JSONObject().apply {
                    put("validation_failed", true)
                    put("error", e.message)
                }.toString()
            }
        }

        // Return WAV audio
        val response = newFixedLengthResponse(
            Response.Status.OK,
            MIME_WAV,
            ByteArrayInputStream(wavBytes),
            wavBytes.size.toLong()
        )
        addCorsHeaders(response)
        response.addHeader("X-TTS-Duration", String.format("%.3f", audioDuration))
        response.addHeader("X-TTS-Voice", voice)
        response.addHeader("X-TTS-Speaker-Id", (synthDiag["speaker_id"]?.toString() ?: "unknown"))
        response.addHeader("X-TTS-Engine", "sherpa-onnx-kokoro")
        response.addHeader("X-Processing-Time-Ms", synthElapsed.toString())

        // Add diagnostics header
        val diagSummary = JSONObject().apply {
            put("synthesis_time_ms", synthElapsed)
            put("audio_duration_s", audioDuration)
            put("wav_size_bytes", wavBytes.size)
            put("speaker_id", synthDiag["speaker_id"] ?: -1)
            put("voice_resolved", synthDiag["voice_resolved"] ?: "unknown")
            val qualityWarning = synthDiag["quality_warning"]
            if (qualityWarning != null) put("quality_warning", qualityWarning)
        }
        response.addHeader("X-TTS-Diagnostics", diagSummary.toString())

        if (validationJson != null) {
            response.addHeader("X-TTS-Validation", validationJson)
        }

        return response
    }

    // ── POST /v1/audio/speech/validate ───────────────────────────────────

    /**
     * Generate speech AND validate with Gemini transcription.
     * Returns JSON with base64 audio, validation result, and diagnostics.
     */
    private fun handleSpeechValidate(session: IHTTPSession): Response {
        val body = parseJsonBody(session) ?: return newCorsResponse(
            Response.Status.BAD_REQUEST, MIME_JSON, errorJson("Missing or invalid JSON body")
        )

        val text = body.optString("input", "").ifBlank { body.optString("text", "") }
        if (text.isBlank()) {
            return newCorsResponse(
                Response.Status.BAD_REQUEST, MIME_JSON, errorJson("Missing 'input' or 'text' field")
            )
        }

        val voice = body.optString("voice", TTSEngine.DEFAULT_VOICE)
        val speed = body.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4.0f)

        // Extract proper nouns from request if provided
        val properNouns = mutableListOf<String>()
        val nounsArray = body.optJSONArray("proper_nouns")
        if (nounsArray != null) {
            for (i in 0 until nounsArray.length()) {
                properNouns.add(nounsArray.getString(i))
            }
        }

        Log.i(TAG, "Speech+Validate request: voice=$voice speed=$speed text=${text.take(80)}...")

        // Step 1: Generate audio with diagnostics
        val (wavBytes, synthDiag) = engine.synthesizeWithDiagnostics(text, voice, speed)

        if (wavBytes == null) {
            val responseJson = JSONObject().apply {
                put("error", "synthesis_failed")
                put("diagnostics", mapToJson(synthDiag))
            }
            return newCorsResponse(Response.Status.INTERNAL_ERROR, MIME_JSON, responseJson.toString())
        }

        val audioDuration = calculateWavDuration(wavBytes)
        totalAudioSeconds += audioDuration

        // Step 2: Validate with Gemini
        val validationResult = try {
            runBlocking {
                geminiValidator.validate(wavBytes, text, properNouns)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini validation failed", e)
            GeminiValidator.ValidationResult(
                passed = false,
                transcription = "",
                missingWords = emptyList(),
                extraWords = emptyList(),
                wordAccuracy = -1f,
                droppedNames = emptyList(),
                overallClarity = -1,
                validationFailed = true,
                errorMessage = "${e.javaClass.simpleName}: ${e.message}",
                validationTimeMs = 0,
            )
        }

        // Step 3: Build comprehensive response
        val audioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val responseJson = JSONObject().apply {
            put("audio_base64", audioBase64)
            put("audio_mime_type", "audio/wav")
            put("validation", validationResult.toJson())
            put("diagnostics", JSONObject().apply {
                put("engine", "sherpa-onnx-kokoro")
                put("model_loaded", true)
                put("speaker_id", synthDiag["speaker_id"] ?: -1)
                put("voice_requested", voice)
                put("voice_resolved", synthDiag["voice_resolved"] ?: "unknown")
                put("sample_rate", synthDiag["sample_rate"] ?: TTSEngine.SAMPLE_RATE)
                put("audio_duration_s", audioDuration)
                put("synthesis_time_ms", synthDiag["synthesis_time_ms"] ?: -1)
                put("validation_time_ms", validationResult.validationTimeMs)
                put("audio_size_bytes", wavBytes.size)
                put("num_samples", synthDiag["num_samples"] ?: 0)

                // Audio quality stats
                val audioStats = synthDiag["audio_stats"]
                if (audioStats is Map<*, *>) {
                    put("audio_stats", mapToJson(audioStats))
                }

                val qualityWarning = synthDiag["quality_warning"]
                if (qualityWarning != null) put("quality_warning", qualityWarning)
            })
        }

        Log.i(TAG, "Speech+Validate complete: passed=${validationResult.passed}, " +
                "accuracy=${String.format("%.1f%%", validationResult.wordAccuracy * 100)}, " +
                "audio=${wavBytes.size} bytes")

        return newCorsResponse(Response.Status.OK, MIME_JSON, responseJson.toString())
    }

    // ── GET /diagnostics ─────────────────────────────────────────────────

    /**
     * Returns comprehensive engine diagnostics including model file status,
     * engine configuration, and a test synthesis result.
     */
    private fun handleDiagnostics(): Response {
        val engineDiag = engine.getDiagnostics()

        // Run test synthesis
        val testStartTime = System.currentTimeMillis()
        val (testWav, testDiag) = engine.synthesizeWithDiagnostics(
            "Hello world, this is a test.", TTSEngine.DEFAULT_VOICE, 1.0f
        )
        val testElapsed = System.currentTimeMillis() - testStartTime

        val json = JSONObject().apply {
            // Engine info
            for ((k, v) in engineDiag) {
                when (v) {
                    is Map<*, *> -> put(k, mapToJson(v))
                    is List<*> -> put(k, JSONArray(v))
                    else -> put(k, v)
                }
            }

            // Server stats
            put("total_requests", requestCount)
            put("total_audio_seconds", totalAudioSeconds)

            // Test synthesis result
            put("test_synthesis", JSONObject().apply {
                put("text", "Hello world, this is a test.")
                put("voice", TTSEngine.DEFAULT_VOICE)
                put("success", testWav != null)
                put("time_ms", testElapsed)
                if (testWav != null) {
                    put("audio_bytes", testWav.size)
                    put("duration_s", calculateWavDuration(testWav))
                }
                // Include audio stats from test
                val testAudioStats = testDiag["audio_stats"]
                if (testAudioStats is Map<*, *>) {
                    put("audio_stats", mapToJson(testAudioStats))
                }
                val qualityWarning = testDiag["quality_warning"]
                if (qualityWarning != null) put("quality_warning", qualityWarning)
                val error = testDiag["error"]
                if (error != null) put("error", error)
            })
        }

        return newCorsResponse(Response.Status.OK, MIME_JSON, json.toString(2))
    }

    // ── CORS Helpers ────────────────────────────────────────────────────

    private fun newCorsResponse(status: Response.Status, mimeType: String, body: String): Response {
        val response = newFixedLengthResponse(status, mimeType, body)
        addCorsHeaders(response)
        return response
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept")
        response.addHeader("Access-Control-Expose-Headers",
            "X-TTS-Duration, X-TTS-Voice, X-TTS-Speaker-Id, X-TTS-Engine, " +
            "X-Processing-Time-Ms, X-TTS-Diagnostics, X-TTS-Validation, X-TTS-Error-Code")
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private fun parseJsonBody(session: IHTTPSession): JSONObject? {
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val rawBody = bodyMap["postData"] ?: ""
            if (rawBody.isBlank()) null else JSONObject(rawBody)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON body: ${e.message}")
            null
        }
    }

    private fun errorJson(
        message: String,
        type: String = "server_error",
        extra: Map<String, Any>? = null,
    ): String {
        return JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", message)
                put("type", type)
                if (extra != null) {
                    for ((k, v) in extra) put(k, v)
                }
            })
        }.toString()
    }

    private fun mapToJson(map: Map<*, *>): JSONObject {
        val json = JSONObject()
        for ((k, v) in map) {
            val key = k.toString()
            when (v) {
                is Map<*, *> -> json.put(key, mapToJson(v))
                is List<*> -> json.put(key, JSONArray(v))
                is Number -> json.put(key, v)
                is Boolean -> json.put(key, v)
                else -> json.put(key, v.toString())
            }
        }
        return json
    }

    /**
     * Calculate WAV duration from header.
     */
    private fun calculateWavDuration(wav: ByteArray): Double {
        if (wav.size < 44) return 0.0
        val sampleRate = (wav[24].toInt() and 0xFF) or
                ((wav[25].toInt() and 0xFF) shl 8) or
                ((wav[26].toInt() and 0xFF) shl 16) or
                ((wav[27].toInt() and 0xFF) shl 24)
        val dataSize = (wav[40].toInt() and 0xFF) or
                ((wav[41].toInt() and 0xFF) shl 8) or
                ((wav[42].toInt() and 0xFF) shl 16) or
                ((wav[43].toInt() and 0xFF) shl 24)
        if (sampleRate <= 0) return 0.0
        return dataSize.toDouble() / (sampleRate * 1 * 2) // mono, 16-bit
    }
}
