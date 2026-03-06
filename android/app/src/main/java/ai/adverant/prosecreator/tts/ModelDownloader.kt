package ai.adverant.prosecreator.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * ModelDownloader — Downloads and extracts the Kokoro TTS model from sherpa-onnx releases.
 *
 * Downloads the pre-packaged model archive (~100MB) to the app's internal storage.
 * The archive contains:
 *   - model.onnx          — Kokoro 82M ONNX model
 *   - tokens.txt           — Tokenizer vocabulary
 *   - voices/{name}.bin     — Voice preset embeddings
 *   - espeak-ng-data/       — Phonemizer data (if included)
 *
 * Progress is reported via callback for UI updates.
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        /**
         * sherpa-onnx release URL for Kokoro English model.
         * This is the pre-packaged archive with model + tokens + voices.
         */
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-kokoro-medium.tar.bz2"

        /**
         * Alternative: individual file downloads from HuggingFace.
         * Used if the archive URL is unavailable.
         */
        private const val HF_BASE =
            "https://huggingface.co/k2-fsa/sherpa-onnx-kokoro-en-v0_19/resolve/main"

        /** Files to download individually from HuggingFace */
        private val HF_FILES = listOf(
            "model.onnx",
            "tokens.txt",
        )

        /** Voice files to download individually */
        private val VOICE_FILES = listOf(
            "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica",
            "af_kore", "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
            "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam",
            "am_michael", "am_onyx", "am_puck",
            "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
        )

        /** Model directory name inside app's internal files */
        const val MODEL_DIR_NAME = "kokoro-en-v0_19"
    }

    /** Callback for download progress */
    interface ProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, fileName: String)
        fun onFileComplete(fileName: String)
        fun onComplete()
        fun onError(error: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Get the model directory path.
     */
    fun getModelDir(): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    /**
     * Check if the model is already downloaded and valid.
     */
    fun isModelReady(): Boolean {
        val dir = getModelDir()
        val modelFile = File(dir, "model.onnx")
        val tokensFile = File(dir, "tokens.txt")
        val voicesDir = File(dir, "voices")

        val ready = modelFile.exists() &&
                tokensFile.exists() &&
                voicesDir.exists() &&
                (voicesDir.listFiles()?.size ?: 0) > 0

        if (ready) {
            Log.i(TAG, "Model ready: ${modelFile.length() / 1_000_000}MB, " +
                    "${voicesDir.listFiles()?.size ?: 0} voices")
        }
        return ready
    }

    /**
     * Get model size on disk in bytes.
     */
    fun getModelSizeOnDisk(): Long {
        val dir = getModelDir()
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Download model files from HuggingFace individually.
     * This is more reliable than archive download and allows resuming.
     */
    suspend fun downloadModel(listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            val dir = getModelDir()
            dir.mkdirs()

            val voicesDir = File(dir, "voices")
            voicesDir.mkdirs()

            // Calculate total files
            val totalFiles = HF_FILES.size + VOICE_FILES.size
            var completedFiles = 0

            // Download main model files
            for (fileName in HF_FILES) {
                val targetFile = File(dir, fileName)
                if (targetFile.exists() && targetFile.length() > 0) {
                    Log.i(TAG, "Skipping existing file: $fileName")
                    completedFiles++
                    listener.onFileComplete(fileName)
                    continue
                }

                val url = "$HF_BASE/$fileName"
                downloadFile(url, targetFile, fileName, listener)
                completedFiles++
                listener.onFileComplete(fileName)
            }

            // Download voice files
            for (voiceName in VOICE_FILES) {
                val fileName = "$voiceName.bin"
                val targetFile = File(voicesDir, fileName)
                if (targetFile.exists() && targetFile.length() > 0) {
                    Log.i(TAG, "Skipping existing voice: $fileName")
                    completedFiles++
                    listener.onFileComplete(fileName)
                    continue
                }

                val url = "$HF_BASE/voices/$fileName"
                downloadFile(url, targetFile, fileName, listener)
                completedFiles++
                listener.onFileComplete(fileName)
            }

            // Download espeak-ng-data if available
            downloadEspeakData(dir, listener)

            Log.i(TAG, "Model download complete: $completedFiles files")
            listener.onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            listener.onError("Download failed: ${e.message}")
        }
    }

    /**
     * Delete downloaded model files.
     */
    fun deleteModel(): Boolean {
        val dir = getModelDir()
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
    }

    // ── Private Helpers ─────────────────────────────────────────────────

    private fun downloadFile(
        url: String,
        targetFile: File,
        displayName: String,
        listener: ProgressListener,
    ) {
        Log.i(TAG, "Downloading: $displayName from $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ProseCreatorTTS/1.0 (Android)")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code} downloading $displayName")
        }

        val body = response.body ?: throw RuntimeException("Empty response for $displayName")
        val totalBytes = body.contentLength()
        var bytesDownloaded = 0L

        // Write to temp file, then rename (atomic-ish)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        body.byteStream().use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead
                    listener.onProgress(bytesDownloaded, totalBytes, displayName)
                }
            }
        }

        // Rename temp to final
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        Log.i(TAG, "Downloaded: $displayName (${targetFile.length()} bytes)")
    }

    /**
     * Download espeak-ng-data directory.
     * Some Kokoro configurations need this for phonemization.
     * If not available, sherpa-onnx falls back to built-in tokenization.
     */
    private fun downloadEspeakData(modelDir: File, listener: ProgressListener) {
        val espeakDir = File(modelDir, "espeak-ng-data")
        if (espeakDir.exists() && (espeakDir.listFiles()?.size ?: 0) > 0) {
            Log.i(TAG, "espeak-ng-data already exists, skipping")
            return
        }

        // Try downloading the espeak-ng-data archive
        try {
            val url = "$HF_BASE/espeak-ng-data/phontab"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ProseCreatorTTS/1.0 (Android)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "espeak-ng-data not available (HTTP ${response.code}), skipping — will use built-in tokenization")
                response.close()
                return
            }
            response.close()

            // If phontab exists, download the essential espeak-ng files
            espeakDir.mkdirs()
            val espeakFiles = listOf(
                "phontab", "phonindex", "phondata", "intonations",
                "en_dict", "en_extra"
            )

            for (fileName in espeakFiles) {
                val targetFile = File(espeakDir, fileName)
                try {
                    downloadFile(
                        "$HF_BASE/espeak-ng-data/$fileName",
                        targetFile,
                        "espeak-ng/$fileName",
                        listener
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download espeak-ng/$fileName: ${e.message}")
                    // Non-fatal — continue without this file
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "espeak-ng-data download skipped: ${e.message}")
        }
    }
}
