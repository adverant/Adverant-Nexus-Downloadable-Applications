package ai.adverant.prosecreator.tts

import android.os.Build
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * TelemetryReporter — Fire-and-forget POST of per-synthesis diagnostics to Nexus server.
 *
 * The Android TTS server calls reportSynthesis() after every /v1/audio/speech request.
 * Data is sent to POST /prosecreator/api/tts/telemetry/device (no JWT required,
 * authenticated by x-device-id header).
 *
 * Non-blocking: uses OkHttp enqueue() so synthesis latency is never affected.
 */
class TelemetryReporter(
    private val nexusUrl: String = DEFAULT_NEXUS_URL,
) {
    companion object {
        private const val TAG = "TelemetryReporter"
        private const val DEFAULT_NEXUS_URL = "https://api.adverant.ai"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val deviceId: String = UUID.randomUUID().toString()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Report a synthesis event. Fire-and-forget — never blocks the caller.
     */
    fun reportSynthesis(
        text: String,
        voice: String,
        model: String = "kokoro",
        durationS: Double,
        processingMs: Long,
        error: String? = null,
    ) {
        try {
            val wordCount = text.trim().split("\\s+".toRegex()).size
            val payload = JSONObject().apply {
                put("device_type", "android_server")
                put("tts_target_type", "mobile")
                put("text_length", text.length)
                put("word_count", wordCount)
                put("model_used", model)
                put("voice_used", voice)
                put("total_duration_s", durationS)
                put("generation_time_ms", processingMs)
                put("error_occurred", error != null)
                if (error != null) put("error_message", error)
                put("tts_server_diagnostics", JSONObject().apply {
                    put("android_model", Build.MODEL)
                    put("android_sdk", Build.VERSION.SDK_INT)
                    put("android_version", Build.VERSION.RELEASE)
                    put("engine", "sherpa-onnx-kokoro")
                    put("app_version", "1.0.1")
                })
            }

            val body = payload.toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$nexusUrl/prosecreator/api/tts/telemetry/device")
                .addHeader("x-device-id", deviceId)
                .addHeader("User-Agent", "ProseCreatorTTS/1.0 (Android)")
                .post(body)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Telemetry send failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            Log.d(TAG, "Reported synthesis: voice=$voice words=$wordCount duration=${String.format("%.1f", durationS)}s")
                        } else {
                            Log.w(TAG, "Telemetry server error: ${it.code}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry build failed: ${e.message}")
        }
    }
}
