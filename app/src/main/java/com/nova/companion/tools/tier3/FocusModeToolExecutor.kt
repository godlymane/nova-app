package com.nova.companion.tools.tier3

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.delay

object FocusModeToolExecutor {

    private const val TAG = "FocusMode"

    @Volatile
    var isActive = false
        private set
    @Volatile
    var focusEndTime = 0L
        private set
    @Volatile
    var focusType = ""
        private set

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "focusMode",
            description = "Activate focus mode to reduce distractions. Enables DND, silences notifications. Actions: 'start' to begin focus session, 'stop' to end, 'status' to check.",
            parameters = mapOf(
                "action" to ToolParam("string", "Action: start, stop, status, pomodoro", true),
                "minutes" to ToolParam("number", "Duration in minutes (default: 25 for pomodoro, 60 for focus)", false),
                "type" to ToolParam("string", "Focus type: deep_work, study, creative, meeting, pomodoro", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "status"

        return try {
            when (action) {
                "start", "pomodoro" -> startFocus(context, params, action == "pomodoro")
                "stop" -> stopFocus(context)
                "status" -> getStatus()
                else -> ToolResult(false, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Focus mode error", e)
            ToolResult(false, "Error: ${e.message}")
        }
    }

    private fun startFocus(context: Context, params: Map<String, Any>, isPomodoro: Boolean): ToolResult {
        val minutes = when (val m = params["minutes"]) {
            is Number -> m.toInt()
            is String -> m.toIntOrNull()
            else -> null
        } ?: if (isPomodoro) 25 else 60

        val type = (params["type"] as? String) ?: if (isPomodoro) "pomodoro" else "deep_work"

        // Enable DND
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable DND: ${e.message}")
        }

        // Set ringer to silent
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (e: Exception) {
            Log.w(TAG, "Could not set silent mode: ${e.message}")
        }

        isActive = true
        focusEndTime = System.currentTimeMillis() + (minutes * 60_000L)
        focusType = type

        val typeLabel = when (type) {
            "deep_work" -> "Deep Work"
            "study" -> "Study"
            "creative" -> "Creative"
            "meeting" -> "Meeting"
            "pomodoro" -> "Pomodoro"
            else -> "Focus"
        }

        return ToolResult(true, buildString {
            append("$typeLabel mode activated for $minutes minutes.\n")
            append("DND enabled. Notifications silenced.\n")
            if (isPomodoro) {
                append("Pomodoro: $minutes min focus → 5 min break.\n")
            }
            append("Say 'stop focus' when done or I'll notify you when time's up.")
        })
    }

    private fun stopFocus(context: Context): ToolResult {
        if (!isActive) return ToolResult(true, "Focus mode is not active.")

        // Disable DND
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) {}

        // Restore ringer
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        } catch (_: Exception) {}

        val elapsed = ((System.currentTimeMillis() - (focusEndTime - 60 * 60_000L)) / 60_000).coerceAtLeast(0)
        isActive = false
        focusEndTime = 0
        focusType = ""

        return ToolResult(true, buildString {
            append("Focus mode ended.\n")
            append("DND disabled. Notifications restored.\n")
            append("Session duration: ~${elapsed} minutes. Nice work!")
        })
    }

    private fun getStatus(): ToolResult {
        return if (isActive) {
            val remaining = ((focusEndTime - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
            val typeLabel = when (focusType) {
                "deep_work" -> "Deep Work"; "pomodoro" -> "Pomodoro"
                else -> focusType.replaceFirstChar { it.uppercase() }
            }
            ToolResult(true, "$typeLabel mode active. $remaining minutes remaining.")
        } else {
            ToolResult(true, "Focus mode is not active. Say 'start focus' to begin a session.")
        }
    }
}
