package com.nova.companion.notification

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules all Nova notification workers.
 * Uses WorkManager PeriodicWorkRequests with initial delays calculated
 * to hit the target time of day.
 */
object NotificationScheduler {

    private const val TAG = "NotificationScheduler"

    // Unique work names
    private const val WORK_MORNING = "nova_morning"
    private const val WORK_GYM = "nova_gym"
    private const val WORK_LUNCH = "nova_lunch"
    private const val WORK_DINNER = "nova_dinner"
    private const val WORK_NIGHT = "nova_night"
    private const val WORK_INACTIVE = "nova_inactive"
    private const val WORK_SMART_WEEKLY = "nova_smart_weekly"

    /**
     * Schedule all notification workers based on current preferences.
     * Uses KEEP policy so existing schedules aren't replaced on every app launch.
     * Call rescheduleAll() when user changes times.
     */
    fun scheduleAll(context: Context) {
        val prefs = NovaNotificationPrefs(context)

        if (!prefs.masterEnabled) {
            cancelAll(context)
            return
        }

        scheduleDailyWorker(context, WORK_MORNING, prefs.morningTime, NovaWorker.TYPE_MORNING)
        scheduleDailyWorker(context, WORK_GYM, prefs.gymTime, NovaWorker.TYPE_GYM)
        scheduleDailyWorker(context, WORK_LUNCH, prefs.lunchTime, NovaWorker.TYPE_LUNCH)
        scheduleDailyWorker(context, WORK_DINNER, prefs.dinnerTime, NovaWorker.TYPE_DINNER)
        scheduleDailyWorker(context, WORK_NIGHT, prefs.nightTime, NovaWorker.TYPE_NIGHT)

        // Inactivity check every 24 hours
        schedulePeriodicWorker(
            context,
            WORK_INACTIVE,
            NovaWorker.TYPE_INACTIVE,
            repeatIntervalHours = 24
        )

        // Weekly smart triggers - check every 12 hours
        // The worker itself checks day-of-week
        schedulePeriodicWorker(
            context,
            WORK_SMART_WEEKLY,
            NovaWorker.TYPE_SMART_WEEKLY,
            repeatIntervalHours = 12
        )

        Log.i(TAG, "All notification workers scheduled")
    }

    /**
     * Cancel all workers and reschedule. Call when user changes notification times.
     */
    fun rescheduleAll(context: Context) {
        cancelAll(context)
        scheduleAll(context)
    }

    /**
     * Cancel all Nova notification workers.
     */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_MORNING)
        wm.cancelUniqueWork(WORK_GYM)
        wm.cancelUniqueWork(WORK_LUNCH)
        wm.cancelUniqueWork(WORK_DINNER)
        wm.cancelUniqueWork(WORK_NIGHT)
        wm.cancelUniqueWork(WORK_INACTIVE)
        wm.cancelUniqueWork(WORK_SMART_WEEKLY)
        Log.i(TAG, "All notification workers cancelled")
    }

    /**
     * Schedule a daily worker that fires at a specific time of day.
     * Uses a 24-hour periodic interval with an initial delay to align to the target time.
     */
    private fun scheduleDailyWorker(
        context: Context,
        uniqueWorkName: String,
        timeString: String,
        notificationType: String
    ) {
        val (hour, minute) = timeString.split(":").map { it.toInt() }
        val initialDelay = calculateInitialDelay(hour, minute)

        val inputData = workDataOf(
            NovaWorker.KEY_NOTIFICATION_TYPE to notificationType
        )

        val workRequest = PeriodicWorkRequestBuilder<NovaWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .addTag("nova_notification")
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Log.d(TAG, "Scheduled $uniqueWorkName at $timeString (delay: ${initialDelay / 60000}min)")
    }

    /**
     * Schedule a periodic worker with a custom interval.
     */
    private fun schedulePeriodicWorker(
        context: Context,
        uniqueWorkName: String,
        notificationType: String,
        repeatIntervalHours: Long
    ) {
        val inputData = workDataOf(
            NovaWorker.KEY_NOTIFICATION_TYPE to notificationType
        )

        val workRequest = PeriodicWorkRequestBuilder<NovaWorker>(
            repeatIntervalHours, TimeUnit.HOURS
        )
            .setInputData(inputData)
            .addTag("nova_notification")
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Log.d(TAG, "Scheduled $uniqueWorkName every ${repeatIntervalHours}h")
    }

    /**
     * Calculate milliseconds from now until the next occurrence of HH:mm.
     * If the time already passed today, schedule for tomorrow.
     */
    private fun calculateInitialDelay(targetHour: Int, targetMinute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If target time already passed today, schedule for tomorrow
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }
}
