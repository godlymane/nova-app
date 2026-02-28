package com.nova.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.nova.companion.notification.NovaNotificationPrefs
import com.nova.companion.ui.navigation.NovaNavigation
import com.nova.companion.ui.theme.NovaTheme
import com.nova.companion.voice.WakeWordService

/**
 * MainActivity — entry point for the Nova Companion app.
 *
 * On startup:
 *   1. enableEdgeToEdge() — full-bleed immersive layout
 *   2. Records the app open time for inactivity-based notification scheduling
 *   3. Requests RECORD_AUDIO permission (required for voice input)
 *   4. Requests POST_NOTIFICATIONS permission on Android 13+ (required for foreground service notifications)
 *   5. Starts [WakeWordService] as a foreground service so "Hey Nova" detection begins immediately
 *
 * Storage / MANAGE_EXTERNAL_STORAGE permission:
 *   This permission is NOT requested on startup. Requesting it automatically on launch is bad UX
 *   because it navigates away from the app to system settings without the user's intent.
 *   Instead, the permission should be requested lazily when the user taps "Load Model" in the
 *   Settings screen, where the intent is clear.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "NovaMain"
    }

    // Single launcher handles both RECORD_AUDIO and POST_NOTIFICATIONS requests
    // (they are requested sequentially; the same result handler is sufficient
    // because we only need to know if either was denied for diagnostics).
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result is informational — the app degrades gracefully if denied.
        Log.d(TAG, "Permission result: granted=$isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Record app open for inactivity tracking (drives "you haven't opened Nova" nudges)
        NovaNotificationPrefs(this).recordAppOpen()

        // Request microphone permission — required for wake word and voice input
        requestMicrophonePermission()

        // Request notification permission on Android 13+ (POST_NOTIFICATIONS)
        requestNotificationPermission()

        // Start WakeWordService immediately so "Hey Nova" detection is live from app open
        WakeWordService.start(this)

        setContent {
            NovaTheme {
                NovaNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep inactivity timer fresh whenever the user returns to the app
        NovaNotificationPrefs(this).recordAppOpen()
    }

    // ── Permission helpers ──────────────────────────────────────────

    /**
     * Request [Manifest.permission.RECORD_AUDIO] if not already granted.
     * This is required for both wake word detection (Porcupine) and STT (Whisper).
     */
    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting RECORD_AUDIO permission")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.d(TAG, "RECORD_AUDIO already granted")
        }
    }

    /**
     * Request [Manifest.permission.POST_NOTIFICATIONS] on Android 13+ (API 33 / TIRAMISU).
     * Required to show the WakeWordService foreground notification and proactive nudges.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "POST_NOTIFICATIONS already granted")
                }
                else -> {
                    Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    // NOTE: requestStoragePermission() has been intentionally removed from here.
    // MANAGE_EXTERNAL_STORAGE (Android 11+) and READ_EXTERNAL_STORAGE (Android ≤10) are
    // only needed when the user loads a .gguf model file from Downloads.
    // The SettingsScreen should call startActivity(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
    // at the point where the user taps "Load Model" — not here on app startup.
}
