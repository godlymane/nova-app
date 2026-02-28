package com.nova.companion.brain.context.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.nova.companion.brain.context.CalendarEvent
import com.nova.companion.brain.context.ContextSnapshot

object CalendarCollector {

    fun collect(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return snapshot // No permission — return unchanged
        }

        return try {
            val now = System.currentTimeMillis()
            val endOfDay = now + 24 * 60 * 60 * 1000L

            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.ALL_DAY
            )

            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(now.toString(), endOfDay.toString())
            val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            var nextEvent: CalendarEvent? = null
            var eventCount = 0

            cursor?.use { c ->
                while (c.moveToNext()) {
                    eventCount++
                    if (nextEvent == null) {
                        nextEvent = CalendarEvent(
                            title = c.getString(0) ?: "Unknown",
                            startTime = c.getLong(1),
                            endTime = c.getLong(2),
                            location = c.getString(3),
                            isAllDay = c.getInt(4) == 1
                        )
                    }
                }
            }

            val minutesToNext = nextEvent?.let {
                ((it.startTime - now) / 1000 / 60).toInt()
            }

            snapshot.copy(
                nextEvent = nextEvent,
                minutesToNextEvent = minutesToNext,
                eventsToday = eventCount
            )
        } catch (e: Exception) {
            snapshot
        }
    }
}
