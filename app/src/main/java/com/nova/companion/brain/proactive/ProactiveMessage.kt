package com.nova.companion.brain.proactive

/**
 * Represents a proactive message that Nova can initiate.
 */
data class ProactiveMessage(
    val message: String,
    val priority: Priority = Priority.NORMAL,
    val triggerType: String = "unknown",
    val speakImmediately: Boolean = false
) {
    enum class Priority { LOW, NORMAL, HIGH, CRITICAL }
}
