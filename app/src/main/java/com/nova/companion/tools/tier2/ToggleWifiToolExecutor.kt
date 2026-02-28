package com.nova.companion.tools.tier2

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object ToggleWifiToolExecutor {

    private const val TAG = "ToggleWifiTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "toggleWifi",
            description = "Turn WiFi on or off on the device.",
            parameters = mapOf(
                "enabled" to ToolParam(
                    type = "boolean",
                    description = "Whether to turn WiFi on (true) or off (false)",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val enabled = params["enabled"] as? Boolean
                ?: return ToolResult(false, "The enabled parameter is required (true or false)")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — cannot toggle programmatically, open settings panel
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.i(TAG, "Opened WiFi settings panel (API 29+)")
                ToolResult(true, "Opening WiFi settings — please toggle WiFi ${if (enabled) "on" else "off"}")
            } else {
                // Android 9 and below — toggle directly
                if (!ToolPermissionHelper.hasPermission(context, android.Manifest.permission.CHANGE_WIFI_STATE)) {
                    return ToolResult(false, "WiFi permission not granted. Please enable it in Settings.")
                }

                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enabled

                Log.i(TAG, "WiFi turned ${if (enabled) "on" else "off"}")
                ToolResult(true, "WiFi turned ${if (enabled) "on" else "off"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle WiFi", e)
            ToolResult(false, "Failed to toggle WiFi: ${e.message}")
        }
    }
}
