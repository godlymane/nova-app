package com.nova.companion.tools.tier1

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object CreateCalendarEventToolExecutor {

    private const val TAG = "CalendarEventTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "createCalendarEvent",
            description = "Create a new calendar event with a title, date, and time.",
            parameters = mapOf(
                "title" to ToolParam(type = "string", description = "The title of the calendar event", required = true),
                "date" to ToolParam(type = "string", description = "The date of the event in YYYY-MM-DD format", required = true),
                "start_time" to ToolParam(type = "string", description = "The start time in HH:mm 24-hour format", required = true),
                "end_time" to ToolParam(type = "string", description = "The end time in HH:mm 24-hour format. Defaults to 1 hour after start.", required = false),
                "description" to ToolParam(type = "string", description = "An optional description for the event", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val title = (params["title"] as? String)?.trim() ?: return ToolResult(false, "Event title is required")
            val dateStr = (params["date"] as? String)?.trim() ?: return ToolResult(false, "Event date is required")
            val startTimeStr = (params["start_time"] as? String)?.trim() ?: return ToolResult(false, "Start time is required")
            val endTimeStr = (params["end_time"] as? String)?.trim()
            val description = (params["description"] as? String)?.trim()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

            val date = try { dateFormat.parse(dateStr) ?: throw IllegalArgumentException() } catch (e: Exception) {
                return ToolResult(false, "Invalid date format. Use YYYY-MM-DD (e.g. 2026-03-01)")
            }
            val startTime = try { timeFormat.parse(startTimeStr) ?: throw IllegalArgumentException() } catch (e: Exception) {
                return ToolResult(false, "Invalid start time format. Use HH:mm (e.g. 14:30)")
            }

            val startCal = Calendar.getInstance().apply {
                time = date
                val timeCal = Calendar.getInstance().apply { time = startTime }
                set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val endCal = Calendar.getInstance().apply {
                timeInMillis = startCal.timeInMillis
                if (!endTimeStr.isNullOrBlank()) {
                    try {
                        val endTime = timeFormat.parse(endTimeStr)
                        if (endTime != null) {
                            val endTimeCal = Calendar.getInstance().apply { time = endTime }
                            set(Calendar.HOUR_OF_DAY, endTimeCal.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, endTimeCal.get(Calendar.MINUTE))
                        }
                    } catch (e: Exception) { add(Calendar.HOUR_OF_DAY, 1) }
                } else { add(Calendar.HOUR_OF_DAY, 1) }
            }

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startCal.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endCal.timeInMillis)
                putExtra(CalendarContract.Events.TITLE, title)
                if (!description.isNullOrBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            Log.i(TAG, "Calendar event created: $title on $dateStr at $startTimeStr")
            ToolResult(true, "Created calendar event: $title on $dateStr at $startTimeStr")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create calendar event", e)
            ToolResult(false, "Failed to create calendar event: ${e.message}")
        }
    }
}