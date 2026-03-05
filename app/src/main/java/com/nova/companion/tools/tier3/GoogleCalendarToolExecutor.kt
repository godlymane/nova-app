package com.nova.companion.tools.tier3

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.*

/**
 * GoogleCalendarToolExecutor — Read and create calendar events via Android CalendarContract.
 *
 * Uses Android's built-in CalendarContract ContentProvider — no OAuth needed.
 * The device calendar syncs with Google Calendar automatically.
 *
 * Capabilities:
 *  - getCalendar(days): list upcoming events (today + N days ahead)
 *  - createEvent(title, when, duration, description): create event via Calendar intent
 */
object GoogleCalendarToolExecutor {

    private const val TAG = "CalendarTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "getCalendar",
            description = "Read upcoming calendar events or create a new event. " +
                "Use action='read' to see scheduled events. Use action='create' to make a new event. " +
                "Examples: 'what's on my calendar today', 'do I have anything this week', " +
                "'schedule a meeting with Rahul tomorrow at 3pm', 'add gym to my calendar Friday 7am'",
            parameters = mapOf(
                "action" to ToolParam(
                    type = "string",
                    description = "'read' to list events, 'create' to add new event",
                    required = false
                ),
                "days" to ToolParam(
                    type = "number",
                    description = "Number of days ahead to look for events (default: 7)",
                    required = false
                ),
                "title" to ToolParam(
                    type = "string",
                    description = "Event title (for create action)",
                    required = false
                ),
                "when" to ToolParam(
                    type = "string",
                    description = "When to schedule — natural language like 'tomorrow 3pm', 'Friday 7am', 'next Monday 10:30am'",
                    required = false
                ),
                "duration_minutes" to ToolParam(
                    type = "number",
                    description = "Duration in minutes (default: 60)",
                    required = false
                ),
                "description" to ToolParam(
                    type = "string",
                    description = "Optional event description or notes",
                    required = false
                ),
                "location" to ToolParam(
                    type = "string",
                    description = "Optional event location",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "read"

        return when {
            action == "create" || params.containsKey("title") -> createEvent(context, params)
            else -> readEvents(context, params)
        }
    }

    // ───────────────────────────── READ ──────────────────────────────

    private fun readEvents(context: Context, params: Map<String, Any>): ToolResult {
        val days = ((params["days"] as? Number)?.toInt() ?: 7).coerceIn(0, 30)

        return try {
            val now = System.currentTimeMillis()
            val endMs = now + (days * 24L * 60L * 60L * 1000L)

            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME
            )

            val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DTSTART} <= ? AND " +
                "${CalendarContract.Events.DELETED} = 0"

            val selectionArgs = arrayOf(now.toString(), endMs.toString())
            val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            if (cursor == null) {
                return ToolResult(false, "Calendar permission not granted — cannot read events")
            }

            val events = mutableListOf<String>()
            val dateFormat = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
            val dateOnlyFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())

