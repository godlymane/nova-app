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

object ScrollScreenToolExecutor {

    private const val TAG = "ScrollScreenTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "scrollScreen",
            description = "Scroll the current screen in a direction.",
            parameters = mapOf(
                "direction" to ToolParam(type = "string", description = "Direction to scroll: up, down, left, or right", required = true),
                "times" to ToolParam(type = "integer", description = "Number of times to scroll. Default: 1", required = false)
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

            val direction = (params["direction"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Direction is required")
            val times = ((params["times"] as? Number)?.toInt()) ?: 1

            if (direction !in listOf("up", "down", "left", "right")) {
                return ToolResult(false, "Invalid direction. Use: up, down, left, or right")
            }

            var success = true
            for (i in 1..times) {
                val scrolled = UIAutomator.scroll(direction)
                if (!scrolled) success = false
                if (i < times) delay(300)
            }

            Log.i(TAG, "Scrolled $direction $times time(s)")
            ToolResult(success, "Scrolled $direction $times time(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scroll", e)
            ToolResult(false, "Failed to scroll: ${e.message}")
        }
    }
}
