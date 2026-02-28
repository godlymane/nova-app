package com.nova.companion.tools.tier1

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object SetAlarmToolExecutor {

    private const val TAG = "SetAlarmTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "setAlarm",
            description = "Set an alarm on the device. Specify the time in 24-hour format.",
            parameters = mapOf(
                "hour" to ToolParam(type = "number", description = "The hour to set the alarm for, in 24-hour format (0-23)", required = true),
                "minute" to ToolParam(type = "number", description = "The minute to set the alarm for (0-59)", required = true),
                "label" to ToolParam(type = "string", description = "An optional label for the alarm", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val hour = (params["hour"] as? Number)?.toInt()
                ?: return ToolResult(false, "Hour is required")
            val minute = (params["minute"] as? Number)?.toInt()
                ?: return ToolResult(false, "Minute is required")
            val label = params["label"] as? String

            if (hour !in 0..23) return ToolResult(false, "Hour must be between 0 and 23")
            if (minute !in 0..59) return ToolResult(false, "Minute must be between 0 and 59")

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            val timeStr = String.format("%02d:%02d", hour, minute)
            Log.i(TAG, "Alarm set for $timeStr")
            ToolResult(true, "Alarm set for $timeStr${if (!label.isNullOrBlank()) " — $label" else ""}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
            ToolResult(false, "Failed to set alarm: ${e.message}")
        }
    }
}