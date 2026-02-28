package com.nova.companion.tools

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SetReminderTool {
    private const val PREFS_NAME = "nova_reminders"
    private const val REMINDERS_KEY = "reminders_data"

    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "set_reminder",
            description = "Sets a reminder that will notify you after a specified delay.",
            parameters = mapOf(
                "message" to ToolParam(
                    type = "string",
                    description = "The reminder message to display",
                    required = true
                ),
                "delay_minutes" to ToolParam(
                    type = "number",
                    description = "How many minutes until the reminder should trigger (minimum 1)",
                    required = true
                )
            ),
            executor = { ctx, params ->
                executeSetReminder(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeSetReminder(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val message = params["message"] as? String
                ?: return ToolResult(false, "Reminder message is required")

            if (message.isBlank()) {
                return ToolResult(false, "Reminder message cannot be empty")
            }

            val delayMinutes = when (params["delay_minutes"]) {
                is Number -> (params["delay_minutes"] as Number).toLong()
                is String -> (params["delay_minutes"] as String).toLongOrNull()
                    ?: return ToolResult(false, "Delay must be a valid number")
                else -> return ToolResult(false, "Delay must be a number")
            }

            if (delayMinutes < 1) {
                return ToolResult(false, "Delay must be at least 1 minute")
            }

            val reminderData = Data.Builder()
                .putString("reminder_message", message)
                .putLong("created_time", System.currentTimeMillis())
                .build()

            val reminderWorkRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(reminderData)
                .addTag("reminder_${System.currentTimeMillis()}")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder_${System.currentTimeMillis()}",
                androidx.work.ExistingWorkPolicy.KEEP,
                reminderWorkRequest
            )

            val timeDisplay = when {
                delayMinutes == 1L -> "1 minute"
                delayMinutes < 60 -> "$delayMinutes minutes"
                delayMinutes == 60L -> "1 hour"
                delayMinutes < 1440 -> {
                    val hours = delayMinutes / 60
                    val mins = delayMinutes % 60
                    if (mins == 0L) {
                        "$hours hour${if (hours > 1) "s" else ""}"
                    } else {
                        "$hours hour${if (hours > 1) "s" else ""} and $mins minute${if (mins > 1) "s" else ""}"
                    }
                }
                else -> {
                    val days = delayMinutes / 1440
                    val remainingMins = delayMinutes % 1440
                    when {
                        remainingMins == 0L -> "$days day${if (days > 1) "s" else ""}"
                        else -> "$days day${if (days > 1) "s" else ""} and ${remainingMins / 60} hour${if (remainingMins / 60 > 1) "s" else ""}"
                    }
                }
            }

            ToolResult(
                true,
                "Reminder set: '$message' in $timeDisplay",
                mapOf("delay_minutes" to delayMinutes)
            )
        } catch (e: Exception) {
            ToolResult(false, "Failed to set reminder: ${e.message}")
        }
    }
}
