package com.nova.companion.notification

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages all notification preferences using SharedPreferences.
 * Stores toggle states and custom times for each notification type.
 */
class NovaNotificationPrefs(context: Context) {

    companion object {
        private const val PREFS_NAME = "nova_notification_prefs"

        // Master toggle
        private const val KEY_MASTER_ENABLED = "master_enabled"

        // Individual toggles
        private const val KEY_MORNING_ENABLED = "morning_enabled"
        private const val KEY_GYM_ENABLED = "gym_enabled"
        private const val KEY_LUNCH_ENABLED = "lunch_enabled"
        private const val KEY_DINNER_ENABLED = "dinner_enabled"
        private const val KEY_NIGHT_ENABLED = "night_enabled"
        private const val KEY_SMART_ENABLED = "smart_enabled"

        // Custom times (stored as "HH:mm")
        private const val KEY_MORNING_TIME = "morning_time"
        private const val KEY_GYM_TIME = "gym_time"
        private const val KEY_LUNCH_TIME = "lunch_time"
        private const val KEY_DINNER_TIME = "dinner_time"
        private const val KEY_NIGHT_TIME = "night_time"

        // Last app open timestamp for inactivity detection
        private const val KEY_LAST_APP_OPEN = "last_app_open"

        // Default times
        const val DEFAULT_MORNING_TIME = "06:30"
        const val DEFAULT_GYM_TIME = "17:00"
        const val DEFAULT_LUNCH_TIME = "12:30"
        const val DEFAULT_DINNER_TIME = "19:30"
        const val DEFAULT_NIGHT_TIME = "23:00"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Master toggle
    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER_ENABLED, value).apply()

    // Individual toggles
    var morningEnabled: Boolean
        get() = prefs.getBoolean(KEY_MORNING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MORNING_ENABLED, value).apply()

    var gymEnabled: Boolean
        get() = prefs.getBoolean(KEY_GYM_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GYM_ENABLED, value).apply()

    var lunchEnabled: Boolean
        get() = prefs.getBoolean(KEY_LUNCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LUNCH_ENABLED, value).apply()

    var dinnerEnabled: Boolean
        get() = prefs.getBoolean(KEY_DINNER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DINNER_ENABLED, value).apply()

    var nightEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_ENABLED, value).apply()

    var smartEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_ENABLED, value).apply()

    // Custom times
    var morningTime: String
        get() = prefs.getString(KEY_MORNING_TIME, DEFAULT_MORNING_TIME) ?: DEFAULT_MORNING_TIME
        set(value) = prefs.edit().putString(KEY_MORNING_TIME, value).apply()

    var gymTime: String
        get() = prefs.getString(KEY_GYM_TIME, DEFAULT_GYM_TIME) ?: DEFAULT_GYM_TIME
        set(value) = prefs.edit().putString(KEY_GYM_TIME, value).apply()

    var lunchTime: String
        get() = prefs.getString(KEY_LUNCH_TIME, DEFAULT_LUNCH_TIME) ?: DEFAULT_LUNCH_TIME
        set(value) = prefs.edit().putString(KEY_LUNCH_TIME, value).apply()

    var dinnerTime: String
        get() = prefs.getString(KEY_DINNER_TIME, DEFAULT_DINNER_TIME) ?: DEFAULT_DINNER_TIME
        set(value) = prefs.edit().putString(KEY_DINNER_TIME, value).apply()

    var nightTime: String
        get() = prefs.getString(KEY_NIGHT_TIME, DEFAULT_NIGHT_TIME) ?: DEFAULT_NIGHT_TIME
        set(value) = prefs.edit().putString(KEY_NIGHT_TIME, value).apply()

    // Last app open
    var lastAppOpen: Long
        get() = prefs.getLong(KEY_LAST_APP_OPEN, System.currentTimeMillis())
        set(value) = prefs.edit().putLong(KEY_LAST_APP_OPEN, value).apply()

    fun recordAppOpen() {
        lastAppOpen = System.currentTimeMillis()
    }

    /**
     * Parse time string "HH:mm" to hour and minute pair.
     */
    fun parseTime(time: String): Pair<Int, Int> {
        return try {
            val parts = time.split(":")
            if (parts.size >= 2) {
                Pair(parts[0].toInt(), parts[1].toInt())
            } else {
                Pair(6, 30) // fallback to 6:30 AM
            }
        } catch (e: NumberFormatException) {
            Pair(6, 30) // fallback to 6:30 AM
        }
    }
}
