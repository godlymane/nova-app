package com.nova.companion

import android.app.Application
import androidx.work.Configuration
import com.nova.companion.proactive.ProactiveNotificationHelper

class NovaApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        ProactiveNotificationHelper.ensureChannel(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
}
