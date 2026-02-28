package com.nova.companion.tools.tier4

import android.content.Context
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.delay

object TapOnScreenToolExecutor {

    private const val TAG = "TapOnScreenTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "tapOnScreen",
            description = "Tap on a UI element on the current screen by its visible text or content description. Use this to press buttons, select items, or interact with any app.",
            parameters = mapOf(
                "text" to ToolParam(type = "string", description = "Visible text of the element to tap", required = false),
                "description" to ToolParam(type = "string", description = "Content description of the element to tap", required = false)
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
            val description = (params["description"] as? String)?.trim()

            if (text.isNullOrBlank() && description.isNullOrBlank()) {
                return ToolResult(false, "Either text or description must be provided")
            }

            val tapped = if (!text.isNullOrBlank()) {
                UIAutomator.tapByText(text)
            } else {
                UIAutomator.tapByDescription(description!!)
            }

            delay(500)

            val target = text ?: description
            if (tapped) {
                Log.i(TAG, "Tapped on '$target'")
                ToolResult(true, "Tapped on $target")
            } else {
                Log.w(TAG, "Could not find '$target' on screen")
                ToolResult(false, "Could not find $target on screen")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to tap", e)
            ToolResult(false, "Failed to tap: ${e.message}")
        }
    }
}
