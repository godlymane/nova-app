package com.nova.companion.tools

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object ToolPermissionHelper {

    private const val TAG = "ToolPermissions"
    private const val REQUEST_CODE_TOOL_PERMISSIONS = 2001

    /**
     * Permissions required for Nova Brain (context collection).
     * All are gracefully degraded if denied.
     */
    val BRAIN_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS
        // Note: PACKAGE_USAGE_STATS requires special grant via Settings,
        // not requestable via standard permission flow
    )

    /**
     * Permissions required for voice/audio features.
     */
    val AUDIO_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO
    )

    /**
     * Check if a permission is currently granted.
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request a list of permissions from an Activity.
     * Use this in your Activity/Fragment to trigger the system permission dialog.
     */
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    /**
     * Returns the list of runtime permissions that Nova tools require.
     * Call this during onboarding or first launch.
     */
    fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // BLUETOOTH_CONNECT is only needed on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return permissions.toTypedArray()
    }

    /**
     * Returns list of permissions not yet granted.
     */
    fun getMissingPermissions(context: Context): List<String> {
        return requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }.also { missing ->
            if (missing.isNotEmpty()) {
                Log.i(TAG, "Missing tool permissions: $missing")
            } else {
                Log.d(TAG, "All tool permissions granted")
            }
        }
    }

    /**
     * Request any missing tool permissions from an Activity.
     */
    fun requestMissingPermissions(activity: Activity) {
        val missing = getMissingPermissions(activity)
        if (missing.isNotEmpty()) {
            Log.i(TAG, "Requesting ${missing.size} missing tool permissions")
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                REQUEST_CODE_TOOL_PERMISSIONS
            )
        }
    }

    fun hasBrainPermissions(activity: Activity): Boolean {
        return BRAIN_PERMISSIONS.all { hasPermission(activity, it) }
    }

    fun hasAudioPermissions(activity: Activity): Boolean {
        return AUDIO_PERMISSIONS.all { hasPermission(activity, it) }
    }
}
