package com.nova.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nova.companion.accessibility.AccessibilityPermissionHelper
import com.nova.companion.brain.context.ContextEngine
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.notification.NovaNotificationPrefs
import com.nova.companion.overlay.AuraOverlayService
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.ui.navigation.NovaNavigation
import com.nova.companion.ui.theme.NovaTheme
import com.nova.companion.vision.ScreenshotService
import com.nova.companion.data.objectbox.NovaObjectBox
import com.nova.companion.inference.NovaInference
import com.nova.companion.voice.WakeWordService
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "NovaMain"

        /**
         * Set by AgentExecutor when it needs screen capture permission.
         * MainActivity observes this and launches the system consent dialog.
         * Completes with `true` if user approved, `false` if denied.
         */
        @Volatile
        var screenCaptureConsentRequest: CompletableDeferred<Boolean>? = null
    }

    private var showAccessibilityBanner by mutableStateOf(false)

    /**
     * Launcher for MediaProjection screen capture consent.
     * On approval, starts ScreenshotService with the projection token.
     */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val deferred = screenCaptureConsentRequest
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "Screen capture permission granted")
            ScreenshotService.start(this, result.resultCode, result.data!!)
            deferred?.complete(true)
        } else {
            Log.w(TAG, "Screen capture permission denied")
            deferred?.complete(false)
        }
        screenCaptureConsentRequest = null
    }

    /**
     * Single launcher for ALL runtime permissions — avoids the bug where
     * multiple sequential launch() calls cancel each other.
     */
    private val requestAllPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, granted) ->
            Log.d(TAG, "Permission $permission: granted=$granted")
        }
        // After permission results come back, start services that need them
        startServicesIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize core engine singletons
        NovaObjectBox.init(this)
        NovaInference.init(this)

        NovaNotificationPrefs(this).recordAppOpen()
        CloudConfig.logStartupDiagnostics()
        checkAccessibilityService()
        requestOverlayPermission()
        requestBatteryOptimizationExclusion()

        // Request ALL permissions in one batch dialog
        requestAllPermissions()

        // Start WakeWordService only if mic permission already granted
        startServicesIfReady()

        setContent {
            NovaTheme {
                NovaNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NovaNotificationPrefs(this).recordAppOpen()
        checkAccessibilityService()
        // Start overlay if permission granted
        if (Settings.canDrawOverlays(this)) {
            AuraOverlayService.start(this)
        }
        // Re-check: user may have granted permissions via Settings
        startServicesIfReady()
        // Check if AgentExecutor is waiting for screen capture consent
        checkPendingScreenCaptureRequest()
    }

    /**
     * Start WakeWordService + ContextEngine only when required permissions are granted.
     */
    private fun startServicesIfReady() {
        // WakeWordService needs RECORD_AUDIO for foregroundServiceType="microphone"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            WakeWordService.start(this)
        } else {
            Log.w(TAG, "RECORD_AUDIO not granted — WakeWordService deferred")
        }
        // ContextEngine degrades gracefully without permissions
        startContextEngine()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "Requesting SYSTEM_ALERT_WINDOW permission")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            // Start the overlay service
            AuraOverlayService.start(this)
        }
    }

    /**
     * Collect ALL missing permissions and request them in one single dialog.
     * This fixes the bug where sequential launch() calls cancelled each other,
     * requiring 3+ app opens to get all permissions granted.
     */
    private fun requestAllPermissions() {
        val allNeeded = mutableSetOf<String>()

        // Core: microphone for wake word
        allNeeded.add(Manifest.permission.RECORD_AUDIO)

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Tool permissions (SMS, contacts, call log, camera, etc.)
        allNeeded.addAll(ToolPermissionHelper.getMissingPermissions(this))

        // Brain permissions (location, calendar, etc.)
        allNeeded.addAll(ToolPermissionHelper.BRAIN_PERMISSIONS)

        // Filter to only those not yet granted
        val missing = allNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            Log.i(TAG, "Requesting ${missing.size} permissions in single batch: ${missing.joinToString()}")
            requestAllPermissionsLauncher.launch(missing)
        } else {
            Log.d(TAG, "All permissions already granted")
        }
    }

    private fun checkAccessibilityService() {
        showAccessibilityBanner = !AccessibilityPermissionHelper.isAccessibilityServiceEnabled(this)
        if (showAccessibilityBanner) {
            Log.i(TAG, "Accessibility service not enabled — showing banner")
        }
    }

    private fun startContextEngine() {
        try {
            ContextEngine.start(this)
            Log.i(TAG, "ContextEngine started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ContextEngine", e)
        }
    }

    /**
     * Check if AgentExecutor has requested screen capture permission.
     * If so, launch the system MediaProjection consent dialog.
     */
    private fun checkPendingScreenCaptureRequest() {
        if (screenCaptureConsentRequest != null && !ScreenshotService.isRunning()) {
            requestScreenCapture()
        }
    }

    /**
     * Launch the system screen capture consent dialog.
     * Called when the AgentExecutor's vision fallback needs a screenshot
     * but ScreenshotService is not yet running.
     */
    fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    /**
     * Request battery optimization exclusion so Nova's background services
     * (WakeWordService, ContextEngine) are not frozen by aggressive battery managers
     * like OPlus Hans Manager on OnePlus/OPPO devices.
     */
    private fun requestBatteryOptimizationExclusion() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exclusion")
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not request battery optimization exclusion", e)
            }
        } else {
            Log.d(TAG, "Battery optimization already excluded")
        }
    }
}

@Composable
fun AccessibilityPermissionBanner(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    if (!AccessibilityPermissionHelper.isAccessibilityServiceEnabled(context)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Enable Full Control",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Let Nova interact with apps on your behalf — tap buttons, fill forms, send messages automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Enable in Settings")
                }
            }
        }
    }
}
