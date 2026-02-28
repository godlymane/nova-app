package com.nova.companion.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nova.companion.MainActivity
import com.nova.companion.R

/**
 * Handles notification channel creation and displaying notifications.
 */
object NovaNotificationHelper {

    const val CHANNEL_ID = "nova_proactive"
    const val CHANNEL_NAME = "Nova"
    const val CHANNEL_DESCRIPTION = "Proactive check-ins from Nova"

    // Notification IDs for each type
    const val NOTIFICATION_ID_MORNING = 1001
    const val NOTIFICATION_ID_GYM = 1002
    const val NOTIFICATION_ID_LUNCH = 1003
    const val NOTIFICATION_ID_DINNER = 1004
    const val NOTIFICATION_ID_NIGHT = 1005
    const val NOTIFICATION_ID_INACTIVE = 1006
    const val NOTIFICATION_ID_SUNDAY = 1007
    const val NOTIFICATION_ID_MONDAY = 1008

    /**
     * Create the notification channel. Safe to call multiple times.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                setShowBadge(true)
            }

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Show a notification with the given message.
     * Tapping opens ChatScreen (MainActivity).
     */
    fun showNotification(
        context: Context,
        notificationId: Int,
        message: String,
        title: String = "Nova"
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("from_notification", true)
            putExtra("notification_message", message)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nova_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }
}
