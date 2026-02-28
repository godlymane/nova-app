package com.nova.companion.tools.tier4

import android.content.Context
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object WaitForElementToolExecutor {

    private const val TAG = "WaitForElementTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "waitForElement",
            description = "Wait for a specific text or element to appear on screen. Useful for waiting for pages to load before taking action.",
            parameters = mapOf(
                "text" to ToolParam(type = "string", description = "Text to wait for on screen", required = true),
                "timeout_seconds" to ToolParam(type = "integer", description = "How long to wait in seconds. Default: 10", required = false)
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
            val timeoutSeconds = ((params["timeout_seconds"] as? Number)?.toInt()) ?: 10

            val found = UIAutomator.waitForText(text, timeoutMs = timeoutSeconds * 1000L)

            if (found) {
                Log.i(TAG, "Found '$text' on screen")
                ToolResult(true, "Found '$text' on screen")
            } else {
                Log.w(TAG, "Timed out waiting for '$text'")
                ToolResult(false, "Timed out waiting for '$text' after $timeoutSeconds seconds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed waiting for element", e)
            ToolResult(false, "Failed waiting for element: ${e.message}")
        }
    }
}
