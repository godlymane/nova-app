package com.nova.companion.brain.proactive.triggers

import android.util.Log
import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.memory.BrainMemoryManager
import com.nova.companion.brain.proactive.ProactiveMessage

class RoutineTrigger(private val memoryManager: BrainMemoryManager) {

    companion object {
        private const val TAG = "RoutineTrigger"
    }

    suspend fun evaluate(snapshot: ContextSnapshot): ProactiveMessage? {
        return try {
            val routines = memoryManager.getRoutinesAt(snapshot.dayOfWeek, snapshot.hour)
            val confirmedRoutines = routines.filter { it.observationCount >= 3 && it.confidence >= 0.7f }

            if (confirmedRoutines.isEmpty()) return null

            val routine = confirmedRoutines.first()
            when (routine.routineType) {
                "workout" -> ProactiveMessage(
                    message = "It's around your usual workout time. Ready to get moving?",
                    priority = ProactiveMessage.Priority.NORMAL,
                    triggerType = "routine_workout"
                )
                "commute" -> ProactiveMessage(
                    message = "Looks like your usual commute time. Want me to check traffic?",
                    priority = ProactiveMessage.Priority.NORMAL,
                    triggerType = "routine_commute"
                )
                "lunch" -> ProactiveMessage(
                    message = "It's your usual lunch time. Have you eaten?",
                    priority = ProactiveMessage.Priority.LOW,
                    triggerType = "routine_lunch"
                )
                "sleep" -> ProactiveMessage(
                    message = "It's around your usual bedtime. Winding down?",
                    priority = ProactiveMessage.Priority.LOW,
                    triggerType = "routine_sleep"
                )
                "wake" -> ProactiveMessage(
                    message = "Good morning! Ready to start the day?",
                    priority = ProactiveMessage.Priority.LOW,
                    triggerType = "routine_wake"
                )
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating routine trigger", e)
            null
        }
    }
}
