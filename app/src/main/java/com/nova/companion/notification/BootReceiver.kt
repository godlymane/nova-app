package com.nova.companion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-schedules notification workers after device reboot.
 * WorkManager persists work across reboots automatically,
 * but this ensures the channel is ready and schedules are fresh.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device rebooted, reinitializing Nova notifications")
            NovaNotificationHelper.createNotificationChannel(context)
            NotificationScheduler.scheduleAll(context)
        }
    }
}
