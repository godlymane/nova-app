package com.nova.companion.tools

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object ToolPermissionHelper {

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

    fun hasPermission(activity: Activity, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissions(
        activity: Activity,
        permissions: Array<String>,
        requestCode: Int
    ) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    fun hasBrainPermissions(activity: Activity): Boolean {
        return BRAIN_PERMISSIONS.all { hasPermission(activity, it) }
    }

    fun hasAudioPermissions(activity: Activity): Boolean {
        return AUDIO_PERMISSIONS.all { hasPermission(activity, it) }
    }
}
