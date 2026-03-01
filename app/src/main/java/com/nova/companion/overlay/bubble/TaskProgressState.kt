package com.nova.companion.overlay.bubble

/**
 * Represents the current state of an active task shown in the bubble overlay.
 */
data class TaskProgressState(
    val taskId: String,
    val title: String,
    val progress: Int = 0,         // 0–100
    val statusMessage: String = "",
    val isComplete: Boolean = false,
    val isFailed: Boolean = false
)
