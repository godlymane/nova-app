package com.nova.companion.biohack.hypnosis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * SubliminalStrobeService — Background screen flash overlay for neural entrainment.
 *
 * Creates a full-screen transparent overlay that flashes at the configured frequency.
 * The flash is extremely subtle (2-4% opacity) — below conscious perception threshold
 * but enough for the retino-hypothalamic tract to register the rhythm.
 *
 * Runs as a foreground service so it persists all day while phone is in use.
 * Uses SYSTEM_ALERT_WINDOW to draw over other apps.
 *
 * The user starts this from the Neuro-Programming screen. It runs until explicitly
 * stopped or the notification is dismissed.
 */
class SubliminalStrobeService : Service() {

    companion object {
        private const val TAG = "SubliminalStrobe"
        private const val CHANNEL_ID = "nova_subliminal_strobe"
        private const val NOTIFICATION_ID = 4242

        @Volatile var isRunning = false
            private set

        @Volatile var currentFrequencyHz: Float = 10f
            private set

        @Volatile var currentProtocolName: String = ""
            private set

        /**
         * Start the subliminal strobe with given frequency and protocol color.
         */
        fun start(context: Context, frequencyHz: Float, colorArgb: Int, protocolName: String) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "No SYSTEM_ALERT_WINDOW permission — cannot start strobe")
                return
            }
            val intent = Intent(context, SubliminalStrobeService::class.java).apply {
                putExtra("frequency_hz", frequencyHz)
                putExtra("color_argb", colorArgb)
                putExtra("protocol_name", protocolName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SubliminalStrobeService::class.java))
        }

        /**
         * Update the flash frequency while running (e.g., during phase transitions).
         */
        fun updateFrequency(hz: Float) {
            currentFrequencyHz = hz
        }
    }

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var strobeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var flashColorArgb: Int = 0x08FFFFFF  // default: white at ~3% opacity

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val freqHz = intent?.getFloatExtra("frequency_hz", 10f) ?: 10f
        val colorArgb = intent?.getIntExtra("color_argb", 0x08FFFFFF) ?: 0x08FFFFFF
        val protocolName = intent?.getStringExtra("protocol_name") ?: "Neural Entrainment"

        currentFrequencyHz = freqHz
        currentProtocolName = protocolName

        // Build the flash color: use protocol accent at very low alpha (3-4%)
        // Extract RGB from the provided color, force alpha to ~8/255 (3.1%)
        val r = (colorArgb shr 16) and 0xFF
        val g = (colorArgb shr 8) and 0xFF
        val b = colorArgb and 0xFF
        flashColorArgb = (0x0A shl 24) or (r shl 16) or (g shl 8) or b

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(protocolName, freqHz))

        setupOverlay()
        startStrobeLoop()

        isRunning = true
        Log.i(TAG, "Subliminal strobe started: ${freqHz}Hz, protocol=$protocolName")

        return START_STICKY
    }

    private fun setupOverlay() {
        // Remove existing overlay if any
        removeOverlay()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = View(this).apply {
            setBackgroundColor(0x00000000)  // start fully transparent
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            // Not focusable, not touchable — completely invisible to user interaction
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun startStrobeLoop() {
        strobeJob?.cancel()
        strobeJob = scope.launch {
            while (isActive) {
                val hz = currentFrequencyHz.coerceIn(1f, 30f)
                val halfPeriodMs = (1000f / hz / 2f).toLong().coerceAtLeast(16L)

                // Flash ON — subtle color overlay
                overlayView?.setBackgroundColor(flashColorArgb)

                delay(halfPeriodMs)

                // Flash OFF — fully transparent
                overlayView?.setBackgroundColor(0x00000000)

                delay(halfPeriodMs)
            }
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        strobeJob?.cancel()
        removeOverlay()
        scope.cancel()
        isRunning = false
        currentProtocolName = ""
        Log.i(TAG, "Subliminal strobe stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Subliminal Neural Entrainment",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background screen flash for neural frequency entrainment"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(protocolName: String, freqHz: Float): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Neural Entrainment Active")
            .setContentText("$protocolName — ${freqHz}Hz subliminal strobe running")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
