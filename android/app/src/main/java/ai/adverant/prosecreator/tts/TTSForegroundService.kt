package ai.adverant.prosecreator.tts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * TTSForegroundService — Keeps the TTS HTTP server alive when the app is backgrounded.
 *
 * Android kills background processes aggressively. This foreground service with
 * a persistent notification ensures the TTS server stays running while the user
 * is writing in ProseCreator on their desktop browser.
 *
 * IMPORTANT: startForeground() is called immediately in onStartCommand() to comply
 * with Android's 10-second foreground service deadline. Heavy initialization
 * (model loading, server start) happens after the notification is posted.
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

    private var ttsEngine: SystemTTSEngine? = null
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

        // CRITICAL: Call startForeground() immediately to avoid
        // ForegroundServiceDidNotStartInTimeException (10-second deadline).
        // Show a "Starting..." notification; update it when server is ready.
        try {
            startForeground(NOTIFICATION_ID, createStartingNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    /**
     * Start the TTS engine and HTTP server.
     * Uses Android's built-in TextToSpeech — no model download needed.
     * @return true if started successfully.
     */
    fun startServer(): Boolean {
        if (isServerRunning) {
            Log.w(TAG, "Server already running")
            return true
        }

        return try {
            // Initialize system TTS engine (uses Android built-in TextToSpeech)
            val engine = SystemTTSEngine.create(this)
            if (engine == null) {
                Log.e(TAG, "Failed to create SystemTTSEngine")
                updateNotification(createErrorNotification("Failed to initialize TTS"))
                return false
            }
            ttsEngine = engine

            // Start HTTP server
            val server = TTSServer(TTSServer.DEFAULT_PORT, engine)
            server.start()
            ttsServer = server

            isServerRunning = true
            Log.i(TAG, "TTS server started on port ${TTSServer.DEFAULT_PORT}")

            // Update notification to show running state
            updateNotification(createRunningNotification())
            onStateChanged?.invoke(true)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            updateNotification(createErrorNotification("Server failed: ${e.message?.take(50)}"))
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

    // ── Notifications ───────────────────────────────────────────────────

    private fun updateNotification(notification: Notification) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun createStartingNotification(): Notification {
        return NotificationCompat.Builder(this, TTSApplication.CHANNEL_ID)
            .setContentTitle("ProseCreator TTS")
            .setContentText("Starting TTS server...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(createOpenIntent())
            .build()
    }

    private fun createRunningNotification(): Notification {
        return NotificationCompat.Builder(this, TTSApplication.CHANNEL_ID)
            .setContentTitle("ProseCreator TTS")
            .setContentText("TTS server running on port ${TTSServer.DEFAULT_PORT}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(createOpenIntent())
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Server",
                createStopIntent()
            )
            .build()
    }

    private fun createErrorNotification(message: String): Notification {
        return NotificationCompat.Builder(this, TTSApplication.CHANNEL_ID)
            .setContentTitle("ProseCreator TTS")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(createOpenIntent())
            .build()
    }

    private fun createOpenIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createStopIntent(): PendingIntent {
        val intent = Intent(this, TTSForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
