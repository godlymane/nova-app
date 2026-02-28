package com.nova.companion.tools.tier2

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.nova.companion.services.NovaNotificationListener
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object ReadNotificationsToolExecutor {

    private const val TAG = "ReadNotificationsTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "readNotifications",
            description = "Read recent unread notifications. Can filter by app name.",
            parameters = mapOf(
                "app_name" to ToolParam(
                    type = "string",
                    description = "Filter notifications by app name",
                    required = false
                ),
                "count" to ToolParam(
                    type = "number",
                    description = "Number of notifications to read, default 5",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val appNameFilter = (params["app_name"] as? String)?.trim()
            val count = (params["count"] as? Number)?.toInt() ?: 5

            // Check if notification listener is enabled
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""

            if (!enabledListeners.contains(context.packageName)) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.i(TAG, "Notification listener not enabled, opening settings")
                return ToolResult(false, "You need to grant Nova notification access. Opening settings now.")
            }

            // Get cached notifications from the listener service
            var notifications = NovaNotificationListener.activeNotifications.value

            // Filter by app name if provided
            if (!appNameFilter.isNullOrBlank()) {
                val filter = appNameFilter.lowercase()
                notifications = notifications.filter { it.appName.lowercase().contains(filter) }
            }

            // Sort by timestamp (newest first) and take requested count
            notifications = notifications
                .sortedByDescending { it.timestamp }
                .take(count)

            if (notifications.isEmpty()) {
                val filterMsg = if (!appNameFilter.isNullOrBlank()) " from $appNameFilter" else ""
                return ToolResult(true, "No notifications$filterMsg right now")
            }

            val formatted = notifications.joinToString("\n") { notif ->
                val title = notif.title ?: "No title"
                val text = notif.text ?: "No content"
                "${notif.appName}: $title — $text"
            }

            val filterMsg = if (!appNameFilter.isNullOrBlank()) " from $appNameFilter" else ""
            val message = "You have ${notifications.size} notification${if (notifications.size != 1) "s" else ""}$filterMsg:\n$formatted"

            Log.i(TAG, "Returned ${notifications.size} notifications")
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read notifications", e)
            ToolResult(false, "Failed to read notifications: ${e.message}")
        }
    }
}
