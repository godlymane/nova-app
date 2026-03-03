package com.nova.companion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires scheduled reminder notifications via AlarmManager.
 * Created by AutonomousActionExecutor.executeRemind() when user says
 * "remind me at 5pm" or "remind me in 30 minutes".
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderReceiver"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACTION_ID = "action_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Nova Reminder"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "You asked me to remind you"
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: "reminder"

        Log.i(TAG, "Reminder fired: $message")

        NovaNotificationHelper.createNotificationChannel(context)
        NovaNotificationHelper.showNotification(
            context = context,
            notificationId = actionId.hashCode(),
            message = message,
            title = title
        )
    }
}
