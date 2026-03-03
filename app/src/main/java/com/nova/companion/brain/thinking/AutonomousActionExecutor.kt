package com.nova.companion.brain.thinking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nova.companion.notification.NovaNotificationHelper
import com.nova.companion.notification.ReminderReceiver
import java.util.Calendar
import java.util.Date
import java.util.UUID

/**
 * Nova's autonomous action system — the Soul.
 *
 * Executes actions from the ThinkingLoop:
 *  - notify: send a notification with Nova's message
 *  - remind: schedule a reminder (future)
 *  - initiate: open app with pre-loaded message (via notification)
 *
 * Rate-limited to prevent being annoying:
 *  - Max 5 actions per day
 *  - Min 1 hour between actions
 *  - No actions between 11pm-7am (respect sleep)
 */
object AutonomousActionExecutor {

    private const val TAG = "AutonomousAction"
    private const val PREFS_NAME = "nova_autonomous"
    private const val KEY_ACTIONS = "recent_actions"
    private const val KEY_LAST_ACTION_TIME = "last_action_time"

    private const val MAX_ACTIONS_PER_DAY = 5
    private const val MIN_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
    private const val QUIET_HOUR_START = 23  // 11 PM
    private const val QUIET_HOUR_END = 7     // 7 AM

    // Notification IDs for autonomous actions (3001+)
    private const val NOTIFICATION_ID_BASE = 3001
    private var nextNotificationId = NOTIFICATION_ID_BASE

    private val gson = Gson()

    /**
     * Attempt to execute an action from the ThinkingLoop.
     * Returns true if action was executed, false if rate-limited or invalid.
     */
    fun execute(action: NovaThinkingLoop.NovaAction, context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!canAct(prefs)) {
            Log.d(TAG, "Rate limited — skipping action: ${action.type}")
            return false
        }

        val success = when (action.type) {
            "notify" -> executeNotify(action, context)
            "initiate" -> executeInitiate(action, context)
            "remind" -> executeRemind(action, context)
            else -> {
                Log.w(TAG, "Unknown action type: ${action.type}")
                false
            }
        }

        if (success) {
            recordAction(prefs, action)
            Log.i(TAG, "Executed ${action.type}: ${action.message.take(60)}")
        }

