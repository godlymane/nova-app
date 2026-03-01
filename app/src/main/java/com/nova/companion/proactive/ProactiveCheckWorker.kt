package com.nova.companion.proactive

import android.content.Context
import android.util.Log
import androidx.work.*
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.Memory
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Background worker that checks for proactive notification opportunities.
 * Runs periodically (every 2 hours) and fires contextual reminders/check-ins.
 *
 * Examples of proactive triggers:
 * - No gym checkin logged after 7pm → "Did you hit the gym today?"
 * - Blayzex not mentioned in 3 days → "How's Blayzex going?"
 * - Stress keywords in last 24h → "You seemed off yesterday. Better today?"
 * - Morning 8am → "Good morning. What's locking in today?"
 */
class ProactiveCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProactiveCheckWorker"
        private const val WORK_NAME = "nova_proactive_check"
        private const val INTERVAL_HOURS = 2L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<ProactiveCheckWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "Proactive checks scheduled every $INTERVAL_HOURS hours")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    private val db by lazy { NovaDatabase.getInstance(applicationContext) }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running proactive check...")

        try {
            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)

            // Run morning check-in at 8 AM
            if (hour == 8) {
                checkMorningRoutine(now)
            }

            // Run evening check-in at 7 PM
            if (hour == 19) {
                checkEveningRoutine(now)
            }

            // Run fitness check between 6–9 PM
            if (hour in 18..21) {
                checkGymStreak(now)
            }

            // Check Blayzex engagement
            checkBlayzexEngagement()

            // Check emotional state from recent memories
            checkEmotionalState(now)

        } catch (e: Exception) {
            Log.e(TAG, "Proactive check failed", e)
            return Result.retry()
        }

        return Result.success()
    }

    // ────────────────────────────────────────────────────────────

    private suspend fun checkMorningRoutine(now: Calendar) {
        val today = getDateKey(now)
        val lastMorning = getLastLogDate("morning_checkin")

        if (lastMorning != today) {
            val hour = now.get(Calendar.HOUR_OF_DAY)
            if (hour in 7..10) {
                ProactiveNotificationHelper.post(
                    applicationContext,
                    notifId = 1001,
                    title = "Good morning",
                    message = "What are we locking in today?"
                )
                Log.d(TAG, "Morning check-in fired")
            }
        }
    }

    private suspend fun checkEveningRoutine(now: Calendar) {
        val today = getDateKey(now)
        val lastEvening = getLastLogDate("night_reflection")

        if (lastEvening != today) {
            ProactiveNotificationHelper.post(
                applicationContext,
                notifId = 1002,
                title = "End of day",
                message = "What was today's win?"
            )
            Log.d(TAG, "Evening reflection fired")
        }
    }

    private suspend fun checkGymStreak(now: Calendar) {
        val today = getDateKey(now)
        val lastGym = getLastLogDate("gym_checkin")

        if (lastGym != today) {
            // Check if they haven't gone today
            val hour = now.get(Calendar.HOUR_OF_DAY)
            if (hour >= 19) {  // After 7 PM
                val daysSinceGym = getDaysSinceLog("gym_checkin")
                val message = when {
                    daysSinceGym == 0 -> return  // Already went today
                    daysSinceGym == 1 -> "You didn't go yesterday. Don't skip twice."
                    daysSinceGym >= 3 -> "${daysSinceGym} days since gym. You know what to do."
                    else -> "Did you train today?"
                }
                ProactiveNotificationHelper.post(
                    applicationContext,
                    notifId = 1003,
                    title = "Gym check",
                    message = message
                )
            }
        }
    }

    private suspend fun checkBlayzexEngagement() {
        val daysSinceBlayzex = getDaysSinceMemoryKeyword("blayzex")
        if (daysSinceBlayzex >= 3) {
            ProactiveNotificationHelper.post(
                applicationContext,
                notifId = 1004,
                title = "Blayzex check",
                message = "${daysSinceBlayzex} days since you mentioned Blayzex. What's the status?"
            )
            Log.d(TAG, "Blayzex engagement check fired")
        }
    }

    private suspend fun checkEmotionalState(now: Calendar) {
        // Look for stress/anxiety keywords in last 24h memories
        val yesterday = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
        val recentMemories = db.memoryDao().getMemoriesSince(yesterday, 20)

        val stressKeywords = listOf("stressed", "anxious", "overwhelmed", "struggling", "panic")
        val hadStressfulDay = recentMemories.any { memory ->
            stressKeywords.any { keyword -> memory.content.contains(keyword, ignoreCase = true) }
        }

        if (hadStressfulDay) {
            val hour = now.get(Calendar.HOUR_OF_DAY)
            if (hour in 10..18) {  // Mid-day check-in
                ProactiveNotificationHelper.post(
                    applicationContext,
                    notifId = 1005,
                    title = "Checking in",
                    message = "You seemed off recently. Better today?"
                )
                Log.d(TAG, "Emotional state check fired")
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // HELPERS
    // ────────────────────────────────────────────────────────────

    private fun getDateKey(cal: Calendar): String {
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    private suspend fun getLastLogDate(logKey: String): String {
        // Search memories for the log entry
        val memories = db.memoryDao().searchByKeyword(logKey, 1)
        if (memories.isEmpty()) return ""
        val cal = Calendar.getInstance().apply { timeInMillis = memories[0].lastAccessed }
        return getDateKey(cal)
    }

    private suspend fun getDaysSinceLog(logKey: String): Int {
        val memories = db.memoryDao().searchByKeyword(logKey, 1)
        if (memories.isEmpty()) return 99
        val lastMs = memories[0].lastAccessed
        val diffMs = System.currentTimeMillis() - lastMs
        return TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
    }

    private suspend fun getDaysSinceMemoryKeyword(keyword: String): Int {
        val memories = db.memoryDao().searchByKeyword(keyword, 1)
        if (memories.isEmpty()) return 99
        val lastMs = memories[0].lastAccessed
        val diffMs = System.currentTimeMillis() - lastMs
        return TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
    }
}
