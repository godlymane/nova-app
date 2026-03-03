package com.nova.companion.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nova.companion.brain.context.ContextEngine
import com.nova.companion.brain.proactive.ContextTimeline
import com.nova.companion.brain.proactive.ProactiveActionDispatcher
import com.nova.companion.brain.proactive.ProactiveDecision
import com.nova.companion.brain.proactive.ProactiveInferenceEngine

/**
 * Periodic worker (every 15 min) that drives Nova's "subconscious".
 *
 * Phase 3 — Dynamic Proactive Inference Engine:
 * Instead of hardcoded if-checks, this worker:
 * 1. Records the latest ContextSnapshot into the rolling timeline
 * 2. Sends the full context timeline to GPT-4o-mini
 * 3. Lets the LLM decide whether to notify, act, or stay silent
 * 4. Dispatches the LLM's decision via ProactiveActionDispatcher
 *
 * Cooldowns and dedup are still enforced via SharedPreferences.
 */
class ProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProactiveWorker"
        private const val PREFS_NAME = "nova_proactive_cooldowns"
        private const val KEY_LAST_NOTIFICATION_TIME = "last_notification_time"
        private const val KEY_LAST_BODY_HASH = "last_body_hash"
        private const val MIN_INTERVAL_MS = 14 * 60 * 1000L // ~14 min
        private const val DEDUP_WINDOW_MS = 60 * 60 * 1000L  // 1 hour
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check cooldown
        val lastTime = prefs.getLong(KEY_LAST_NOTIFICATION_TIME, 0L)
        val now = System.currentTimeMillis()
        if (now - lastTime < MIN_INTERVAL_MS) {
            Log.d(TAG, "Cooldown active (${(now - lastTime) / 60000}min since last), skipping")
            return Result.success()
        }

        // Check master notification toggle
        val notifPrefs = NovaNotificationPrefs(applicationContext)
        if (!notifPrefs.masterEnabled) {
            Log.d(TAG, "Master toggle disabled, skipping")
            return Result.success()
        }

        try {
            // Record the latest context snapshot into the timeline buffer
            val currentSnapshot = ContextEngine.currentContext.value
            ContextTimeline.record(currentSnapshot)
            Log.d(TAG, "Timeline buffer: ${ContextTimeline.size()} snapshots")

            // Run LLM inference
            val decision = ProactiveInferenceEngine.infer(applicationContext)

            if (decision == null) {
                Log.d(TAG, "Inference returned null (offline, no key, or error)")
                return Result.success()
            }

            if (decision is ProactiveDecision.None) {
                Log.d(TAG, "LLM decided: no action needed")
                return Result.success()
            }

            // Dedup: don't send the exact same notification body within 1 hour
            if (decision is ProactiveDecision.Notification) {
                val bodyHash = decision.body.hashCode()
                val lastHash = prefs.getInt(KEY_LAST_BODY_HASH, 0)
                if (bodyHash == lastHash && now - lastTime < DEDUP_WINDOW_MS) {
                    Log.d(TAG, "Duplicate notification body within dedup window, skipping")
                    return Result.success()
                }
            }

            // Dispatch the decision
            val dispatched = ProactiveActionDispatcher.dispatch(applicationContext, decision)

            if (dispatched) {
                Log.i(TAG, "Proactive decision dispatched: $decision")
                prefs.edit()
                    .putLong(KEY_LAST_NOTIFICATION_TIME, now)
                    .apply()

                // Track body hash for dedup
                if (decision is ProactiveDecision.Notification) {
                    prefs.edit()
                        .putInt(KEY_LAST_BODY_HASH, decision.body.hashCode())
                        .apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proactive inference failed", e)
        }

        return Result.success()
    }
}