        return success
    }

    /**
     * Check if Nova is allowed to act right now.
     */
    private fun canAct(prefs: SharedPreferences): Boolean {
        // Quiet hours check
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= QUIET_HOUR_START || hour < QUIET_HOUR_END) {
            Log.d(TAG, "Quiet hours ($hour) — no actions")
            return false
        }

        // Minimum interval between actions
        val lastAction = prefs.getLong(KEY_LAST_ACTION_TIME, 0)
        val timeSince = System.currentTimeMillis() - lastAction
        if (timeSince < MIN_INTERVAL_MS) {
            Log.d(TAG, "Too soon since last action (${timeSince / 60000}min)")
            return false
        }

        // Daily limit
        val todayActions = getActionsToday(prefs)
        if (todayActions >= MAX_ACTIONS_PER_DAY) {
            Log.d(TAG, "Daily limit reached ($todayActions/$MAX_ACTIONS_PER_DAY)")
            return false
        }

        return true
    }

    private fun executeNotify(action: NovaThinkingLoop.NovaAction, context: Context): Boolean {
        return try {
            NovaNotificationHelper.createNotificationChannel(context)
            NovaNotificationHelper.showNotification(
                context = context,
                notificationId = nextNotificationId++,
                message = action.message,
                title = "Nova"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Notify failed", e)
            false
        }
    }

    private fun executeInitiate(action: NovaThinkingLoop.NovaAction, context: Context): Boolean {
        // Same as notify but with a different title to signal it's a conversation starter
        return try {
            NovaNotificationHelper.createNotificationChannel(context)
            NovaNotificationHelper.showNotification(
                context = context,
                notificationId = nextNotificationId++,
                message = action.message,
                title = "Nova wants to talk"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Initiate failed", e)
            false
        }
    }

    private fun executeRemind(action: NovaThinkingLoop.NovaAction, context: Context): Boolean {
        val reminderTimeMs = parseReminderTime(action.message)
        if (reminderTimeMs == null) {
            Log.w(TAG, "No reminder time found in message, sending immediately")
            return executeNotify(action, context)
        }

        // Don't schedule in the past
        if (reminderTimeMs <= System.currentTimeMillis()) {
            Log.w(TAG, "Parsed reminder time is in the past, sending immediately")
            return executeNotify(action, context)
        }

        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val actionId = UUID.randomUUID().toString()

            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_TITLE, "Nova Reminder")
                putExtra(ReminderReceiver.EXTRA_MESSAGE, action.message)
                putExtra(ReminderReceiver.EXTRA_ACTION_ID, actionId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                actionId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, reminderTimeMs, pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, reminderTimeMs, pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, reminderTimeMs, pendingIntent
                )
            }

            Log.i(TAG, "Reminder scheduled for ${Date(reminderTimeMs)}: ${action.message}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule reminder, falling back to immediate", e)
            executeNotify(action, context)
        }
    }

    // ── Reminder time parsing ────────────────────────────────────

    /**
     * Parse a reminder time from the message text.
     * Handles:
     *  - Relative: "in 30 minutes", "in 2 hours", "in 1 hour"
     *  - Absolute: "at 5pm", "at 14:00", "at 5:30pm", "at 5:30 pm"
     *  - Named: "tomorrow morning", "tonight"
     * Returns epoch millis or null if parsing fails.
     */
    internal fun parseReminderTime(message: String): Long? {
        val lower = message.lowercase().trim()

        // ── Relative times: "in X minutes/hours" ──
        val relMinutes = Regex("in\\s+(\\d+)\\s*min(?:ute)?s?").find(lower)
        if (relMinutes != null) {
            val mins = relMinutes.groupValues[1].toLongOrNull() ?: return null
            return System.currentTimeMillis() + mins * 60 * 1000L
        }
        val relHours = Regex("in\\s+(\\d+)\\s*hours?").find(lower)
        if (relHours != null) {
            val hours = relHours.groupValues[1].toLongOrNull() ?: return null
            return System.currentTimeMillis() + hours * 60 * 60 * 1000L
        }
        // "in half an hour"
        if (lower.contains("half an hour") || lower.contains("half hour")) {
            return System.currentTimeMillis() + 30 * 60 * 1000L
        }

        // ── Absolute times: "at 5pm", "at 5:30pm", "at 14:00", "at 5:30 pm" ──
        val absTime = Regex("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(lower)
        if (absTime != null) {
            var hour = absTime.groupValues[1].toIntOrNull() ?: return null
            val minute = absTime.groupValues[2].toIntOrNull() ?: 0
            val ampm = absTime.groupValues[3]

            when (ampm) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
                // No am/pm specified and hour <= 12 — assume 24h format or contextual
            }

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // If the time is already past today, schedule for tomorrow
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // ── Named times ──
        if (lower.contains("tomorrow morning")) {
            return Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        if (lower.contains("tomorrow evening") || lower.contains("tomorrow night")) {
            return Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 19)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        if (lower.contains("tonight")) {
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }.timeInMillis
        }
        if (lower.contains("this afternoon")) {
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 14)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }.timeInMillis
        }

        return null
    }

    // ── Action tracking ──────────────────────────────────────────

    private data class ActionRecord(
        val type: String,
        val message: String,
        val timestamp: Long
    )

    private fun recordAction(prefs: SharedPreferences, action: NovaThinkingLoop.NovaAction) {
        val actions = loadActions(prefs).toMutableList()
        actions.add(ActionRecord(action.type, action.message, System.currentTimeMillis()))

        // Keep only last 24 hours
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        actions.removeAll { it.timestamp < cutoff }

        prefs.edit()
            .putString(KEY_ACTIONS, gson.toJson(actions))
            .putLong(KEY_LAST_ACTION_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun loadActions(prefs: SharedPreferences): List<ActionRecord> {
        val json = prefs.getString(KEY_ACTIONS, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<ActionRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getActionsToday(prefs: SharedPreferences): Int {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis

        return loadActions(prefs).count { it.timestamp >= todayStart }
    }

    /**
     * Get recent actions (last 24h) for injection into ThinkingLoop context.
     */
    fun getRecentActions(context: Context): List<NovaThinkingLoop.NovaAction> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return loadActions(prefs).map {
            NovaThinkingLoop.NovaAction(it.type, it.message, it.timestamp)
        }
    }
}
