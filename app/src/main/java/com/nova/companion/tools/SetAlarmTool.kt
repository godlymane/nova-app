package com.nova.companion.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

object SetAlarmTool {
    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "set_alarm",
            description = "Sets an alarm or timer. Use 'hour' and 'minute' for alarms, or 'seconds' for timers.",
            parameters = mapOf(
                "hour" to ToolParam(
                    type = "number",
                    description = "Hour (0-23) for alarm, not needed for timers",
                    required = false
                ),
                "minute" to ToolParam(
                    type = "number",
                    description = "Minute (0-59) for alarm, not needed for timers",
                    required = false
                ),
                "label" to ToolParam(
                    type = "string",
                    description = "Optional label/description for the alarm",
                    required = false
                ),
                "seconds" to ToolParam(
                    type = "number",
                    description = "Duration in seconds for a timer instead of setting an alarm",
                    required = false
                )
            ),
            executor = { ctx, params ->
                executeSetAlarm(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeSetAlarm(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val seconds = params["seconds"]
            val hour = params["hour"]
            val minute = params["minute"]
            val label = params["label"] as? String

            if (seconds != null) {
                val timerSeconds = when (seconds) {
                    is Number -> seconds.toInt()
                    is String -> seconds.toIntOrNull() ?: return ToolResult(
                        false,
                        "Timer duration must be a valid number"
                    )
                    else -> return ToolResult(false, "Timer duration must be a number")
                }

                if (timerSeconds <= 0) {
                    return ToolResult(false, "Timer duration must be greater than 0 seconds")
                }

                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(AlarmClock.EXTRA_LENGTH, timerSeconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    if (!label.isNullOrEmpty()) {
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    }
                }

                context.startActivity(intent)
                val displayTime = formatSeconds(timerSeconds)
                ToolResult(true, "Timer set for $displayTime")
            } else if (hour != null && minute != null) {
                val alarmHour = when (hour) {
                    is Number -> hour.toInt()
                    is String -> hour.toIntOrNull() ?: return ToolResult(
                        false,
                        "Hour must be a valid number (0-23)"
                    )
                    else -> return ToolResult(false, "Hour must be a number")
                }

                val alarmMinute = when (minute) {
                    is Number -> minute.toInt()
                    is String -> minute.toIntOrNull() ?: return ToolResult(
                        false,
                        "Minute must be a valid number (0-59)"
                    )
                    else -> return ToolResult(false, "Minute must be a number")
                }

                if (alarmHour !in 0..23) {
                    return ToolResult(false, "Hour must be between 0 and 23")
                }
                if (alarmMinute !in 0..59) {
                    return ToolResult(false, "Minute must be between 0 and 59")
                }

                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(AlarmClock.EXTRA_HOUR, alarmHour)
                    putExtra(AlarmClock.EXTRA_MINUTES, alarmMinute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    if (!label.isNullOrEmpty()) {
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    }
                }

                context.startActivity(intent)
                val displayTime = String.format("%02d:%02d", alarmHour, alarmMinute)
                val labelText = if (!label.isNullOrEmpty()) " - $label" else ""
                ToolResult(true, "Alarm set for $displayTime$labelText")
            } else {
                ToolResult(false, "Either provide 'hour' and 'minute' for an alarm, or 'seconds' for a timer")
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to set alarm: ${e.message}")
        }
    }

    private fun formatSeconds(seconds: Int): String {
        return when {
            seconds < 60 -> "$seconds second${if (seconds != 1) "s" else ""}"
            seconds < 3600 -> {
                val minutes = seconds / 60
                val secs = seconds % 60
                if (secs == 0) {
                    "$minutes minute${if (minutes != 1) "s" else ""}"
                } else {
                    "$minutes minute${if (minutes != 1) "s" else ""} and $secs second${if (secs != 1) "s" else ""}"
                }
            }
            else -> {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                when {
                    minutes == 0 -> "$hours hour${if (hours != 1) "s" else ""}"
                    else -> "$hours hour${if (hours != 1) "s" else ""} and $minutes minute${if (minutes != 1) "s" else ""}"
                }
            }
        }
    }
}
