package com.nova.companion.brain.proactive.triggers

import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.proactive.ProactiveMessage

class WellnessTrigger {

    fun evaluate(snapshot: ContextSnapshot): ProactiveMessage? {
        val screenTime = snapshot.screenTimeToday
        val hour = snapshot.hour

        // Late night screen time nudge
        if (hour >= 23 && screenTime > 240) { // 23:00+ and 4+ hours of screen time
            return ProactiveMessage(
                message = "You've been on your phone for over ${screenTime / 60} hours today and it's getting late. Want to wind down?",
                priority = ProactiveMessage.Priority.LOW,
                triggerType = "wellness_late_night"
            )
        }

        // Long screen time during day
        if (hour in 10..20 && screenTime > 360) { // 6+ hours of screen time
            return ProactiveMessage(
                message = "You've had ${screenTime / 60} hours of screen time today. Take a break?",
                priority = ProactiveMessage.Priority.LOW,
                triggerType = "wellness_screen_time"
            )
        }

        return null
    }
}
