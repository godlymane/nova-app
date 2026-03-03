package com.nova.companion.brain.thinking

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nova.companion.notification.NovaNotificationHelper
import java.util.Calendar

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
        // For now, reminders are just immediate notifications
        // Future: schedule with AlarmManager or WorkManager
        return executeNotify(action, context)
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
