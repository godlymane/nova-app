package com.nova.companion.brain.proactive.triggers

import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.proactive.ProactiveMessage

class CalendarTrigger {

    fun evaluate(snapshot: ContextSnapshot): ProactiveMessage? {
        val minutesToNext = snapshot.minutesToNextEvent ?: return null
        val nextEvent = snapshot.nextEvent ?: return null

        // Morning brief: first thing in the morning if there are events today
        if (snapshot.hour in 7..8 && snapshot.eventsToday > 0) {
            val eventWord = if (snapshot.eventsToday == 1) "event" else "events"
            return ProactiveMessage(
                message = "Good morning! You have ${snapshot.eventsToday} $eventWord today. " +
                    "Your first is ${nextEvent.title} in ${minutesToNext} minutes.",
                priority = ProactiveMessage.Priority.NORMAL,
                triggerType = "calendar_morning_brief"
            )
        }

        // Meeting reminder: 15 minutes before
        if (minutesToNext in 14..16 && !nextEvent.isAllDay) {
            return ProactiveMessage(
                message = "Heads up — you have ${nextEvent.title} in about 15 minutes." +
                    (nextEvent.location?.let { " It's at $it." } ?: ""),
                priority = ProactiveMessage.Priority.HIGH,
                triggerType = "calendar_15min_reminder",
                speakImmediately = true
            )
        }

        return null
    }
}
