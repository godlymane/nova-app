package com.nova.companion

import android.app.Application
import android.util.Log
import com.nova.companion.data.NovaDatabase
import com.nova.companion.notification.NotificationScheduler
import com.nova.companion.notification.NovaNotificationHelper

class NovaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("NovaApp", "Nova companion initialized")

        // Initialize database (lazy singleton, first access creates it)
        NovaDatabase.getInstance(this)

        // Create notification channel
        NovaNotificationHelper.createNotificationChannel(this)

        // Schedule all notification workers
        NotificationScheduler.scheduleAll(this)
    }
}
