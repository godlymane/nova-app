package com.nova.companion.tools.tier4

import android.content.Context
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object PressBackToolExecutor {

    private const val TAG = "PressBackTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "pressBack",
            description = "Press the back button to go back in the current app.",
            parameters = emptyMap(),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            if (!NovaAccessibilityService.isRunning()) {
                return ToolResult(false, "Accessibility service not enabled. Please enable Nova in Settings > Accessibility.")
            }

            UIAutomator.pressBack()
            Log.i(TAG, "Pressed back")
            ToolResult(true, "Pressed back")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to press back", e)
            ToolResult(false, "Failed to press back: ${e.message}")
        }
    }
}
