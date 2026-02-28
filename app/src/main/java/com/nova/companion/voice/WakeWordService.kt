package com.nova.companion.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineException
import com.nova.companion.MainActivity
import com.nova.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * WakeWordService — persistent foreground service that listens for the "Nova" wake word
 * using Picovoice Porcupine.
 *
 * When the wake word is detected, it broadcasts [ACTION_WAKE_WORD_DETECTED] and
 * signals the singleton [VoiceManager] to begin listening via [VoiceManager.startListening].
 *
 * NOTE: Currently uses the built-in "PORCUPINE" keyword for testing.
 * To use the custom "Nova" keyword, replace [KEYWORD] with
 * BuiltInKeyword.values() isn't available — you must place the
 * custom .ppn keyword file in assets/ and use the keyword path constructor:
 *
 *   PorcupineManager.Builder()
 *       .setAccessKey(PORCUPINE_ACCESS_KEY)
 *       .setKeywordPath("nova_android_<version>.ppn")   // in assets/
 *       .setSensitivity(0.7f)
 *       .build(context, wakeWordCallback)
 *
 * Custom "Nova" keyword file must be generated at: https://console.picovoice.ai/
 *
 * Boot startup: Triggered by [com.nova.companion.notification.BootReceiver] which
 * calls [context.startForegroundService(Intent(context, WakeWordService::class.java))]
 * after BOOT_COMPLETED.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"

        // Notification channel for the foreground service notification
        const val CHANNEL_ID = "nova_wake_word"
        private const val NOTIFICATION_ID = 1001

        // Broadcast action emitted on wake word detection
        const val ACTION_WAKE_WORD_DETECTED = "com.nova.companion.WAKE_WORD_DETECTED"

        // PendingIntent request codes
        private const val PENDING_INTENT_RC = 200

        /**
         * Start the wake word service as a foreground service.
         */
        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the wake word service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    // ── Internal state ────────────────────────────────────────────────

    private var porcupineManager: PorcupineManager? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Singleton VoiceManager reference — populated by the running ViewModel
    // We use a weak companion reference so the service doesn't hold the ViewModel alive
    private var voiceManagerRef: VoiceManager? = null

    // ── Service lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WakeWordService created")
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "WakeWordService starting")
        startForeground(NOTIFICATION_ID, buildNotification())
        initPorcupine()
        return START_STICKY // Restart if killed by system
    }

    override fun onDestroy() {
        Log.i(TAG, "WakeWordService destroyed")
        releasePorcupine()
        abandonAudioFocus()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Porcupine initialization ──────────────────────────────────────

    private fun initPorcupine() {
        // Retrieve your Picovoice access key from BuildConfig or local.properties.
        // Add to local.properties: PICOVOICE_ACCESS_KEY=your_key_here
        // Add to build.gradle.kts: buildConfigField("String", "PICOVOICE_ACCESS_KEY", ...)
        val accessKey = try {
            com.nova.companion.BuildConfig::class.java
                .getField("PICOVOICE_ACCESS_KEY")
                .get(null) as? String ?: ""
        } catch (e: Exception) {
            // If field not yet added to BuildConfig, use empty string — Porcupine will throw
            // a clear error in the callback below. Add PICOVOICE_ACCESS_KEY to local.properties.
            Log.w(TAG, "PICOVOICE_ACCESS_KEY not in BuildConfig — Porcupine won't start", e)
            ""
        }

        if (accessKey.isBlank()) {
            Log.e(TAG, "No Picovoice access key — wake word detection disabled. " +
                    "Add PICOVOICE_ACCESS_KEY to local.properties.")
            stopSelf()
            return
        }

        try {
            // Using PORCUPINE built-in keyword for testing.
            // For production: replace with custom "Nova" keyword (.ppn file in assets/)
            // using PorcupineManager.Builder().setKeywordPath("nova_en_android.ppn")
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.PORCUPINE)
                // TODO: Replace with custom Nova keyword for production:
                // .setKeywordPath("nova_en_android_<version>.ppn")  // place in assets/
                .setSensitivity(0.7f) // 0.0 (fewer false accepts) to 1.0 (fewer misses)
                .build(applicationContext, wakeWordCallback)

            porcupineManager?.start()
            Log.i(TAG, "Porcupine started — listening for wake word")
            updateNotification("Listening for wake word...")

        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine: ${e.message}", e)
            updateNotification("Wake word service unavailable")
            stopSelf()
        }
    }

    // ── Wake word callback ────────────────────────────────────────────

    private val wakeWordCallback = PorcupineManagerCallback { keywordIndex ->
        // Called on Porcupine's audio processing thread — dispatch to main
        Log.i(TAG, "Wake word detected! keyword index=$keywordIndex")
        serviceScope.launch(Dispatchers.Main) {
            onWakeWordDetected()
        }
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "Processing wake word trigger")

        // 1. Request audio focus so we can capture mic uninterrupted
        requestAudioFocus()

        // 2. Broadcast intent so any registered receivers can act
        val broadcastIntent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)

        // 3. If the VoiceManager singleton is available (set by ViewModel),
        //    start listening immediately in-process (avoids broadcast round-trip latency)
        val vm = ActiveVoiceManagerHolder.voiceManager
        if (vm != null) {
            serviceScope.launch(Dispatchers.Main) {
                try {
                    vm.startListening(serviceScope)
                    Log.i(TAG, "VoiceManager.startListening() triggered by wake word")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting listening after wake word", e)
                }
            }
        } else {
            Log.w(TAG, "VoiceManager not available — wake word broadcast sent but in-process trigger skipped")
        }

        updateNotification("Wake word detected — listening...")
    }

    // ── Audio focus ───────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()
            audioFocusRequest = focusReq
            val result = audioManager?.requestAudioFocus(focusReq)
            Log.d(TAG, "Audio focus request result: $result")
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                { focusChange -> Log.d(TAG, "Audio focus changed: $focusChange") },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    // ── Porcupine teardown ────────────────────────────────────────────

    private fun releasePorcupine() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
            Log.i(TAG, "Porcupine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Porcupine", e)
        }
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nova Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nova wake word detection service"
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String = "Waiting for 'Nova'..."): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, PENDING_INTENT_RC, launchIntent, pendingFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nova is listening")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Replace with R.drawable.ic_nova_mic when available
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}

/**
 * Global holder for the active VoiceManager instance.
 *
 * The ViewModel registers its VoiceManager here when it's created so the
 * WakeWordService can trigger voice listening in-process without needing a
 * bound service connection. The reference is nullable — the service falls back
 * to broadcast if no VoiceManager is registered.
 *
 * Usage in NovaViewModel.init():
 *   ActiveVoiceManagerHolder.voiceManager = voiceManager
 * Usage in NovaViewModel.onCleared():
 *   ActiveVoiceManagerHolder.voiceManager = null
 */
object ActiveVoiceManagerHolder {
    @Volatile
    var voiceManager: VoiceManager? = null
}
