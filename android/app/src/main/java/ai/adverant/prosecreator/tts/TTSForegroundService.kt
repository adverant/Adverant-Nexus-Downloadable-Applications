package ai.adverant.prosecreator.tts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * TTSForegroundService — Keeps the TTS HTTP server alive when the app is backgrounded.
 *
 * Android kills background processes aggressively. This foreground service with
 * a persistent notification ensures the TTS server stays running while the user
 * is writing in ProseCreator on their desktop browser.
 *
 * Lifecycle:
 *   1. MainActivity binds to this service and calls startServer()
 *   2. Service starts as foreground with a "TTS Server Running" notification
 *   3. TTSEngine loads the Kokoro model, TTSServer starts on port 8881
 *   4. User stops via the notification action or the in-app button
 *   5. Service stops server, releases engine, stops foreground
 */
class TTSForegroundService : Service() {

    companion object {
        private const val TAG = "TTSForegroundService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "ai.adverant.prosecreator.tts.STOP"
    }

    /** Binder for Activity <-> Service communication */
    inner class LocalBinder : Binder() {
        fun getService(): TTSForegroundService = this@TTSForegroundService
    }

    private val binder = LocalBinder()

    private var ttsEngine: TTSEngine? = null
    private var ttsServer: TTSServer? = null

    @Volatile
    var isServerRunning: Boolean = false
        private set

    /** Listener for server state changes */
    var onStateChanged: ((running: Boolean) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    /**
     * Start the TTS engine and HTTP server.
     * @param modelDir Directory containing the Kokoro model files.
     * @return true if started successfully.
     */
    fun startServer(modelDir: File): Boolean {
        if (isServerRunning) {
            Log.w(TAG, "Server already running")
            return true
        }

        return try {
            // Initialize TTS engine
            val engine = TTSEngine.create(modelDir)
            if (engine == null) {
                Log.e(TAG, "Failed to create TTSEngine")
                return false
            }
            ttsEngine = engine

            // Start HTTP server
            val server = TTSServer(TTSServer.DEFAULT_PORT, engine)
            server.start()
            ttsServer = server

            isServerRunning = true
            Log.i(TAG, "TTS server started on port ${TTSServer.DEFAULT_PORT}")

            // Start foreground with notification
            startForeground(NOTIFICATION_ID, createNotification())
            onStateChanged?.invoke(true)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            stopServer()
            false
        }
    }

    /**
     * Stop the TTS server and release resources.
     */
    fun stopServer() {
        try {
            ttsServer?.stop()
            ttsServer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server", e)
        }

        try {
            ttsEngine?.release()
            ttsEngine = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing engine", e)
        }

        isServerRunning = false
        onStateChanged?.invoke(false)
        Log.i(TAG, "TTS server stopped")
    }

    /**
     * Get the current TTSServer instance (for stats access).
     */
    fun getServer(): TTSServer? = ttsServer

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotification(): Notification {
        // Intent to open the app when notification is tapped
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop the server from the notification
        val stopIntent = Intent(this, TTSForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TTSApplication.CHANNEL_ID)
            .setContentTitle("ProseCreator TTS")
            .setContentText("TTS server running on port ${TTSServer.DEFAULT_PORT}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Server",
                stopPending
            )
            .build()
    }
}
