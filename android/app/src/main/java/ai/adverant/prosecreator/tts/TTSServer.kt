package ai.adverant.prosecreator.tts

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * TTSServer — NanoHTTPD-based HTTP server implementing the OpenAI-compatible TTS API.
 *
 * Runs on port 8881 and serves audio to the ProseCreator web app on the same WiFi network.
 *
 * Endpoints:
 *   GET  /health           — Health check (matches Mac TTS server contract)
 *   GET  /v1/models        — List loaded models
 *   GET  /v1/audio/voices  — List available Kokoro voices
 *   POST /v1/audio/speech  — Generate speech (returns WAV audio)
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

        return try {
            when {
                method == Method.GET && uri == "/health" -> handleHealth()
                method == Method.GET && uri == "/v1/models" -> handleModels()
                method == Method.GET && uri == "/v1/audio/voices" -> handleVoices()
                method == Method.POST && uri == "/v1/audio/speech" -> handleSpeech(session)
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
                errorJson("Internal error: ${e.message}")
            )
        }
    }

    // ── GET /health ─────────────────────────────────────────────────────

    /**
     * Health check — matches the contract expected by useTTSStatus.ts:
     * { loaded_models: string[], models_available: string[], port: number }
     */
    private fun handleHealth(): Response {
        val json = JSONObject().apply {
            put("status", "ok")
            put("loaded_models", JSONArray().put("kokoro"))
            put("models_available", JSONArray().put("kokoro"))
            put("port", DEFAULT_PORT)
            put("platform", "android")
            put("engine", "sherpa-onnx")
            put("sample_rate", engine.getSampleRate())
        }
        return newCorsResponse(Response.Status.OK, MIME_JSON, json.toString())
    }

    // ── GET /v1/models ──────────────────────────────────────────────────

    /**
     * List loaded models — matches OpenAI API format.
     * { data: [{ id, object, loaded }] }
     */
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

    /**
     * List available voices — grouped by model.
     * { kokoro: { voice_id: "Display Name", ... } }
     *
     * Matches the format consumed by useTTSStatus.ts fetchTargetModelsAndVoices().
     */
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

    /**
     * Generate speech — OpenAI-compatible endpoint.
     *
     * Request body (JSON):
     * {
     *   "input": "Text to synthesize",
     *   "model": "kokoro",         // optional, ignored (only kokoro available)
     *   "voice": "af_sky",         // optional, default af_sky
     *   "speed": 1.0,              // optional, 0.5-2.0
     *   "response_format": "wav"   // optional, only wav supported
     * }
     *
     * Also accepts the ProseCreator format:
     * {
     *   "text": "Text to synthesize",  // alias for "input"
     *   "model": "kokoro",
     *   "voice": "af_sky",
     *   "speed": 1.0
     * }
     *
     * Returns: WAV audio (audio/wav)
     */
    private fun handleSpeech(session: IHTTPSession): Response {
        // Read request body
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val bodyMap = HashMap<String, String>()

        // NanoHTTPD requires parseBody to read POST data
        session.parseBody(bodyMap)

        // Get the raw body — NanoHTTPD puts it in "postData" for JSON content type
        val rawBody = bodyMap["postData"] ?: ""
        if (rawBody.isBlank()) {
            return newCorsResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                errorJson("Missing request body")
            )
        }

        val body = try {
            JSONObject(rawBody)
        } catch (e: Exception) {
            return newCorsResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                errorJson("Invalid JSON: ${e.message}")
            )
        }

        // Extract parameters — support both OpenAI format ("input") and ProseCreator format ("text")
        val text = body.optString("input", "").ifBlank {
            body.optString("text", "")
        }
        if (text.isBlank()) {
            return newCorsResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                errorJson("Missing 'input' or 'text' field")
            )
        }

        val voice = body.optString("voice", TTSEngine.DEFAULT_VOICE)
        val speed = body.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4.0f)

        Log.i(TAG, "Speech request: voice=$voice speed=$speed text=${text.take(80)}...")

        // Generate audio
        val startTime = System.currentTimeMillis()
        val wavBytes = engine.synthesize(text, voice, speed)
        val elapsed = System.currentTimeMillis() - startTime

        if (wavBytes == null) {
            return newCorsResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                errorJson("Speech synthesis failed")
            )
        }

        // Calculate audio duration for stats
        val audioDuration = calculateWavDuration(wavBytes)
        totalAudioSeconds += audioDuration

        Log.i(TAG, "Generated ${wavBytes.size} bytes (${String.format("%.1f", audioDuration)}s) in ${elapsed}ms for voice=$voice")

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
        response.addHeader("X-TTS-Engine", "sherpa-onnx-kokoro")
        response.addHeader("X-Processing-Time-Ms", elapsed.toString())
        return response
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
        response.addHeader("Access-Control-Expose-Headers", "X-TTS-Duration, X-TTS-Voice, X-TTS-Engine, X-Processing-Time-Ms")
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private fun errorJson(message: String): String {
        return JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", message)
                put("type", "server_error")
            })
        }.toString()
    }

    /**
     * Calculate WAV duration from header.
     * WAV header: bytes 24-27 = sample rate (little-endian int32)
     *             bytes 40-43 = data chunk size (little-endian int32)
     * Duration = dataSize / (sampleRate * numChannels * bitsPerSample/8)
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
