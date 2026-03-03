package com.nova.companion.agent

/**
 * Represents a single action the LLM has decided to take during a ReAct step.
 *
 * The LLM returns JSON like:
 * {
 *   "thought": "I see a search bar, I should tap it to start searching",
 *   "action": "tap",
 *   "target": "Search",
 *   "text": ""
 * }
 *
 * This sealed class maps those JSON responses into type-safe Kotlin actions
 * that the AgentExecutor can dispatch to UIAutomator.
 */
sealed class AgentAction {

    /** LLM's reasoning for choosing this action */
    abstract val thought: String

    /** Tap an element identified by its visible text or content description */
    data class Tap(
        override val thought: String,
        val target: String
    ) : AgentAction()

    /** Tap at exact percentage coordinates (from vision system) */
    data class TapXY(
        override val thought: String,
        val xPct: Float,
        val yPct: Float
    ) : AgentAction()

    /** Type text into the currently focused (or labeled) input field */
    data class Type(
        override val thought: String,
        val target: String,
        val text: String
    ) : AgentAction()

    /** Scroll the screen in a given direction */
    data class Scroll(
        override val thought: String,
        val direction: String = "down"
    ) : AgentAction()

    /** Press the hardware/system back button */
    data class Back(
        override val thought: String
    ) : AgentAction()

    /** Open an app by package name */
    data class OpenApp(
        override val thought: String,
        val packageName: String
    ) : AgentAction()

    /** Wait for UI to settle (e.g. after a page load) */
    data class Wait(
        override val thought: String,
        val durationMs: Long = 2000
    ) : AgentAction()

    /** The agent believes the goal has been achieved */
    data class Done(
        override val thought: String,
        val summary: String = ""
    ) : AgentAction()

    /** The agent cannot proceed — needs to bail out */
    data class Fail(
        override val thought: String,
        val reason: String
    ) : AgentAction()

    companion object {

        /**
         * Parse the LLM's JSON response into a typed AgentAction.
         * Expects:
         * {
         *   "thought": "string",
         *   "action": "tap" | "type" | "scroll" | "back" | "open_app" | "wait" | "done" | "fail",
         *   "target": "string (element text/desc or package name)",
         *   "text":   "string (for type action)",
         *   "direction": "up|down|left|right (for scroll)"
         * }
         */
        fun fromJson(json: com.google.gson.JsonObject): AgentAction {
            val thought = json.get("thought")?.asString ?: "No reasoning provided"
            val action = json.get("action")?.asString?.lowercase()?.trim()
                ?: return Fail(thought, "Missing 'action' field in LLM response")

            return when (action) {
                "tap" -> {
                    val target = json.get("target")?.asString
                        ?: return Fail(thought, "tap action requires a 'target'")
                    Tap(thought, target)
                }
                "tap_xy" -> {
                    val xPct = json.get("x_pct")?.asFloat
                        ?: return Fail(thought, "tap_xy requires 'x_pct'")
                    val yPct = json.get("y_pct")?.asFloat
                        ?: return Fail(thought, "tap_xy requires 'y_pct'")
                    TapXY(thought, xPct.coerceIn(0f, 1f), yPct.coerceIn(0f, 1f))
                }
                "type" -> {
                    val target = json.get("target")?.asString ?: ""
                    val text = json.get("text")?.asString
                        ?: return Fail(thought, "type action requires 'text'")
                    Type(thought, target, text)
                }
                "scroll" -> {
                    val direction = json.get("direction")?.asString ?: "down"
                    Scroll(thought, direction)
                }
                "back" -> Back(thought)
                "open_app" -> {
                    val pkg = json.get("target")?.asString
                        ?: return Fail(thought, "open_app requires a 'target' package name")
                    OpenApp(thought, pkg)
                }
                "wait" -> {
                    val duration = json.get("duration_ms")?.asLong ?: 2000L
                    Wait(thought, duration.coerceIn(500, 5000))
                }
                "done" -> {
                    val summary = json.get("summary")?.asString ?: json.get("text")?.asString ?: ""
                    Done(thought, summary)
                }
                "fail" -> {
                    val reason = json.get("reason")?.asString ?: json.get("text")?.asString ?: "Unknown failure"
                    Fail(thought, reason)
                }
                else -> Fail(thought, "Unknown action type: '$action'")
            }
        }
    }
}

/**
 * Result of a single ReAct step execution.
 */
data class StepResult(
    val stepNumber: Int,
    val action: AgentAction,
    val success: Boolean,
    val observation: String
)

/**
 * Final result of the entire agent execution.
 */
data class AgentResult(
    val goalAchieved: Boolean,
    val summary: String,
    val stepsExecuted: Int,
    val stepLog: List<StepResult>,
    val terminationReason: TerminationReason
)

enum class TerminationReason {
    GOAL_ACHIEVED,      // LLM returned done()
    MAX_STEPS_REACHED,  // Circuit breaker triggered
    AGENT_GAVE_UP,      // LLM returned fail()
    LLM_ERROR,          // Could not get a response from LLM
    EXECUTION_ERROR     // Unrecoverable error during action execution
}
