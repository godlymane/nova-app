package com.nova.companion.tools.tier4

import android.content.Context
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.ScreenReader
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object ReadScreenToolExecutor {

    private const val TAG = "ReadScreenTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "readScreen",
            description = "Read what's currently displayed on the screen. Returns a list of visible text and interactive elements. Use this to understand what app is open and what actions are available before tapping or typing.",
            parameters = mapOf(
                "compact" to ToolParam(type = "boolean", description = "If true, only return interactive elements like buttons and input fields", required = false)
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

            val compact = (params["compact"] as? Boolean) ?: false
            val screenContent = if (compact) {
                ScreenReader.readScreenCompact()
            } else {
                ScreenReader.readScreen()
            }

            Log.i(TAG, "Read screen (compact=$compact)")
            ToolResult(true, screenContent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read screen", e)
            ToolResult(false, "Failed to read screen: ${e.message}")
        }
    }
}
