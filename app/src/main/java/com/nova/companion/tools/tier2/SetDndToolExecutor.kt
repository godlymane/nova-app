package com.nova.companion.tools.tier2

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object SetDndToolExecutor {

    private const val TAG = "SetDndTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "setDnd",
            description = "Enable or disable Do Not Disturb mode on the device.",
            parameters = mapOf(
                "enabled" to ToolParam(
                    type = "boolean",
                    description = "Whether to enable (true) or disable (false) Do Not Disturb",
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

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!notificationManager.isNotificationPolicyAccessGranted) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.i(TAG, "DND policy access not granted, opening settings")
                return ToolResult(false, "You need to grant Nova Do Not Disturb access. Opening settings now.")
            }

            if (enabled) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Log.i(TAG, "Do Not Disturb enabled (priority only)")
                ToolResult(true, "Do Not Disturb turned on (priority only mode)")
            } else {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.i(TAG, "Do Not Disturb disabled")
                ToolResult(true, "Do Not Disturb turned off")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DND", e)
            ToolResult(false, "Failed to set Do Not Disturb: ${e.message}")
        }
    }
}
