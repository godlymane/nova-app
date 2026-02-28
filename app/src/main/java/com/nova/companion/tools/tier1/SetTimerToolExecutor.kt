package com.nova.companion.tools.tier1

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object SetTimerToolExecutor {

    private const val TAG = "SetTimerTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "setTimer",
            description = "Set a countdown timer on the device.",
            parameters = mapOf(
                "duration_seconds" to ToolParam(type = "number", description = "The duration of the timer in seconds", required = true),
                "label" to ToolParam(type = "string", description = "An optional label for the timer", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val durationSeconds = (params["duration_seconds"] as? Number)?.toInt()
                ?: return ToolResult(false, "Duration in seconds is required")
            val label = params["label"] as? String

            if (durationSeconds <= 0) return ToolResult(false, "Duration must be greater than 0")

            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            val formatted = formatDuration(durationSeconds)
            Log.i(TAG, "Timer set for $formatted")
            ToolResult(true, "Timer set for $formatted${if (!label.isNullOrBlank()) " — $label" else ""}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set timer", e)
            ToolResult(false, "Failed to set timer: ${e.message}")
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0) append("${m}m ")
            if (s > 0 || (h == 0 && m == 0)) append("${s}s")
        }.trim()
    }
}