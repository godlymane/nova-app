package com.nova.companion.brain.thinking

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nova.companion.brain.emotion.NovaEmotionEngine
import com.nova.companion.cloud.CloudConfig

/**
 * Background worker that runs Nova's ThinkingLoop every 30 minutes.
 *
 * Flow:
 * 1. Collect device context + memories + emotion state
 * 2. Call GPT-4o-mini for inner monologue
 * 3. Update emotion state from thought
 * 4. If thought produces an action, pass to AutonomousActionExecutor
 *
 * Cost: ~$0.50/month at 48 calls/day × 500 tokens avg
 */
class ThinkingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ThinkingWorker"
    }

    override suspend fun doWork(): Result {
        if (!CloudConfig.isOnline(applicationContext) || !CloudConfig.hasOpenAiKey()) {
            Log.d(TAG, "Offline or no API key — skipping thinking cycle")
            return Result.success()  // Don't retry, just skip this cycle
        }

        Log.i(TAG, "Starting thinking cycle...")

        // Ensure EmotionEngine is initialized
        try {
            NovaEmotionEngine.initialize(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "EmotionEngine init failed", e)
        }

        // Run the thinking loop
        val thought = NovaThinkingLoop.think(applicationContext)

        if (thought == null) {
            Log.w(TAG, "Thinking cycle produced no thought")
            return Result.success()
        }

        // Update emotion state
        thought.moodUpdate?.let { moodUpdate ->
            try {
                NovaEmotionEngine.updateFromThinkingLoop(moodUpdate)
            } catch (e: Exception) {
                Log.e(TAG, "Emotion update from thought failed", e)
            }
        }

        // Execute action if present
        thought.action?.let { action ->
            try {
                val executed = AutonomousActionExecutor.execute(action, applicationContext)
                Log.i(TAG, "Action ${action.type}: executed=$executed")
            } catch (e: Exception) {
                Log.e(TAG, "Action execution failed", e)
            }
        }

        Log.i(TAG, "Thinking cycle complete")
        return Result.success()
    }
}
