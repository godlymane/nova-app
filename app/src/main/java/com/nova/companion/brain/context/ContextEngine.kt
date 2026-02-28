package com.nova.companion.brain.context

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nova.companion.R
import com.nova.companion.brain.context.collectors.BatteryCollector
import com.nova.companion.brain.context.collectors.CalendarCollector
import com.nova.companion.brain.context.collectors.CommunicationCollector
import com.nova.companion.brain.context.collectors.DeviceStateCollector
import com.nova.companion.brain.context.collectors.LocationCollector
import com.nova.companion.brain.context.collectors.TimeContextCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Background foreground service that collects device context every 5 minutes.
 * Runs as a persistent foreground service with a low-priority notification.
 * Exposes the latest ContextSnapshot via a static StateFlow.
 */
class ContextEngine : Service() {

    companion object {
        private const val TAG = "ContextEngine"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "nova_context_engine"
        private const val COLLECTION_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        private val _currentContext = MutableStateFlow(ContextSnapshot())
        val currentContext: StateFlow<ContextSnapshot> = _currentContext.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ContextEngine::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContextEngine::class.java)
            context.stopService(intent)
        }

        /**
         * Force an immediate context collection (e.g., when a voice session starts).
         * Non-blocking — updates _currentContext when done.
         */
        fun forceCollect(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val snapshot = collectAll(context)
                    _currentContext.value = snapshot
                    Log.d(TAG, "Force collection completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Force collection error", e)
                }
            }
        }

        /**
         * Runs all collectors and returns a complete ContextSnapshot.
         */
        internal suspend fun collectAll(context: Context): ContextSnapshot {
            var snapshot = ContextSnapshot(timestamp = System.currentTimeMillis())

            // Time context (no permissions needed, always succeeds)
            snapshot = TimeContextCollector.collect(snapshot)

            // Battery (no special permissions)
            snapshot = BatteryCollector.collect(context, snapshot)

            // Calendar (needs READ_CALENDAR — graceful if denied)
            snapshot = CalendarCollector.collect(context, snapshot)

            // Location (needs ACCESS_COARSE_LOCATION — graceful if denied)
            snapshot = LocationCollector.collect(context, snapshot)

            // Communication (needs READ_CALL_LOG, READ_SMS — graceful if denied)
            snapshot = CommunicationCollector.collect(context, snapshot)

            // Device state (needs PACKAGE_USAGE_STATS — graceful if denied)
            snapshot = DeviceStateCollector.collect(context, snapshot)

            return snapshot
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var collectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        _isRunning.value = true
        startPeriodicCollection()
        Log.i(TAG, "ContextEngine service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        collectionJob?.cancel()
        serviceScope.cancel()
        _isRunning.value = false
        Log.i(TAG, "ContextEngine service destroyed")
    }

    private fun startPeriodicCollection() {
        collectionJob = serviceScope.launch {
            // Initial collection immediately
            try {
                val snapshot = collectAll(applicationContext)
                _currentContext.value = snapshot
                Log.i(TAG, "Initial context collection complete: timeOfDay=${snapshot.timeOfDay}, battery=${snapshot.batteryLevel}%")
            } catch (e: Exception) {
                Log.e(TAG, "Initial collection error", e)
            }

            // Then every 5 minutes
            while (true) {
                delay(COLLECTION_INTERVAL_MS)
                try {
                    val snapshot = collectAll(applicationContext)
                    _currentContext.value = snapshot
                    Log.d(TAG, "Periodic context collection: timeOfDay=${snapshot.timeOfDay}, battery=${snapshot.batteryLevel}%")
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic collection error", e)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nova Context Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Nova is learning your patterns to be more helpful"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nova is active")
            .setContentText("Learning your patterns to be more helpful")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
