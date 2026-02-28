package com.nova.companion.brain

import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.context.TimeOfDay
import com.nova.companion.brain.memory.BrainMemoryManager
import com.nova.companion.brain.memory.entities.LearnedRoutine
import com.nova.companion.brain.memory.entities.UserPreference

/**
 * Builds the contextual addendum to Nova's personality prompt.
 *
 * IMPORTANT: Nova's core personality lives in the ElevenLabs dashboard.
 * This class only builds the dynamic, context-aware addendum that gets
 * injected when a voice session starts via sendContextualUpdate().
 *
 * The addendum covers:
 * - Current time/day context
 * - User's current situation (location, calendar, battery)
 * - Relevant memories and preferences
 * - Suggested conversation posture (energetic, calm, concise, etc.)
 *
 * Keep it under ~500 words to stay within ElevenLabs context limits.
 */
object NovaPersonality {

    /**
     * Build the full contextual system prompt addendum.
     * This is sent to ElevenLabs at the start of each voice session.
     */
    suspend fun buildContextualAddendum(
        snapshot: ContextSnapshot,
        memoryManager: BrainMemoryManager
    ): String {
        val sb = StringBuilder()

        // Section 1: Temporal context
        sb.appendLine(buildTimeContext(snapshot))

        // Section 2: Current situation
        sb.appendLine(buildSituationContext(snapshot))

        // Section 3: Memory context
        val memorySummary = memoryManager.buildMemorySummary()
        if (memorySummary.isNotEmpty()) {
            sb.appendLine("\n[Memory context]")
            sb.appendLine(memorySummary)
        }

        // Section 4: Tone guidance based on context
        sb.appendLine("\n[Tone guidance]")
        sb.appendLine(buildToneGuidance(snapshot))

        return sb.toString().trim()
    }

    /**
     * Lightweight version — no DB access, just snapshot-based context.
     * Use this when speed matters more than memory depth.
     */
    fun buildQuickContext(snapshot: ContextSnapshot): String {
        return buildString {
            appendLine(buildTimeContext(snapshot))
            appendLine(buildSituationContext(snapshot))
            appendLine("\n[Tone guidance]")
            appendLine(buildToneGuidance(snapshot))
        }.trim()
    }

    // ─────────────────────────────────────────────
    // Private builders
    // ─────────────────────────────────────────────

    private fun buildTimeContext(snapshot: ContextSnapshot): String {
        val dayName = when (snapshot.dayOfWeek) {
            1 -> "Sunday"
            2 -> "Monday"
            3 -> "Tuesday"
            4 -> "Wednesday"
            5 -> "Thursday"
            6 -> "Friday"
            7 -> "Saturday"
            else -> "today"
        }

        val timeDesc = when (snapshot.timeOfDay) {
            TimeOfDay.EARLY_MORNING -> "early morning (${snapshot.hour}:00)"
            TimeOfDay.MORNING -> "morning (${snapshot.hour}:00)"
            TimeOfDay.AFTERNOON -> "afternoon (${snapshot.hour}:00)"
            TimeOfDay.EVENING -> "evening (${snapshot.hour}:00)"
            TimeOfDay.NIGHT -> "night (${snapshot.hour}:00)"
            TimeOfDay.UNKNOWN -> "${snapshot.hour}:00"
        }

        return "[Current time] It's $timeDesc on $dayName${if (snapshot.isWeekend) " (weekend)" else " (weekday)"}.\n" +
            "The user's current time is ${snapshot.hour}:00."
    }

    private fun buildSituationContext(snapshot: ContextSnapshot): String {
        val parts = mutableListOf<String>()

        // Location
        when {
            snapshot.isHome == true -> parts.add("User is at home.")
            snapshot.isWork == true -> parts.add("User is at work.")
            snapshot.isMoving == true -> parts.add("User appears to be in transit/commuting.")
            snapshot.locationLabel != null -> parts.add("User is at: ${snapshot.locationLabel}.")
        }

        // Calendar
        when {
            snapshot.minutesToNextEvent != null && snapshot.minutesToNextEvent!! <= 30 ->
                parts.add("User has ${snapshot.nextEvent?.title ?: "a meeting"} in ${snapshot.minutesToNextEvent} minutes.")
            snapshot.eventsToday > 0 ->
                parts.add("User has ${snapshot.eventsToday} event(s) today.")
        }

        // Battery
        when {
            snapshot.batteryLevel in 1..15 && !snapshot.isCharging ->
                parts.add("Battery is critically low (${snapshot.batteryLevel}%).")
            snapshot.batteryLevel in 16..25 && !snapshot.isCharging ->
                parts.add("Battery is low (${snapshot.batteryLevel}%).")
        }

        // Communication
        if (snapshot.missedCalls > 0) {
            parts.add("User has ${snapshot.missedCalls} missed call(s)${snapshot.lastContactedPerson?.let { " from $it" } ?: ""}.")
        }
        if (snapshot.unreadSmsCount > 3) {
            parts.add("User has ${snapshot.unreadSmsCount} unread messages.")
        }

        // Headphones
        if (snapshot.isHeadphonesConnected) {
            parts.add("User has headphones connected.")
        }

        if (parts.isEmpty()) return "[Current situation] No notable context signals."

        return "[Current situation]\n" + parts.joinToString("\n") { "- $it" }
    }

    private fun buildToneGuidance(snapshot: ContextSnapshot): String {
        return when {
            // Late night — be quiet and calm
            snapshot.timeOfDay == TimeOfDay.NIGHT ->
                "It's late. Be calm, soft, and concise. Don't be overly energetic. Keep responses short unless the user wants to talk."

            // Early morning — gentle wake-up energy
            snapshot.timeOfDay == TimeOfDay.EARLY_MORNING ->
                "It's early. Be gentle and warm. Don't bombard with info. Help ease into the day."

            // Low battery — be efficient
            snapshot.batteryLevel in 1..20 && !snapshot.isCharging ->
                "Battery is low. Keep responses concise to conserve resources. Mention charging if relevant."

            // Meeting imminent — be brief
            snapshot.minutesToNextEvent != null && snapshot.minutesToNextEvent!! <= 15 ->
                "User has a meeting very soon. Be brief. Stick to essentials. Don't start long conversations."

            // Weekend morning — relaxed
            snapshot.isWeekend && snapshot.timeOfDay == TimeOfDay.MORNING ->
                "It's a weekend morning. Be relaxed and unhurried. No rush — casual tone is fine."

            // Default — natural and engaged
            else -> "Be natural, warm, and engaged. Match the user's energy."
        }
    }
}
