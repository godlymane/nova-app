package com.nova.companion.workflows

import android.content.Context
import android.util.Log
import com.nova.companion.overlay.bubble.TaskProgressManager
import kotlinx.coroutines.delay

/**
 * Executes workflow templates step by step.
 * Reports progress to TaskProgressManager so the bubble overlay stays in sync.
 */
class WorkflowExecutor(private val context: Context) {

    companion object {
        private const val TAG = "WorkflowExecutor"
    }

    /**
     * Execute a workflow template and return the primary response message.
     *
     * Progress is reported to TaskProgressManager throughout execution.
     * The caller receives the final assembled response string.
     */
    suspend fun execute(template: WorkflowTemplate, userInput: String): String {
        Log.d(TAG, "Executing workflow: ${template.id}")

        // Start task bubble
        TaskProgressManager.startTask(template.id, template.name)

        val responseLines = mutableListOf<String>()
        val totalSteps = template.steps.size

        for ((index, step) in template.steps.withIndex()) {
            val progressPercent = ((index + 1).toFloat() / totalSteps * 100).toInt()

            // Apply step delay if specified
            if (step.delayMs > 0) delay(step.delayMs)

            when (step.action) {
                "message" -> {
                    responseLines.add(step.content)
                    TaskProgressManager.updateProgress(
                        template.id,
                        progressPercent,
                        "Step ${index + 1}/${totalSteps}"
                    )
                    Log.d(TAG, "[${template.id}] message: ${step.content}")
                }

                "log" -> {
                    val logValue = step.content.replace("{date}", getCurrentDate())
                    // TODO: persist log entry to DB when logging table is added
                    TaskProgressManager.updateProgress(
                        template.id,
                        progressPercent,
                        "Logging..."
                    )
                    Log.d(TAG, "[${template.id}] log: $logValue")
                }

                "daily_brief" -> {
                    // TODO: fetch from DB and inject top priorities
                    responseLines.add("[Daily brief coming soon]")
                    TaskProgressManager.updateProgress(template.id, progressPercent, "Fetching brief...")
                }

                "recall_memories" -> {
                    // TODO: query MemoryManager and inject summary
                    responseLines.add("[Memory recall coming soon]")
                    TaskProgressManager.updateProgress(template.id, progressPercent, "Recalling...")
                }

                "api_call" -> {
                    // TODO: plug in real API call
                    TaskProgressManager.updateProgress(template.id, progressPercent, "Calling API...")
                    Log.d(TAG, "[${template.id}] api_call: ${step.content}")
                }

                else -> {
                    Log.w(TAG, "Unknown step action: ${step.action}")
                }
            }
        }

        // Complete the task bubble
        TaskProgressManager.completeTask(template.id, "Done")

        return responseLines.joinToString("\n")
    }

    private fun getCurrentDate(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }
}