            cursor.use {
                while (it.moveToNext() && events.size < 20) {
                    val title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE))
                        ?.trim() ?: "(No title)"
                    val startMs = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                    val endMs2 = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
                    val allDay = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) == 1
                    val location = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION))
                        ?.trim()

                    val timeStr = if (allDay) {
                        dateOnlyFormat.format(Date(startMs)) + " (all day)"
                    } else {
                        val start = dateFormat.format(Date(startMs))
                        val endTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(endMs2))
                        "$start – $endTime"
                    }

                    val entry = buildString {
                        append("• $title — $timeStr")
                        if (!location.isNullOrBlank()) append(" @ $location")
                    }
                    events.add(entry)
                }
            }

            if (events.isEmpty()) {
                val rangeDesc = if (days == 0) "today" else "the next $days days"
                ToolResult(true, "No events scheduled for $rangeDesc.")
            } else {
                val rangeDesc = if (days == 0) "today" else "the next $days days"
                val header = "Upcoming events ($rangeDesc):"
                ToolResult(true, "$header\n${events.joinToString("\n")}")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar permission denied", e)
            ToolResult(false, "Calendar permission not granted. Go to Settings > Apps > Nova > Permissions and enable Calendar.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read calendar", e)
            ToolResult(false, "Failed to read calendar: ${e.message}")
        }
    }

    // ───────────────────────────── CREATE ────────────────────────────

    private fun createEvent(context: Context, params: Map<String, Any>): ToolResult {
        val title = (params["title"] as? String)?.trim()
            ?: return ToolResult(false, "Event title is required")

        val whenStr = (params["when"] as? String)?.trim()
        val durationMins = ((params["duration_minutes"] as? Number)?.toLong() ?: 60L)
        val description = (params["description"] as? String)?.trim()
        val location = (params["location"] as? String)?.trim()

        return try {
            // Parse the "when" string to a timestamp
            val startMs = parseWhenString(whenStr)
            val endMs = startMs + durationMins * 60_000L

            val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                putExtra(CalendarContract.Events.TITLE, title)
                if (!description.isNullOrBlank()) {
                    putExtra(CalendarContract.Events.DESCRIPTION, description)
                }
                if (!location.isNullOrBlank()) {
                    putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val dateStr = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(startMs))
            Log.i(TAG, "Calendar event created: $title at $dateStr")
            ToolResult(true, "Opening calendar to create: '$title' on $dateStr (${durationMins}min). Confirm in the calendar app.")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create event", e)
            // Fallback: just open calendar
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://com.android.calendar/time/")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) } catch (_: Exception) {}
            ToolResult(false, "Failed to create event automatically: ${e.message}. Opening calendar instead.")
        }
    }

    /**
     * Parse natural language time strings to epoch milliseconds.
     * Handles: "tomorrow 3pm", "Friday 7am", "next Monday 10:30am", "today 2pm", etc.
     */
    private fun parseWhenString(whenStr: String?): Long {
        if (whenStr.isNullOrBlank()) {
            // Default: tomorrow 9am
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            return cal.timeInMillis
        }

        val input = whenStr.lowercase().trim()
        val cal = Calendar.getInstance()

        // Parse time component (e.g. "3pm", "10:30am", "14:00")
        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
        val timeMatch = timeRegex.find(input)
        var hour = 9
        var minute = 0

        if (timeMatch != null) {
            hour = timeMatch.groupValues[1].toIntOrNull() ?: 9
            minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val meridiem = timeMatch.groupValues[3]
            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
        }

        // Parse day component
        when {
            "today" in input -> {
                // use today
            }
            "tomorrow" in input -> cal.add(Calendar.DAY_OF_MONTH, 1)
            "monday" in input || "mon" in input -> setToNextWeekday(cal, Calendar.MONDAY, "next" in input)
            "tuesday" in input || "tue" in input -> setToNextWeekday(cal, Calendar.TUESDAY, "next" in input)
            "wednesday" in input || "wed" in input -> setToNextWeekday(cal, Calendar.WEDNESDAY, "next" in input)
            "thursday" in input || "thu" in input -> setToNextWeekday(cal, Calendar.THURSDAY, "next" in input)
            "friday" in input || "fri" in input -> setToNextWeekday(cal, Calendar.FRIDAY, "next" in input)
            "saturday" in input || "sat" in input -> setToNextWeekday(cal, Calendar.SATURDAY, "next" in input)
            "sunday" in input || "sun" in input -> setToNextWeekday(cal, Calendar.SUNDAY, "next" in input)
        }

        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // If the parsed time is in the past (and no explicit day was given), add 1 day
        if (cal.timeInMillis < System.currentTimeMillis() &&
            "today" !in input && "tomorrow" !in input
        ) {
            if (!listOf("monday","tuesday","wednesday","thursday","friday","saturday","sunday",
                    "mon","tue","wed","thu","fri","sat","sun").any { it in input }) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        return cal.timeInMillis
    }

    private fun setToNextWeekday(cal: Calendar, targetDay: Int, forceNext: Boolean) {
        val current = cal.get(Calendar.DAY_OF_WEEK)
        var daysUntil = (targetDay - current + 7) % 7
        if (daysUntil == 0 && forceNext) daysUntil = 7
        if (daysUntil == 0) daysUntil = 7  // "Friday" always means the next Friday
        cal.add(Calendar.DAY_OF_MONTH, daysUntil)
    }
}
