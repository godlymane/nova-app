package com.nova.companion.tools.tier2

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object TakeScreenshotToolExecutor {

    private const val TAG = "TakeScreenshotTool"

    // Will be set by the AccessibilityService when it connects
    var accessibilityService: AccessibilityService? = null

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "takeScreenshot",
            description = "Take a screenshot of the current screen.",
            parameters = emptyMap(),
            executor = { _, _ -> execute() }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(): ToolResult {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return ToolResult(false, "Screenshots require Android 9 or higher")
            }

            val service = accessibilityService
                ?: return ToolResult(
                    false,
                    "Screenshot requires Nova's Accessibility Service to be enabled. " +
                    "This will be available in a future update (Tier 4)."
                )

            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            Log.i(TAG, "Screenshot action dispatched")
            ToolResult(true, "Screenshot taken — check your photos gallery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take screenshot", e)
            ToolResult(false, "Failed to take screenshot: ${e.message}")
        }
    }
}
