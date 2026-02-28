package com.nova.companion.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.Calendar

/**
 * Single worker that handles all notification types.
 * Each PeriodicWorkRequest passes a NOTIFICATION_TYPE via inputData.
 */
class NovaWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "NovaWorker"
        const val KEY_NOTIFICATION_TYPE = "notification_type"

        // Notification type constants
        const val TYPE_MORNING = "morning"
        const val TYPE_GYM = "gym"
        const val TYPE_LUNCH = "lunch"
        const val TYPE_DINNER = "dinner"
        const val TYPE_NIGHT = "night"
        const val TYPE_INACTIVE = "inactive"
        const val TYPE_SMART_WEEKLY = "smart_weekly"
    }

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_NOTIFICATION_TYPE) ?: return Result.failure()
        val prefs = NovaNotificationPrefs(applicationContext)

        // Check master toggle
        if (!prefs.masterEnabled) {
            Log.d(TAG, "Master toggle disabled, skipping $type")
            return Result.success()
        }

        Log.d(TAG, "Processing notification type: $type")

        when (type) {
            TYPE_MORNING -> {
                if (prefs.morningEnabled) {
                    NovaNotificationHelper.showNotification(
                        applicationContext,
                        NovaNotificationHelper.NOTIFICATION_ID_MORNING,
                        NovaMessages.random(NovaMessages.morning)
                    )
                }
            }

            TYPE_GYM -> {
                if (prefs.gymEnabled) {
                    NovaNotificationHelper.showNotification(
                        applicationContext,
                        NovaNotificationHelper.NOTIFICATION_ID_GYM,
                        NovaMessages.random(NovaMessages.preGym)
                    )
                }
            }

            TYPE_LUNCH -> {
                if (prefs.lunchEnabled) {
                    NovaNotificationHelper.showNotification(
                        applicationContext,
                        NovaNotificationHelper.NOTIFICATION_ID_LUNCH,
                        NovaMessages.random(NovaMessages.lunch)
                    )
                }
            }

            TYPE_DINNER -> {
                if (prefs.dinnerEnabled) {
                    NovaNotificationHelper.showNotification(
                        applicationContext,
                        NovaNotificationHelper.NOTIFICATION_ID_DINNER,
                        NovaMessages.random(NovaMessages.dinner)
                    )
                }
            }

            TYPE_NIGHT -> {
                if (prefs.nightEnabled) {
                    NovaNotificationHelper.showNotification(
                        applicationContext,
                        NovaNotificationHelper.NOTIFICATION_ID_NIGHT,
                        NovaMessages.random(NovaMessages.night)
                    )
                }
            }

            TYPE_INACTIVE -> {
                if (prefs.smartEnabled) {
                    val hoursSinceOpen =
                        (System.currentTimeMillis() - prefs.lastAppOpen) / (1000 * 60 * 60)
                    if (hoursSinceOpen >= 48) {
                        NovaNotificationHelper.showNotification(
                            applicationContext,
                            NovaNotificationHelper.NOTIFICATION_ID_INACTIVE,
                            NovaMessages.random(NovaMessages.inactive)
                        )
                    }
                }
            }

            TYPE_SMART_WEEKLY -> {
                if (prefs.smartEnabled) {
                    val calendar = Calendar.getInstance()
                    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                    val hour = calendar.get(Calendar.HOUR_OF_DAY)

                    when {
                        // Sunday evening (5-9 PM)
                        dayOfWeek == Calendar.SUNDAY && hour in 17..21 -> {
                            NovaNotificationHelper.showNotification(
                                applicationContext,
                                NovaNotificationHelper.NOTIFICATION_ID_SUNDAY,
                                NovaMessages.random(NovaMessages.sundayReview)
                            )
                        }
                        // Monday morning (7-10 AM)
                        dayOfWeek == Calendar.MONDAY && hour in 7..10 -> {
                            NovaNotificationHelper.showNotification(
                                applicationContext,
                                NovaNotificationHelper.NOTIFICATION_ID_MONDAY,
                                NovaMessages.random(NovaMessages.mondayGoals)
                            )
                        }
                    }
                }
            }
        }

        return Result.success()
    }
}
