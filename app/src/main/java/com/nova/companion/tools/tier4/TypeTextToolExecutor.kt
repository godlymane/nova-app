package com.nova.companion.tools.tier4

import android.content.Context
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object TypeTextToolExecutor {

    private const val TAG = "TypeTextTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "typeText",
            description = "Type text into an input field on the current screen. Can target a specific field by its label or type into the focused field.",
            parameters = mapOf(
                "text" to ToolParam(type = "string", description = "The text to type", required = true),
                "field_label" to ToolParam(type = "string", description = "Label of the input field to type into. If not provided, types into the focused or first available field.", required = false),
                "clear_first" to ToolParam(type = "boolean", description = "Whether to clear the field before typing. Default: false", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            if (!NovaAccessibilityService.isRunning()) {
                return ToolResult(false, "Accessibility service not enabled. Please enable Nova in Settings > Accessibility.")
            }

            val text = (params["text"] as? String)?.trim()
                ?: return ToolResult(false, "Text is required")
            val fieldLabel = (params["field_label"] as? String)?.trim()
            val clearFirst = (params["clear_first"] as? Boolean) ?: false

            val typed = if (!fieldLabel.isNullOrBlank()) {
                UIAutomator.typeTextByLabel(fieldLabel, text)
            } else {
                UIAutomator.typeText(text, clearFirst)
            }

            val target = fieldLabel ?: "current field"
            if (typed) {
                Log.i(TAG, "Typed text into '$target'")
                ToolResult(true, "Typed text into $target")
            } else {
                Log.w(TAG, "Failed to type into '$target'")
                ToolResult(false, "Could not find an input field to type into")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to type text", e)
            ToolResult(false, "Failed to type text: ${e.message}")
        }
    }
}
