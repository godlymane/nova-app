package com.nova.companion.brain.context.collectors

import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.context.TimeOfDay
import java.util.Calendar

object TimeContextCollector {
    fun collect(snapshot: ContextSnapshot): ContextSnapshot {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        val timeOfDay = when (hour) {
            in 5..7 -> TimeOfDay.EARLY_MORNING
            in 8..11 -> TimeOfDay.MORNING
            in 12..16 -> TimeOfDay.AFTERNOON
            in 17..20 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
        return snapshot.copy(
            hour = hour,
            dayOfWeek = dayOfWeek,
            isWeekend = isWeekend,
            timeOfDay = timeOfDay
        )
    }
}
