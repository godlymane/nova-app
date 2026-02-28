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
import com.nova.companion.notification.NovaNotificationPrefs
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.ui.navigation.NovaNavigation
import com.nova.companion.ui.theme.NovaTheme
import com.nova.companion.voice.WakeWordService

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "NovaMain"
    }

    private var showAccessibilityBanner by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Permission result: granted=$isGranted")
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, granted) ->
            Log.d(TAG, "Tool permission $permission: granted=$granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        NovaNotificationPrefs(this).recordAppOpen()
        requestMicrophonePermission()
        requestNotificationPermission()
        requestToolPermissions()
        checkAccessibilityService()

        WakeWordService.start(this)

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
    }

    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Requesting RECORD_AUDIO permission")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.d(TAG, "RECORD_AUDIO already granted")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "POST_NOTIFICATIONS already granted")
            }
        }
    }

    private fun requestToolPermissions() {
        val missing = ToolPermissionHelper.getMissingPermissions(this)
        if (missing.isNotEmpty()) {
            Log.i(TAG, "Requesting ${missing.size} tool permissions: $missing")
            requestMultiplePermissionsLauncher.launch(missing.toTypedArray())
        } else {
            Log.d(TAG, "All tool permissions already granted")
        }
    }

    private fun checkAccessibilityService() {
        showAccessibilityBanner = !AccessibilityPermissionHelper.isAccessibilityServiceEnabled(this)
        if (showAccessibilityBanner) {
            Log.i(TAG, "Accessibility service not enabled — showing banner")
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
