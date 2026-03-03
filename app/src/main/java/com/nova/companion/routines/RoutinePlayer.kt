package com.nova.companion.routines

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.ScreenReader
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.overlay.bubble.TaskProgressManager
import kotlinx.coroutines.delay

/**
 * Replays a learned routine using existing tier4 tool primitives.
 *
 * For each step:
 * 1. Verify the correct app is in the foreground
 * 2. Execute the action (tap, type, scroll, back, open_app)
 * 3. For smart fields, call GPT-4o-mini to generate dynamic content
 * 4. Short delay between steps to let UI settle
 */
object RoutinePlayer {

    private const val TAG = "RoutinePlayer"
    private const val STEP_DELAY_MS = 500L
    private const val SETTLE_DELAY_MS = 250L

    data class PlayResult(
        val success: Boolean,
        val message: String,
        val stepsCompleted: Int,
        val totalSteps: Int
    )

    suspend fun play(
        steps: List<RecordedStep>,
        context: Context,
        taskId: String = "routine_replay"
    ): PlayResult {
        if (!NovaAccessibilityService.isRunning()) {
            return PlayResult(false, "Accessibility service not connected.", 0, steps.size)
        }

        if (steps.isEmpty()) {
            return PlayResult(false, "No steps to replay.", 0, 0)
        }

        var completed = 0
        for ((index, step) in steps.withIndex()) {
            Log.d(TAG, "Executing step ${index + 1}/${steps.size}: ${step.action}")

            // Update floating bubble with current step
            val progress = ((index.toFloat() / steps.size) * 100).toInt()
            TaskProgressManager.updateProgress(
                taskId, progress,
                "Step ${index + 1}/${steps.size}: ${describeStep(step)}"
            )

            val success = executeStep(step, context)
            if (!success) {
                // Try once more after a longer wait
                delay(STEP_DELAY_MS)
                val retry = executeStep(step, context)
                if (!retry) {
                    TaskProgressManager.failTask(
                        taskId, "Failed at step ${index + 1}: ${describeStep(step)}"
                    )
                    return PlayResult(
                        false,
                        "Failed at step ${index + 1}: ${describeStep(step)}",
                        completed,
                        steps.size
                    )
                }
            }
            completed++
            delay(STEP_DELAY_MS)
        }

        TaskProgressManager.completeTask(taskId, "Done — $completed steps completed")
        return PlayResult(true, "Completed all $completed steps.", completed, steps.size)
    }

    private suspend fun executeStep(step: RecordedStep, context: Context): Boolean {
        return try {
            when (step.action) {
                "open_app" -> openApp(step.packageName, context)
                "tap" -> executeTap(step)
                "type" -> executeType(step, context)
                "scroll" -> executeScroll(step)
                "back" -> {
                    NovaAccessibilityService.instance?.pressBack()
                    delay(SETTLE_DELAY_MS)
                    true
                }
                "wait" -> {
                    delay(2000)
                    true
                }
                else -> {
                    Log.w(TAG, "Unknown action: ${step.action}")
                    true // skip unknown actions
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step execution failed: ${step.action}", e)
            false
        }
    }

    private suspend fun openApp(packageName: String, context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            delay(2000) // wait for app to launch
            return true
        }
        Log.w(TAG, "Could not launch package: $packageName")
        return false
    }

    private suspend fun executeTap(step: RecordedStep): Boolean {
        // Try by text first, then by description
        if (step.targetText.isNotBlank()) {
            val tapped = UIAutomator.tapByText(step.targetText)
            if (tapped) {
                delay(SETTLE_DELAY_MS)
                return true
            }
        }
        if (step.targetDesc.isNotBlank()) {
            val tapped = UIAutomator.tapByDescription(step.targetDesc)
            if (tapped) {
                delay(SETTLE_DELAY_MS)
                return true
            }
        }
        // Element not found — try scrolling to find it
        Log.d(TAG, "Element not found, trying scroll...")
        UIAutomator.scroll("down")
        delay(SETTLE_DELAY_MS)
        if (step.targetText.isNotBlank()) {
            return UIAutomator.tapByText(step.targetText)
        }
        if (step.targetDesc.isNotBlank()) {
            return UIAutomator.tapByDescription(step.targetDesc)
        }
        return false
    }

    private suspend fun executeType(step: RecordedStep, context: Context): Boolean {
        val textToType = if (step.isSmartField) {
            // Generate dynamic content via LLM
            generateSmartContent(step, context) ?: step.typedText
        } else {
            step.typedText
        }

        if (textToType.isBlank()) return true

        return UIAutomator.typeText(textToType, clearFirst = true)
    }

    private suspend fun executeScroll(step: RecordedStep): Boolean {
        val dir = step.scrollDirection.ifBlank { "down" }
        UIAutomator.scroll(dir)
        delay(SETTLE_DELAY_MS)
        return true
    }

    /**
     * For smart fields, ask GPT-4o-mini to generate content
     * based on the hint and current screen context.
     */
    private suspend fun generateSmartContent(step: RecordedStep, context: Context): String? {
        return try {
            val screenContent = try {
                ScreenReader.readScreenCompact()
            } catch (_: Exception) { "" }

            val systemPrompt = "You generate short, natural content for social media and apps. " +
                    "Be casual, creative, and authentic. No hashtags unless asked. " +
                    "Just output the text — no quotes, no explanation."

            val userPrompt = buildString {
                append("Generate: ${step.smartFieldHint}")
                if (screenContent.isNotBlank()) {
                    append("\n\nCurrent screen context:\n$screenContent")
                }
            }

            OpenAIClient.miniCompletion(systemPrompt, userPrompt, context)
        } catch (e: Exception) {
            Log.e(TAG, "Smart content generation failed", e)
            null
        }
    }

    private fun describeStep(step: RecordedStep): String {
        return when (step.action) {
            "tap" -> "tap on '${step.targetText.ifBlank { step.targetDesc }}'"
            "type" -> "type '${step.typedText.take(20)}...'"
            "scroll" -> "scroll ${step.scrollDirection}"
            "open_app" -> "open ${step.packageName}"
            "back" -> "press back"
            else -> step.action
        }
    }
}
