package com.nova.companion.overlay.bubble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton manager that tracks active task progress.
 * WorkflowExecutor (and any other task runner) pushes updates here.
 * TaskBubbleService observes and updates the overlay UI.
 */
object TaskProgressManager {

    private val _activeTask = MutableStateFlow<TaskProgressState?>(null)
    val activeTask: StateFlow<TaskProgressState?> = _activeTask.asStateFlow()

    /**
     * Start tracking a new task.
     */
    fun startTask(taskId: String, title: String) {
        _activeTask.value = TaskProgressState(
            taskId = taskId,
            title = title,
            progress = 0,
            statusMessage = "Starting..."
        )
    }

    /**
     * Update progress (0–100) and status message.
     */
    fun updateProgress(taskId: String, progress: Int, message: String = "") {
        val current = _activeTask.value ?: return
        if (current.taskId != taskId) return
        _activeTask.value = current.copy(
            progress = progress.coerceIn(0, 100),
            statusMessage = message
        )
    }

    /**
     * Mark task as complete.
     */
    fun completeTask(taskId: String, finalMessage: String = "Done!") {
        val current = _activeTask.value ?: return
        if (current.taskId != taskId) return
        _activeTask.value = current.copy(
            progress = 100,
            statusMessage = finalMessage,
            isComplete = true
        )
    }

    /**
     * Mark task as failed.
     */
    fun failTask(taskId: String, errorMessage: String = "Something went wrong") {
        val current = _activeTask.value ?: return
        if (current.taskId != taskId) return
        _activeTask.value = current.copy(
            statusMessage = errorMessage,
            isFailed = true
        )
    }

    /**
     * Clear the active task (called after user dismisses or auto-dismiss timer fires).
     */
    fun clearTask() {
        _activeTask.value = null
    }
}
