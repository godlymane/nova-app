package com.nova.companion.brain.context

/**
 * Immutable snapshot of current device/user context.
 * Collected every 5 minutes by ContextEngine service.
 */
data class ContextSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    
    // Time context
    val hour: Int = 0,
    val dayOfWeek: Int = 0, // Calendar.SUNDAY=1 ... Calendar.SATURDAY=7
    val isWeekend: Boolean = false,
    val timeOfDay: TimeOfDay = TimeOfDay.UNKNOWN,
    
    // Location context (coarse)
    val isHome: Boolean? = null,       // null = unknown/no permission
    val isWork: Boolean? = null,
    val isMoving: Boolean? = null,
    val locationLabel: String? = null,  // "home", "work", "commuting", etc.
    
    // Calendar context
    val nextEvent: CalendarEvent? = null,
    val minutesToNextEvent: Int? = null,
    val eventsToday: Int = 0,
    
    // Communication context
    val missedCalls: Int = 0,
    val unreadSmsCount: Int = 0,
    val lastContactedPerson: String? = null,
    
    // Device state
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val isHeadphonesConnected: Boolean = false,
    val screenOnDuration: Long = 0L, // minutes
    
    // App usage
    val currentApp: String? = null,     // foreground app package
    val screenTimeToday: Long = 0L,     // minutes
    
    // Weather (if available)
    val weatherDescription: String? = null,
    val temperatureCelsius: Double? = null
)

enum class TimeOfDay {
    EARLY_MORNING,  // 5-8
    MORNING,        // 8-12
    AFTERNOON,      // 12-17
    EVENING,        // 17-21
    NIGHT,          // 21-5
    UNKNOWN
}

data class CalendarEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val location: String? = null,
    val isAllDay: Boolean = false
)

/**
 * Converts a ContextSnapshot into a compact, LLM-friendly string
 * for injection into system prompts. Skips null/empty fields.
 * Typically 50-150 tokens.
 */
fun ContextSnapshot.toPromptString(): String {
    val parts = mutableListOf<String>()

    // Time
    val timeLabel = when (timeOfDay) {
        TimeOfDay.EARLY_MORNING -> "early morning"
        TimeOfDay.MORNING -> "morning"
        TimeOfDay.AFTERNOON -> "afternoon"
        TimeOfDay.EVENING -> "evening"
        TimeOfDay.NIGHT -> "late night"
        TimeOfDay.UNKNOWN -> "${hour}:00"
    }
    val dayLabel = when (dayOfWeek) {
        1 -> "Sunday"; 2 -> "Monday"; 3 -> "Tuesday"; 4 -> "Wednesday"
        5 -> "Thursday"; 6 -> "Friday"; 7 -> "Saturday"
        else -> ""
    }
    parts.add("Time: $dayLabel $timeLabel")

    // Location
    locationLabel?.let { parts.add("Location: $it") }
    if (isMoving == true) parts.add("Moving/commuting")

    // Battery
    if (batteryLevel >= 0) {
        val chargingStr = if (isCharging) " charging" else ""
        parts.add("Battery: ${batteryLevel}%$chargingStr")
    }

    // Calendar
    nextEvent?.let { event ->
        val minsAway = minutesToNextEvent
        val timeStr = when {
            minsAway == null -> ""
            minsAway <= 5 -> " (starting now)"
            minsAway <= 60 -> " (in ${minsAway}min)"
            else -> " (in ${minsAway / 60}h)"
        }
        parts.add("Next: ${event.title}$timeStr")
    }
    if (eventsToday > 0 && nextEvent == null) {
        parts.add("$eventsToday events today (all done)")
    }

    // Communication
    if (missedCalls > 0) parts.add("$missedCalls missed calls")
    if (unreadSmsCount > 0) parts.add("$unreadSmsCount unread messages")

    // Screen time
    if (screenTimeToday > 0) {
        val hours = screenTimeToday / 60
        val mins = screenTimeToday % 60
        val timeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        parts.add("Screen time today: $timeStr")
    }

    // Headphones
    if (isHeadphonesConnected) parts.add("Headphones connected")

    // Weather
    weatherDescription?.let { desc ->
        val tempStr = temperatureCelsius?.let { " ${it.toInt()}°C" } ?: ""
        parts.add("Weather: $desc$tempStr")
    }

    return parts.joinToString(" | ")
}
