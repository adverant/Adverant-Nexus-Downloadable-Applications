package ai.adverant.prosecreator.tts

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * MainActivity — ProseCreator TTS Companion App
 *
 * Material Design 3 UI showing:
 *   - Server status (running/stopped) with IP address
 *   - Start/Stop toggle
 *   - Model download status and progress
 *   - Voice count and server statistics
 *   - Connection instructions
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // ── Views ───────────────────────────────────────────────────────────

    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var portText: TextView
    private lateinit var serverToggle: MaterialSwitch
    private lateinit var startStopButton: Button

    private lateinit var modelCard: MaterialCardView
    private lateinit var modelStatusText: TextView
    private lateinit var modelSizeText: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadProgressText: TextView
    private lateinit var downloadButton: Button
    private lateinit var deleteModelButton: Button

    private lateinit var statsCard: MaterialCardView
    private lateinit var voiceCountText: TextView
    private lateinit var requestCountText: TextView
    private lateinit var audioGeneratedText: TextView

    private lateinit var instructionsCard: MaterialCardView
    private lateinit var instructionsText: TextView

    // ── State ───────────────────────────────────────────────────────────

    private var service: TTSForegroundService? = null
    private var bound = false
    private var statsTimer: Timer? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TTSForegroundService.LocalBinder).getService()
            bound = true
            service?.onStateChanged = { running ->
                runOnUiThread { updateUI(running) }
            }
            updateUI(service?.isServerRunning == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            updateUI(false)
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        requestPermissions()
        hideModelCard()
        updateUI(false)
    }

    override fun onStart() {
        super.onStart()
        // Bind to foreground service
        Intent(this, TTSForegroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        startStatsTimer()
    }

    override fun onPause() {
        super.onPause()
        stopStatsTimer()
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    // ── View Binding ────────────────────────────────────────────────────

    private fun bindViews() {
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        ipAddressText = findViewById(R.id.ipAddressText)
        portText = findViewById(R.id.portText)
        serverToggle = findViewById(R.id.serverToggle)
        startStopButton = findViewById(R.id.startStopButton)

        modelCard = findViewById(R.id.modelCard)
        modelStatusText = findViewById(R.id.modelStatusText)
        modelSizeText = findViewById(R.id.modelSizeText)
        downloadProgress = findViewById(R.id.downloadProgress)
        downloadProgressText = findViewById(R.id.downloadProgressText)
        downloadButton = findViewById(R.id.downloadButton)
        deleteModelButton = findViewById(R.id.deleteModelButton)

        statsCard = findViewById(R.id.statsCard)
        voiceCountText = findViewById(R.id.voiceCountText)
        requestCountText = findViewById(R.id.requestCountText)
        audioGeneratedText = findViewById(R.id.audioGeneratedText)

        instructionsCard = findViewById(R.id.instructionsCard)
        instructionsText = findViewById(R.id.instructionsText)
    }

    private fun setupListeners() {
        serverToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startServer() else stopServer()
        }

        startStopButton.setOnClickListener {
            if (service?.isServerRunning == true) {
                stopServer()
            } else {
                startServer()
            }
        }
    }

    // ── Permissions ─────────────────────────────────────────────────────

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    // ── Server Control ──────────────────────────────────────────────────

    private fun startServer() {
        // Start the foreground service
        val intent = Intent(this, TTSForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Bind and start server
        lifecycleScope.launch {
            // Wait for service to be bound
            var attempts = 0
            while (service == null && attempts < 20) {
                delay(100)
                attempts++
            }

            val svc = service
            if (svc == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to connect to service", Toast.LENGTH_SHORT).show()
                    serverToggle.isChecked = false
                }
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                svc.startServer()
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    updateUI(true)
                    Toast.makeText(this@MainActivity, "TTS server started", Toast.LENGTH_SHORT).show()
                } else {
                    serverToggle.isChecked = false
                    Toast.makeText(this@MainActivity, "Failed to start TTS engine", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopServer() {
        service?.stopServer()
        updateUI(false)

        // Stop the foreground service
        val intent = Intent(this, TTSForegroundService::class.java).apply {
            action = TTSForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    // ── UI Updates ──────────────────────────────────────────────────────

    /** Hide the model download card — system TTS needs no model download. */
    private fun hideModelCard() {
        modelCard.visibility = View.GONE
    }

    private fun updateUI(running: Boolean) {
        val green = ContextCompat.getColor(this, R.color.status_running)
        val red = ContextCompat.getColor(this, R.color.status_stopped)
        val textColor = ContextCompat.getColor(this, R.color.md_theme_on_surface)

        // Status indicator
        statusIndicator.setBackgroundColor(if (running) green else red)
        statusText.text = if (running) "Server Running" else "Server Stopped"
        statusText.setTextColor(textColor)

        // IP address
        val ip = getWifiIpAddress()
        ipAddressText.text = if (running && ip != null) {
            "http://$ip:${TTSServer.DEFAULT_PORT}"
        } else if (ip != null) {
            "Wi-Fi IP: $ip"
        } else {
            "No Wi-Fi connection"
        }
        portText.text = "Port ${TTSServer.DEFAULT_PORT}"

        // Toggle — always enabled (system TTS needs no model download)
        serverToggle.isChecked = running
        startStopButton.text = if (running) "Stop Server" else "Start Server"
        startStopButton.isEnabled = true
        serverToggle.isEnabled = true

        // Instructions
        if (running && ip != null) {
            instructionsText.text = getString(R.string.instructions_running, ip, TTSServer.DEFAULT_PORT)
        } else {
            instructionsText.text = getString(R.string.instructions_stopped)
        }

        // Update stats
        updateStats()
    }

    private fun updateStats() {
        val server = service?.getServer()
        if (server != null && service?.isServerRunning == true) {
            voiceCountText.text = "${SystemTTSEngine.KOKORO_VOICES.size}"
            requestCountText.text = "${server.requestCount}"
            audioGeneratedText.text = String.format(
                Locale.US,
                "%.0fs",
                server.totalAudioSeconds
            )
            statsCard.visibility = View.VISIBLE
        } else {
            statsCard.visibility = View.GONE
        }
    }

    // ── Stats Timer ─────────────────────────────────────────────────────

    private fun startStatsTimer() {
        stopStatsTimer()
        statsTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread { updateStats() }
                }
            }, 2000, 3000) // Update every 3 seconds
        }
    }

    private fun stopStatsTimer() {
        statsTimer?.cancel()
        statsTimer = null
    }

    // ── Network ─────────────────────────────────────────────────────────

    /**
     * Get the device's WiFi IP address (IPv4).
     * Returns null if not connected to WiFi.
     */
    private fun getWifiIpAddress(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress

            if (ipInt == 0) return null

            String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi IP", e)
            null
        }
    }
}
