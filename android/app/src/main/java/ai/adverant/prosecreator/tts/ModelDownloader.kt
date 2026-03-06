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
 * ModelDownloader — Downloads and extracts the Kokoro TTS model.
 *
 * Downloads a pre-packaged zip (~300MB) from our GitHub Release containing:
 *   - model.onnx          — Kokoro 82M ONNX model (330MB uncompressed)
 *   - tokens.txt           — Tokenizer vocabulary
 *   - voices.bin           — All voice embeddings in a single file (5.5MB)
 *   - espeak-ng-data/      — Phonemizer data
 *
 * Progress is reported via callback for UI updates.
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        /**
         * Our GitHub Release URL for the pre-packaged Kokoro model zip.
         * Public, no auth required.
         */
        private const val MODEL_ZIP_URL =
            "https://github.com/adverant/Adverant-Nexus-Downloadable-Applications/releases/download/v1.0.0/kokoro-model-android.zip"

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
        .readTimeout(300, TimeUnit.SECONDS)
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
        val voicesFile = File(dir, "voices.bin")

        val ready = modelFile.exists() &&
                modelFile.length() > 1_000_000 && // model should be >1MB
                tokensFile.exists() &&
                voicesFile.exists()

        if (ready) {
            Log.i(TAG, "Model ready: ${modelFile.length() / 1_000_000}MB model, " +
                    "${voicesFile.length() / 1_000}KB voices")
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
     * Download and extract the Kokoro model zip from GitHub Releases.
     */
    suspend fun downloadModel(listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            val dir = getModelDir()

            // If model already ready, skip
            if (isModelReady()) {
                Log.i(TAG, "Model already downloaded, skipping")
                listener.onComplete()
                return@withContext
            }

            // Clean any partial downloads
            dir.deleteRecursively()
            dir.mkdirs()

            // Step 1: Download zip to temp file
            val tempZip = File(context.cacheDir, "kokoro-model.zip")
            if (tempZip.exists()) tempZip.delete()

            Log.i(TAG, "Downloading model zip from $MODEL_ZIP_URL")
            listener.onProgress(0, -1, "Downloading Kokoro model...")

            val request = Request.Builder()
                .url(MODEL_ZIP_URL)
                .header("User-Agent", "ProseCreatorTTS/1.0 (Android)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} downloading model")
            }

            val body = response.body ?: throw RuntimeException("Empty response")
            val totalBytes = body.contentLength()
            var bytesDownloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        listener.onProgress(bytesDownloaded, totalBytes, "Downloading model...")
                    }
                }
            }

            Log.i(TAG, "Download complete: ${tempZip.length()} bytes")
            listener.onFileComplete("kokoro-model.zip")

            // Step 2: Extract zip
            listener.onProgress(0, -1, "Extracting model files...")
            extractZip(tempZip, dir, listener)

            // Clean up zip
            tempZip.delete()

            // Verify extraction
            if (!isModelReady()) {
                throw RuntimeException("Model extraction failed — required files missing")
            }

            Log.i(TAG, "Model download and extraction complete")
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

    private fun extractZip(zipFile: File, targetDir: File, listener: ProgressListener) {
        var entryCount = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)

                // Security: prevent zip slip
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    Log.w(TAG, "Skipping suspicious zip entry: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(65536)
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    entryCount++
                    if (entryCount % 10 == 0) {
                        listener.onProgress(entryCount.toLong(), -1, "Extracting files...")
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Log.i(TAG, "Extracted $entryCount files to ${targetDir.absolutePath}")
    }
}
