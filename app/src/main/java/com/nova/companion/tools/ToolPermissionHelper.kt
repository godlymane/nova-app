package com.nova.companion.tools

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object ToolPermissionHelper {

    private const val TAG = "ToolPermissions"
    private const val REQUEST_CODE_TOOL_PERMISSIONS = 2001

    private val TIER1_PERMISSIONS = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    )

    // Tier 3 tools use intents and deep links — no additional runtime permissions needed.
    // INTERNET is a normal permission (declared in manifest, auto-granted).

    fun getMissingPermissions(context: Context): List<String> {
        return TIER1_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }.also { missing ->
            if (missing.isNotEmpty()) {
                Log.i(TAG, "Missing tool permissions: $missing")
            } else {
                Log.d(TAG, "All tool permissions granted")
            }
        }
    }

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

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
