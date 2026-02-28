package com.nova.companion.services

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NovaNotification(
    val key: String,
    val appName: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long
)

class NovaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NovaNotificationListener"
        private const val MAX_CACHED = 50

        private val _activeNotifications = MutableStateFlow<List<NovaNotification>>(emptyList())
        val activeNotifications: StateFlow<List<NovaNotification>> = _activeNotifications
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        // Load existing notifications on connect
        try {
            val existing = activeNotifications
                ?.mapNotNull { sbn -> sbn.toNovaNotification() }
                ?: emptyList()
            _activeNotifications.value = existing.takeLast(MAX_CACHED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load existing notifications", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val notification = sbn?.toNovaNotification() ?: return
        Log.d(TAG, "Notification posted: ${notification.appName}: ${notification.title}")

        val current = _activeNotifications.value.toMutableList()
        // Remove existing notification with same key if updating
        current.removeAll { it.key == notification.key }
        current.add(notification)

        // Keep only the most recent MAX_CACHED notifications
        _activeNotifications.value = current.takeLast(MAX_CACHED)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val key = sbn?.key ?: return
        Log.d(TAG, "Notification removed: $key")
        _activeNotifications.value = _activeNotifications.value.filter { it.key != key }
    }

    private fun StatusBarNotification.toNovaNotification(): NovaNotification? {
        return try {
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()

            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                ).toString()
            } catch (e: Exception) {
                packageName
            }

            NovaNotification(
                key = key,
                appName = appName,
                packageName = packageName,
                title = title,
                text = text,
                timestamp = postTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification", e)
            null
        }
    }
}
