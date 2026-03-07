package ai.adverant.prosecreator.tts

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GeminiValidator — Validates TTS audio by transcribing with Gemini and
 * comparing word-by-word against the expected text.
 *
 * Ported from desktop tts-quality-validator.ts.
 *
 * Uses Gemini 3.1 Pro Preview to transcribe audio, then diffs the
 * transcription against expected text to detect garbled output,
 * missing words, and dropped proper nouns.
 */
class GeminiValidator {

    companion object {
        private const val TAG = "GeminiValidator"
        private const val GEMINI_MODEL = "gemini-3.1-pro-preview"
        private const val GOOGLE_API_KEY = "AIzaSyDik9k5KKAlmNf301nR8hsdQ14503htrt0"
        private const val GEMINI_TIMEOUT_S = 45L
        private const val PASS_THRESHOLD = 0.92f // 92% word accuracy = pass
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(GEMINI_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Result of transcription-based validation.
     */
    data class ValidationResult(
        val passed: Boolean,
        val transcription: String,
        val missingWords: List<String>,
        val extraWords: List<String>,
        val wordAccuracy: Float,
        val droppedNames: List<String>,
        val overallClarity: Int,
        val validationFailed: Boolean, // true if Gemini call itself failed
        val errorMessage: String?,
        val validationTimeMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("passed", passed)
            put("transcription", transcription)
            put("missing_words", JSONArray(missingWords))
            put("extra_words", JSONArray(extraWords))
            put("word_accuracy", wordAccuracy.toDouble())
            put("dropped_names", JSONArray(droppedNames))
            put("clarity", overallClarity)
            put("validation_failed", validationFailed)
            if (errorMessage != null) put("error", errorMessage)
            put("validation_time_ms", validationTimeMs)
        }
    }

    /**
     * Validate TTS audio by transcribing with Gemini and comparing
     * against expected text.
     *
     * @param wavBytes WAV audio bytes
     * @param expectedText The text that was sent to TTS
     * @param properNouns Optional list of proper nouns to check specifically
     * @return ValidationResult with detailed comparison
     */
    suspend fun validate(
        wavBytes: ByteArray,
        expectedText: String,
        properNouns: List<String> = emptyList(),
    ): ValidationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val audioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

            Log.i(TAG, "Validating audio: ${wavBytes.size} bytes, expected text: ${expectedText.take(80)}...")

            val prompt = """Transcribe this audio EXACTLY as spoken. Include every single word you hear.
Do not add words that were not spoken. Do not correct grammar or fill in gaps.
If there is silence where a word should be, write [silence].
If a word is unclear or garbled, write [unclear].

The expected text was: "${expectedText.take(500)}"

Respond with ONLY valid JSON (no markdown, no explanation):
{"transcription": "the exact words you heard spoken", "clarity": 8}"""

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "audio/wav")
                                    put("data", audioBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("maxOutputTokens", 2048)
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$GOOGLE_API_KEY"

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(500) ?: "no body"
                Log.w(TAG, "Gemini API error: ${response.code} — $errorBody")
                return@withContext failedResult(
                    "Gemini API error: ${response.code}",
                    elapsed,
                )
            }

            val responseJson = JSONObject(response.body?.string() ?: "{}")
            val responseText = responseJson
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "") ?: ""

            if (responseText.isBlank()) {
                Log.w(TAG, "Gemini returned empty response")
                return@withContext failedResult("Empty Gemini response", elapsed)
            }

            // Parse JSON from response (strip markdown fences if present)
            val cleaned = responseText
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            val parsed = try {
                JSONObject(cleaned)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Gemini response JSON: ${cleaned.take(200)}")
                return@withContext failedResult(
                    "Failed to parse Gemini JSON: ${e.message}",
                    elapsed,
                )
            }

            val transcription = parsed.optString("transcription", "")
            val clarity = parsed.optInt("clarity", -1)

            Log.i(TAG, "Gemini transcription (${elapsed}ms): ${transcription.take(100)}...")
            Log.i(TAG, "Gemini clarity: $clarity")

            // Word-level diff
            val expectedWords = normalizeForComparison(expectedText)
            val transcribedWords = normalizeForComparison(transcription)

            val missingWords = findMissingWords(expectedWords, transcribedWords)
            val extraWords = findMissingWords(transcribedWords, expectedWords)
            val matchedCount = expectedWords.size - missingWords.size
            val wordAccuracy = if (expectedWords.isNotEmpty()) {
                matchedCount.toFloat() / expectedWords.size
            } else 1.0f

            // Check proper nouns specifically
            val droppedNames = properNouns.filter { noun ->
                !transcription.lowercase().contains(noun.lowercase())
            }

            val passed = wordAccuracy >= PASS_THRESHOLD && droppedNames.isEmpty()

            Log.i(TAG, "Validation result: passed=$passed, accuracy=${String.format("%.1f%%", wordAccuracy * 100)}, " +
                    "missing=${missingWords.size}, extra=${extraWords.size}, droppedNames=${droppedNames.size}")

            if (missingWords.isNotEmpty()) {
                Log.w(TAG, "Missing words: ${missingWords.take(20)}")
            }
            if (droppedNames.isNotEmpty()) {
                Log.w(TAG, "Dropped proper nouns: $droppedNames")
            }

            ValidationResult(
                passed = passed,
                transcription = transcription,
                missingWords = missingWords,
                extraWords = extraWords,
                wordAccuracy = wordAccuracy,
                droppedNames = droppedNames,
                overallClarity = clarity,
                validationFailed = false,
                errorMessage = null,
                validationTimeMs = elapsed,
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Validation failed", e)
            failedResult("${e.javaClass.simpleName}: ${e.message}", elapsed)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun failedResult(reason: String, elapsedMs: Long) = ValidationResult(
        passed = false,
        transcription = "",
        missingWords = emptyList(),
        extraWords = emptyList(),
        wordAccuracy = -1f,
        droppedNames = emptyList(),
        overallClarity = -1,
        validationFailed = true,
        errorMessage = reason,
        validationTimeMs = elapsedMs,
    )

    /**
     * Normalize text for word-level comparison:
     * lowercase, strip punctuation, split into words, remove empties.
     */
    private fun normalizeForComparison(text: String): List<String> {
        return text
            .lowercase()
            .replace(Regex("\\[silence]"), "")
            .replace(Regex("\\[unclear]"), "")
            .replace(Regex("[^\\w\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }

    /**
     * Find words in `expected` that are missing from `actual`.
     * Uses frequency-based approach to handle repeated words correctly.
     */
    private fun findMissingWords(expected: List<String>, actual: List<String>): List<String> {
        val actualFreq = mutableMapOf<String, Int>()
        for (w in actual) {
            actualFreq[w] = (actualFreq[w] ?: 0) + 1
        }

        val missing = mutableListOf<String>()
        val usedFreq = mutableMapOf<String, Int>()

        for (w in expected) {
            val used = usedFreq[w] ?: 0
            val available = actualFreq[w] ?: 0
            if (used < available) {
                usedFreq[w] = used + 1
            } else {
                missing.add(w)
            }
        }

        return missing
    }
}
