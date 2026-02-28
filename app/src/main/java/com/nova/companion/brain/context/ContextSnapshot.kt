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
