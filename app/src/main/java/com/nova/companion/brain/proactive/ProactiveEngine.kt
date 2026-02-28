package com.nova.companion.brain.proactive

import android.content.Context
import android.util.Log
import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.memory.BrainMemoryManager
import com.nova.companion.brain.proactive.triggers.BatteryTrigger
import com.nova.companion.brain.proactive.triggers.CalendarTrigger
import com.nova.companion.brain.proactive.triggers.CommunicationTrigger
import com.nova.companion.brain.proactive.triggers.RoutineTrigger
import com.nova.companion.brain.proactive.triggers.WellnessTrigger

/**
 * Evaluates all proactive triggers and decides what (if anything) Nova should say.
 *
 * Cooldown system (prevents spam):
 *   - Global: 10 minutes between ANY proactive messages
 *   - Per-category: 30 minutes between messages of same category
 *   - Per-trigger: 60 minutes between same specific trigger
 *
 * Usage:
 *   val engine = ProactiveEngine(context, memoryManager)
 *   val message = engine.evaluate(currentSnapshot)
 *   if (message != null) { nova.speak(message.message) }
 */
class ProactiveEngine(
    private val context: Context,
    private val memoryManager: BrainMemoryManager
) {
    companion object {
        private const val TAG = "ProactiveEngine"
        private const val GLOBAL_COOLDOWN_MS = 10 * 60 * 1000L      // 10 minutes
        private const val CATEGORY_COOLDOWN_MS = 30 * 60 * 1000L    // 30 minutes
        private const val TRIGGER_COOLDOWN_MS = 60 * 60 * 1000L     // 60 minutes
    }

    private var lastGlobalMessageTime = 0L
    private val lastCategoryMessageTime = mutableMapOf<String, Long>()
    private val lastTriggerMessageTime = mutableMapOf<String, Long>()

    private val calendarTrigger = CalendarTrigger()
    private val batteryTrigger = BatteryTrigger()
    private val routineTrigger = RoutineTrigger(memoryManager)
    private val wellnessTrigger = WellnessTrigger()
    private val communicationTrigger = CommunicationTrigger()

    /**
     * Main entry point. Evaluates all triggers and returns the highest-priority message.
     * Returns null if nothing should be said (all triggers quiet, or in cooldown).
     */
    suspend fun evaluate(snapshot: ContextSnapshot): ProactiveMessage? {
        val now = System.currentTimeMillis()

        // Check global cooldown first
        if (now - lastGlobalMessageTime < GLOBAL_COOLDOWN_MS) {
            Log.d(TAG, "Global cooldown active, skipping evaluation")
            return null
        }

        // Collect all triggered messages
        val candidates = mutableListOf<ProactiveMessage>()

        // Battery trigger
        batteryTrigger.evaluate(snapshot)?.let { msg ->
            if (canFire("battery", msg.triggerType, now)) candidates.add(msg)
        }

        // Calendar trigger
        calendarTrigger.evaluate(snapshot)?.let { msg ->
            if (canFire("calendar", msg.triggerType, now)) candidates.add(msg)
        }

        // Routine trigger
        routineTrigger.evaluate(snapshot)?.let { msg ->
            if (canFire("routine", msg.triggerType, now)) candidates.add(msg)
        }

        // Wellness trigger
        wellnessTrigger.evaluate(snapshot)?.let { msg ->
            if (canFire("wellness", msg.triggerType, now)) candidates.add(msg)
        }

        // Communication trigger
        communicationTrigger.evaluate(snapshot)?.let { msg ->
            if (canFire("communication", msg.triggerType, now)) candidates.add(msg)
        }

        if (candidates.isEmpty()) return null

        // Pick highest priority message
        val chosen = candidates.maxByOrNull { it.priority.ordinal } ?: return null

        // Record the fire
        val category = chosen.triggerType.split("_").firstOrNull() ?: "unknown"
        lastGlobalMessageTime = now
        lastCategoryMessageTime[category] = now
        lastTriggerMessageTime[chosen.triggerType] = now

        Log.i(TAG, "Firing proactive message: ${chosen.triggerType} | ${chosen.message.take(60)}")
        return chosen
    }

    private fun canFire(category: String, triggerType: String, now: Long): Boolean {
        val categoryLastFired = lastCategoryMessageTime[category] ?: 0L
        val triggerLastFired = lastTriggerMessageTime[triggerType] ?: 0L

        if (now - categoryLastFired < CATEGORY_COOLDOWN_MS) {
            Log.d(TAG, "Category cooldown active for: $category")
            return false
        }
        if (now - triggerLastFired < TRIGGER_COOLDOWN_MS) {
            Log.d(TAG, "Trigger cooldown active for: $triggerType")
            return false
        }
        return true
    }

    /**
     * Reset all cooldowns (useful for testing).
     */
    fun resetCooldowns() {
        lastGlobalMessageTime = 0L
        lastCategoryMessageTime.clear()
        lastTriggerMessageTime.clear()
    }
}
