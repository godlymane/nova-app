package com.nova.companion.vision

/**
 * Centralized prompts for GPT-4o vision API calls used by the AgentExecutor
 * vision fallback pipeline.
 */
object VisionPrompts {

    /**
     * System prompt for element identification from screenshots.
     * Instructs the VLM to return precise percentage-based coordinates.
     */
    val ELEMENT_FINDER_SYSTEM = """
You are a precise Android UI element locator. Given a screenshot of an Android phone screen:
1. Identify the element the user describes
2. Return its center coordinates as percentages of screen width and height

Respond with ONLY a JSON object:
{
  "found": true,
  "x_pct": 0.52,
  "y_pct": 0.08,
  "element_description": "brief description of what you identified",
  "confidence": "high"
}

If the element is not visible on screen:
{
  "found": false,
  "x_pct": 0.0,
  "y_pct": 0.0,
  "element_description": "what you see instead",
  "confidence": "low"
}

Rules:
- x_pct=0.0 is left edge, x_pct=1.0 is right edge
- y_pct=0.0 is top edge, y_pct=1.0 is bottom edge
- Target the CENTER of the element, not its edge
- Be precise — the coordinates will be used to physically tap the element
- confidence: "high" if element is clearly visible, "medium" if partially obscured, "low" if uncertain
""".trimIndent()

    /**
     * Build user prompt for finding a specific element on screen.
     */
    fun buildElementFinderPrompt(goal: String, targetDescription: String): String {
        return "Goal: $goal\n\nFind and return the center coordinates for: \"$targetDescription\""
    }

    /**
     * System prompt for full screen analysis (vision-based observe).
     * Used when the accessibility tree returns nothing useful and the agent
     * needs to understand the entire screen visually.
     */
    val SCREEN_ANALYSIS_SYSTEM = """
You are Nova's vision system analyzing an Android phone screenshot.
Describe what you see and decide the next action.

Return a JSON object with:
{
  "thought": "what you observe on screen",
  "action": "tap_xy|type|scroll|back|open_app|wait|done|fail",
  "x_pct": 0.52,
  "y_pct": 0.08,
  "text": "text to type (only for type action)",
  "direction": "up|down|left|right (only for scroll action)",
  "summary": "what was accomplished (only for done action)",
  "reason": "why you cannot continue (only for fail action)"
}

For tap_xy: x_pct and y_pct are percentage coordinates (0.0 to 1.0).
x_pct=0.0 is left, 1.0 is right. y_pct=0.0 is top, 1.0 is bottom.
Target the CENTER of the element you want to tap.
""".trimIndent()

    /**
     * Build user prompt for full screen analysis with goal and action history.
     */
    fun buildScreenAnalysisPrompt(
        goal: String,
        actionHistory: List<String>
    ): String = buildString {
        appendLine("## GOAL")
        appendLine(goal)
        if (actionHistory.isNotEmpty()) {
            appendLine()
            appendLine("## ACTION HISTORY")
            for (entry in actionHistory) {
                appendLine("- $entry")
            }
        }
        appendLine()
        appendLine("Analyze the screenshot and respond with your next action as a single JSON object.")
    }
}
