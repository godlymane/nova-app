package com.nova.companion.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.ScreenReader
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.overlay.bubble.TaskProgressManager
import com.nova.companion.vision.BitmapEncoder
import com.nova.companion.vision.ScreenshotService
import com.nova.companion.vision.VisionPrompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AgentExecutor — Nova's ReAct (Think → Act → Observe) loop.
 *
 * Replaces the deterministic RoutinePlayer with an LLM-driven agent that
 * dynamically navigates Android apps to accomplish a user-defined goal.
 *
 * Flow per step:
 *   1. OBSERVE  → ScreenReader.readScreenCompact() to capture current UI state
 *   2. THINK    → Send goal + screen state + history to LLM, get structured JSON action
 *   3. ACT      → Execute the action via UIAutomator
 *   4. REPEAT   → Loop until done(), fail(), or circuit breaker trips
 *
 * The LLM sees the full action history so it can course-correct when actions fail
 * or the UI isn't what it expected.
 */
class AgentExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AgentExecutor"
        private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o"

        /** Circuit breaker — hard cap on steps to prevent infinite loops */
        const val MAX_STEPS = 15

        /** Delay after each action to let UI settle before next observation */
        private const val POST_ACTION_DELAY_MS = 800L

        /** Max retries for a single LLM call if it returns garbage */
        private const val MAX_LLM_RETRIES = 2
    }

    /** Current goal — stored so visionFallbackTap can reference it */
    private var currentGoal: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── System prompt that instructs the LLM how to be a ReAct agent ──────

    private val agentSystemPrompt = """
You are Nova's autonomous UI agent. You control an Android phone to accomplish goals.

## How You Work
Each turn you receive:
- The GOAL you must accomplish
- The SCREEN STATE (list of interactive elements currently visible)
- Your ACTION HISTORY (what you already did and what happened)

You respond with exactly ONE JSON object — your next action.

## Response Format (strict JSON, no markdown fences)
{
  "thought": "Your reasoning about what you see and what to do next",
  "action": "tap|tap_xy|type|scroll|back|open_app|wait|done|fail",
  "target": "element text, content description, or package name",
  "text": "text to type (only for type action)",
  "x_pct": 0.5, "y_pct": 0.3,
  "direction": "up|down|left|right (only for scroll action)",
  "summary": "what was accomplished (only for done action)",
  "reason": "why you can't continue (only for fail action)"
}

## Actions
- tap: Tap an element. "target" = the exact text or description shown in the screen state.
- tap_xy: Tap at exact screen coordinates. "x_pct" and "y_pct" = percentage position (0.0-1.0). Use only when you can see the screen via vision and need to tap something without a text label.
- type: Type into a field. "target" = field label (empty = currently focused field). "text" = what to type.
- scroll: Scroll the page. "direction" = up/down/left/right.
- back: Press the Android back button.
- open_app: Launch an app. "target" = package name (e.g. com.google.android.gm).
- wait: Wait for UI to load. Use after open_app or slow transitions.
- done: The goal is achieved. Include "summary" of what was accomplished.
- fail: You cannot achieve the goal. Include "reason" explaining why.

## Rules
1. Pick the SINGLE best next action. Do not plan ahead — just one step.
2. Use EXACT text from the screen state for tap targets. Don't guess element names.
3. If an element isn't visible, try scrolling before giving up.
4. If an action fails (you'll see it in history), try a different approach.
5. After 2-3 failed attempts at the same thing, use "fail" with a clear reason.
6. Keep "thought" concise — 1-2 sentences max.
7. Respond with ONLY the JSON object. No explanation, no markdown.
""".trimIndent()

    // ── Main entry point ──────────────────────────────────────────────

    /**
     * Execute a goal using the ReAct loop.
     *
     * @param goal Natural-language description of what to accomplish
     * @param taskId Unique ID for TaskProgressManager tracking
     * @return AgentResult with full execution log
     */
    suspend fun execute(
        goal: String,
        taskId: String = "agent_${System.currentTimeMillis()}"
    ): AgentResult {
        Log.i(TAG, "Starting agent execution — Goal: $goal")

        // Pre-flight checks
        if (!NovaAccessibilityService.isRunning()) {
            return AgentResult(
                goalAchieved = false,
                summary = "Accessibility service not running",
                stepsExecuted = 0,
                stepLog = emptyList(),
                terminationReason = TerminationReason.EXECUTION_ERROR
            )
        }

        if (!CloudConfig.hasOpenAiKey()) {
            return AgentResult(
                goalAchieved = false,
                summary = "OpenAI API key not configured",
                stepsExecuted = 0,
                stepLog = emptyList(),
                terminationReason = TerminationReason.LLM_ERROR
            )
        }

        // Store goal for vision fallback
        currentGoal = goal

        // Initialize progress tracking
        TaskProgressManager.startTask(taskId, "Agent: $goal")
        val stepLog = mutableListOf<StepResult>()
        val actionHistory = mutableListOf<String>()

        try {
            for (step in 1..MAX_STEPS) {
                val progressPercent = ((step - 1).toFloat() / MAX_STEPS * 100).toInt()

                // ── OBSERVE ──────────────────────────────────────────
                val screenState = observe()
                Log.d(TAG, "Step $step OBSERVE: ${screenState.take(200)}...")

                TaskProgressManager.updateProgress(
                    taskId, progressPercent,
                    "Step $step/$MAX_STEPS: Observing screen..."
                )

                // ── THINK ────────────────────────────────────────────
                TaskProgressManager.updateProgress(
                    taskId, progressPercent,
                    "Step $step/$MAX_STEPS: Thinking..."
                )

                val action = think(goal, screenState, actionHistory)
                if (action == null) {
                    // LLM completely failed after retries
                    TaskProgressManager.failTask(taskId, "Lost connection to AI")
                    return AgentResult(
                        goalAchieved = false,
                        summary = "LLM failed to return a valid action after retries",
                        stepsExecuted = step - 1,
                        stepLog = stepLog,
                        terminationReason = TerminationReason.LLM_ERROR
                    )
                }

                Log.d(TAG, "Step $step THINK: [${action.javaClass.simpleName}] ${action.thought}")

                // Check for terminal actions before executing
                if (action is AgentAction.Done) {
                    val result = StepResult(step, action, true, "Goal achieved")
                    stepLog.add(result)
                    TaskProgressManager.completeTask(taskId, action.summary.ifBlank { "Done!" })
                    return AgentResult(
                        goalAchieved = true,
                        summary = action.summary.ifBlank { action.thought },
                        stepsExecuted = step,
                        stepLog = stepLog,
                        terminationReason = TerminationReason.GOAL_ACHIEVED
                    )
                }

                if (action is AgentAction.Fail) {
                    val result = StepResult(step, action, false, action.reason)
                    stepLog.add(result)
                    TaskProgressManager.failTask(taskId, action.reason)
                    return AgentResult(
                        goalAchieved = false,
                        summary = action.reason,
                        stepsExecuted = step,
                        stepLog = stepLog,
                        terminationReason = TerminationReason.AGENT_GAVE_UP
                    )
                }

                // ── ACT ──────────────────────────────────────────────
                TaskProgressManager.updateProgress(
                    taskId, progressPercent,
                    "Step $step/$MAX_STEPS: ${describeAction(action)}"
                )

                val (success, observation) = act(action)

                Log.d(TAG, "Step $step ACT: success=$success, observation=$observation")

                val result = StepResult(step, action, success, observation)
                stepLog.add(result)

                // Record in history so the LLM knows what happened
                actionHistory.add(
                    "Step $step: ${action.thought} → ${describeAction(action)} → " +
                    if (success) "OK: $observation" else "FAILED: $observation"
                )

                // Settle delay before next observation
                delay(POST_ACTION_DELAY_MS)
            }

            // Circuit breaker tripped
            Log.w(TAG, "Circuit breaker: max $MAX_STEPS steps reached")
            TaskProgressManager.failTask(taskId, "Reached step limit ($MAX_STEPS)")
            return AgentResult(
                goalAchieved = false,
                summary = "Reached maximum step limit ($MAX_STEPS) without completing the goal",
                stepsExecuted = MAX_STEPS,
                stepLog = stepLog,
                terminationReason = TerminationReason.MAX_STEPS_REACHED
            )

        } catch (e: Exception) {
            Log.e(TAG, "Agent execution crashed", e)
            TaskProgressManager.failTask(taskId, "Error: ${e.message}")
            return AgentResult(
                goalAchieved = false,
                summary = "Execution error: ${e.message}",
                stepsExecuted = stepLog.size,
                stepLog = stepLog,
                terminationReason = TerminationReason.EXECUTION_ERROR
            )
        }
    }

    // ── OBSERVE: Read the current screen state ────────────────────────

    private fun observe(): String {
        return try {
            val compact = ScreenReader.readScreenCompact()
            if (compact.isBlank() || compact.startsWith("Accessibility service")) {
                "Screen not readable — accessibility service may not be connected"
            } else {
                compact
            }
        } catch (e: Exception) {
            Log.e(TAG, "Observe failed", e)
            "Error reading screen: ${e.message}"
        }
    }

    // ── THINK: Ask the LLM for the next action ───────────────────────

    /**
     * Calls GPT-4o with the goal, current screen, and action history.
     * Forces JSON output via response_format and parses into AgentAction.
     * Retries up to MAX_LLM_RETRIES times if the response isn't valid JSON.
     */
    private suspend fun think(
        goal: String,
        screenState: String,
        actionHistory: List<String>
    ): AgentAction? {
        val userPrompt = buildString {
            appendLine("## GOAL")
            appendLine(goal)
            appendLine()
            appendLine("## CURRENT SCREEN STATE")
            appendLine(screenState)
            if (actionHistory.isNotEmpty()) {
                appendLine()
                appendLine("## ACTION HISTORY")
                for (entry in actionHistory) {
                    appendLine("- $entry")
                }
            }
            appendLine()
            appendLine("Respond with your next action as a single JSON object.")
        }

        for (attempt in 1..MAX_LLM_RETRIES) {
            val rawResponse = callLlmJson(userPrompt)
            if (rawResponse == null) {
                Log.w(TAG, "LLM returned null on attempt $attempt")
                if (attempt < MAX_LLM_RETRIES) delay(1000)
                continue
            }

            val action = parseAction(rawResponse)
            if (action != null) return action

            Log.w(TAG, "Failed to parse LLM response on attempt $attempt: ${rawResponse.take(200)}")
            if (attempt < MAX_LLM_RETRIES) delay(500)
        }

        return null // All retries exhausted
    }

    /**
     * Make a raw LLM call with JSON response format enforced.
     */
    private suspend fun callLlmJson(userPrompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) return@withContext null

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", agentSystemPrompt)
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userPrompt)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", MODEL)
            add("messages", messages)
            addProperty("max_tokens", 300)
            addProperty("temperature", 0.3) // Low temp for deterministic actions
            // Force JSON output so the LLM doesn't wrap in markdown
            add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "LLM call failed: ${response.code} - ${responseBody?.take(200)}")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString

            // Track token usage
            val usage = json.getAsJsonObject("usage")
            val totalTokens = usage?.get("total_tokens")?.asInt ?: 0
            CloudConfig.trackOpenAiTokens(context, totalTokens)

            content
        } catch (e: Exception) {
            Log.e(TAG, "LLM call error", e)
            null
        }
    }

    /**
     * Parse raw LLM text into an AgentAction.
     * Handles edge cases: markdown fences, extra whitespace, nested JSON.
     */
    private fun parseAction(raw: String): AgentAction? {
        return try {
            // Strip markdown code fences if the LLM still wraps despite json_object mode
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JsonParser.parseString(cleaned).asJsonObject
            AgentAction.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: ${e.message} — raw: ${raw.take(200)}")
            null
        }
    }

    // ── ACT: Execute the chosen action via UIAutomator ────────────────

    /**
     * Dispatches an AgentAction to the appropriate UIAutomator method.
     * Returns (success, observation) — observation is a brief description
     * of what happened, fed back to the LLM in the next step.
     */
    private suspend fun act(action: AgentAction): Pair<Boolean, String> {
        return try {
            when (action) {
                is AgentAction.Tap -> {
                    // Try by text first, then by content description
                    val tapped = UIAutomator.tapByText(action.target)
                        || UIAutomator.tapByDescription(action.target)
                    if (tapped) {
                        delay(300) // Let tap animation settle
                        Pair(true, "Tapped '${action.target}'")
                    } else {
                        // Vision fallback: screenshot → GPT-4o → coordinate tap
                        visionFallbackTap(currentGoal, action.target)
                    }
                }

                is AgentAction.TapXY -> {
                    val tapped = UIAutomator.tapAtPercentage(action.xPct, action.yPct)
                    if (tapped) {
                        delay(300)
                        Pair(true, "Tapped at (${(action.xPct * 100).toInt()}%, ${(action.yPct * 100).toInt()}%)")
                    } else {
                        Pair(false, "Coordinate tap failed at (${action.xPct}, ${action.yPct})")
                    }
                }

                is AgentAction.Type -> {
                    val typed = if (action.target.isNotBlank()) {
                        // Type into a specific field by label
                        UIAutomator.typeTextByLabel(action.target, action.text)
                    } else {
                        // Type into the currently focused field
                        UIAutomator.typeText(action.text, clearFirst = true)
                    }
                    if (typed) {
                        Pair(true, "Typed '${action.text.take(30)}' into '${action.target.ifBlank { "focused field" }}'")
                    } else {
                        Pair(false, "Could not type into '${action.target.ifBlank { "focused field" }}'")
                    }
                }

                is AgentAction.Scroll -> {
                    val scrolled = UIAutomator.scroll(action.direction)
                    delay(500) // Let scroll settle
                    if (scrolled) {
                        Pair(true, "Scrolled ${action.direction}")
                    } else {
                        Pair(false, "Scroll ${action.direction} failed — may be at end of content")
                    }
                }

                is AgentAction.Back -> {
                    UIAutomator.pressBack()
                    delay(300)
                    Pair(true, "Pressed back")
                }

                is AgentAction.OpenApp -> {
                    val launched = openApp(action.packageName)
                    if (launched) {
                        delay(2000) // Wait for app to fully launch
                        Pair(true, "Opened ${action.packageName}")
                    } else {
                        Pair(false, "Could not launch ${action.packageName} — app may not be installed")
                    }
                }

                is AgentAction.Wait -> {
                    delay(action.durationMs)
                    Pair(true, "Waited ${action.durationMs}ms for UI to settle")
                }

                // Done and Fail are handled before act() is called,
                // but handle them here defensively
                is AgentAction.Done -> Pair(true, action.summary)
                is AgentAction.Fail -> Pair(false, action.reason)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Action execution error", e)
            Pair(false, "Execution error: ${e.message}")
        }
    }

    private fun openApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    // ── Vision Fallback ──────────────────────────────────────────────

    /**
     * When text-based tap fails, screenshot the screen, send to GPT-4o vision,
     * and tap at the returned coordinates.
     *
     * Returns (success, observation) just like act() so the caller can record it.
     */
    private suspend fun visionFallbackTap(goal: String, target: String): Pair<Boolean, String> {
        if (!ScreenshotService.isRunning()) {
            Log.d(TAG, "Vision fallback skipped — ScreenshotService not running")
            return Pair(false, "Could not find element '$target' on screen (vision not available)")
        }

        Log.i(TAG, "Vision fallback: attempting screenshot for target '$target'")

        // 1. Capture screenshot (in-memory only)
        val bitmap = ScreenshotService.captureScreenshot()
        if (bitmap == null) {
            Log.w(TAG, "Vision fallback: screenshot capture failed")
            return Pair(false, "Could not find element '$target' (screenshot failed)")
        }

        try {
            // 2. Encode to base64 JPEG
            val base64 = BitmapEncoder.encodeToBase64(bitmap)
            bitmap.recycle()

            // 3. Ask GPT-4o vision to locate the element
            val systemPrompt = VisionPrompts.ELEMENT_FINDER_SYSTEM
            val userPrompt = VisionPrompts.buildElementFinderPrompt(goal, target)

            val response = OpenAIClient.visionCompletion(systemPrompt, userPrompt, base64, context)
            if (response == null) {
                Log.w(TAG, "Vision fallback: VLM returned null")
                return Pair(false, "Could not find element '$target' (vision API failed)")
            }

            // 4. Parse the VLM response
            val json = try {
                val cleaned = response.trim()
                    .removePrefix("```json").removePrefix("```")
                    .removeSuffix("```").trim()
                JsonParser.parseString(cleaned).asJsonObject
            } catch (e: Exception) {
                Log.w(TAG, "Vision fallback: failed to parse VLM response: ${response.take(200)}")
                return Pair(false, "Could not find element '$target' (vision parse error)")
            }

            val found = json.get("found")?.asBoolean ?: false
            if (!found) {
                val desc = json.get("element_description")?.asString ?: "not visible"
                Log.d(TAG, "Vision fallback: element not found by VLM — $desc")
                return Pair(false, "Could not find element '$target' — vision says: $desc")
            }

            val xPct = json.get("x_pct")?.asFloat ?: return Pair(false, "Vision returned no x_pct")
            val yPct = json.get("y_pct")?.asFloat ?: return Pair(false, "Vision returned no y_pct")
            val confidence = json.get("confidence")?.asString ?: "unknown"

            Log.i(TAG, "Vision fallback: found '$target' at ($xPct, $yPct) confidence=$confidence")

            // 5. Tap at the vision-provided coordinates
            val tapped = UIAutomator.tapAtPercentage(
                xPct.coerceIn(0f, 1f),
                yPct.coerceIn(0f, 1f)
            )

            return if (tapped) {
                delay(300)
                Pair(true, "Tapped '$target' via vision at (${(xPct * 100).toInt()}%, ${(yPct * 100).toInt()}%) [$confidence]")
            } else {
                Pair(false, "Vision located '$target' but coordinate tap failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision fallback error", e)
            return Pair(false, "Could not find element '$target' (vision error: ${e.message})")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun describeAction(action: AgentAction): String {
        return when (action) {
            is AgentAction.Tap -> "Tap '${action.target}'"
            is AgentAction.TapXY -> "Tap at (${(action.xPct * 100).toInt()}%, ${(action.yPct * 100).toInt()}%)"
            is AgentAction.Type -> "Type '${action.text.take(20)}...' into '${action.target.ifBlank { "field" }}'"
            is AgentAction.Scroll -> "Scroll ${action.direction}"
            is AgentAction.Back -> "Press back"
            is AgentAction.OpenApp -> "Open ${action.packageName}"
            is AgentAction.Wait -> "Wait ${action.durationMs}ms"
            is AgentAction.Done -> "Done: ${action.summary}"
            is AgentAction.Fail -> "Fail: ${action.reason}"
        }
    }
}
