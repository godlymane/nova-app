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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * WakeWordService — persistent foreground service that listens for the "Hey Nova" wake word
 * using Picovoice Porcupine with a custom-trained .ppn keyword file.
 *
 * When the wake word is detected, it:
 *   1. Requests audio focus
 *   2. Vibrates the device (100 ms, medium amplitude)
 *   3. Broadcasts [ACTION_WAKE_WORD_DETECTED] so any registered BroadcastReceivers can act
 *   4. Emits a Unit on [wakeWordEvent] SharedFlow so ChatViewModel can observe without overhead
 *   5. Calls [VoiceManager.startListening] in-process via [ActiveVoiceManagerHolder]
 * Custom keyword file: Hey-Nova_en_android_v4_0_0.ppn (must be placed in assets/)
 * Porcupine SDK:       ai.picovoice:porcupine-android:3.0.2
 * Generated at:        https://console.picovoice.ai/
 *
 * Boot startup: triggered by [com.nova.companion.notification.BootReceiver] which calls
 * [context.startForegroundService(Intent(context, WakeWordService::class.java))] after
 * BOOT_COMPLETED.

 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"

        // Custom wake word model file — must be placed in app/src/main/assets/
        private const val KEYWORD_ASSET = "Hey-Nova_en_android_v4_0_0.ppn"

        // Sensitivity tuned to reduce false positives while keeping reliable detection
        // Range: 0.0 (fewer false accepts) → 1.0 (fewer misses). 0.65 is a good balance.
        private const val DEFAULT_SENSITIVITY = 0.65f

        // Notification channel for the foreground service notification
        const val CHANNEL_ID = "nova_wake_word"
        private const val NOTIFICATION_ID = 1001

        // Broadcast action emitted on wake word detection
        const val ACTION_WAKE_WORD_DETECTED = "com.nova.companion.WAKE_WORD_DETECTED"

        // PendingIntent request code
        private const val PENDING_INTENT_RC = 200

        // ── SharedFlow for in-process wake word events ───────────────────
        // ChatViewModel collects this flow instead of registering a BroadcastReceiver,
        // eliminating the IPC round-trip latency.
        private val _wakeWordEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val wakeWordEvent: SharedFlow<Unit> = _wakeWordEvent.asSharedFlow()

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

    // ── Internal state ──────────────────────────────────────────

    private var porcupineManager: PorcupineManager? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Singleton VoiceManager reference — populated by the running ViewModel.
    // Kept here as a local alias of the companion holder for readability.
    private var voiceManagerRef: VoiceManager? = null

    // ── Service lifecycle ──────────────────────────────────────

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
        return START_STICKY // Restart automatically if killed by system

    }

    override fun onDestroy() {
        Log.i(TAG, "WakeWordService destroyed")
        releasePorcupine()
        abandonAudioFocus()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Porcupine initialization ─────────────────────────────────

    private fun initPorcupine() {
        // Retrieve the Picovoice access key from BuildConfig.
        // Add to local.properties:  PICOVOICE_ACCESS_KEY=your_key_here
        // Add to build.gradle.kts:  buildConfigField("String", "PICOVOICE_ACCESS_KEY", ...)

        val accessKey = try {
            com.nova.companion.BuildConfig::class.java
                .getField("PICOVOICE_ACCESS_KEY")
                .get(null) as? String ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "PICOVOICE_ACCESS_KEY not in BuildConfig — Porcupine won't start", e)
            ""
        }

        if (accessKey.isBlank()) {
            Log.e(
                TAG,
                "No Picovoice access key — wake word detection disabled. " +
                    "Add PICOVOICE_ACCESS_KEY to local.properties."
            )
            stopSelf()
            return
        }

        // Allow the user to override sensitivity via SharedPreferences (Settings screen).

        val prefs = applicationContext.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)
        val sensitivity = prefs.getFloat("wake_word_sensitivity", DEFAULT_SENSITIVITY)
            .coerceIn(0.0f, 1.0f)

        try {
            // Custom "Hey Nova" keyword — v4.0.0 ppn file requires Porcupine SDK 3.0.x.
            // PorcupineManager.Builder.setKeywordPath() resolves relative paths from assets/.
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(KEYWORD_ASSET)

                .setSensitivity(sensitivity)
                .build(applicationContext, wakeWordCallback)

            porcupineManager?.start()
            Log.i(TAG, "Porcupine started — listening for 'Hey Nova' (sensitivity=$sensitivity)")
            updateNotification("Listening for 'Hey Nova'...")

        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine: ${e.message}", e)
            updateNotification("Wake word service unavailable")
            stopSelf()
        }
    }

    // ── Wake word callback ──────────────────────────────────────

    private val wakeWordCallback = PorcupineManagerCallback { keywordIndex ->
        // Called on Porcupine's internal audio processing thread — dispatch to main

        Log.i(TAG, "Wake word detected! keyword index=$keywordIndex")
        serviceScope.launch(Dispatchers.Main) {
            onWakeWordDetected()
        }
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "Processing wake word trigger")

        // 1. Request audio focus so mic capture is uninterrupted
        requestAudioFocus()

        // 2. Vibrate the device — tactile confirmation for the user (100 ms)
        vibrateOnWakeWord()

        // 3. Broadcast intent so any registered BroadcastReceivers can act

        val broadcastIntent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)

        // 4. Emit event for ChatViewModel (no BroadcastReceiver overhead)
        _wakeWordEvent.tryEmit(Unit)

        // 5. If VoiceManager singleton is available (set by ViewModel),
        //    start listening immediately in-process to avoid broadcast round-trip latency

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

    // ── Vibration ───────────────────────────────────────────────

    /**
     * Vibrate for 100 ms at DEFAULT_AMPLITUDE to give tactile feedback on wake word detection.
     *
     * Uses [android.os.VibratorManager] on API 31+ (S) and falls back to the legacy
     * [android.os.Vibrator] for older APIs, using [android.os.VibrationEffect] where available.
     */
    private fun vibrateOnWakeWord() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        100,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            100,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    // ── Audio focus ─────────────────────────────────────────────


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

    // ── Porcupine teardown ──────────────────────────────────────


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

    // ── Notification ────────────────────────────────────────────


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

    private fun buildNotification(statusText: String = "Waiting for 'Hey Nova'..."): Notification {
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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