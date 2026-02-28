package com.nova.companion.tools.tier2

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object SetBrightnessToolExecutor {

    private const val TAG = "SetBrightnessTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "setBrightness",
            description = "Set the screen brightness level.",
            parameters = mapOf(
                "level" to ToolParam(
                    type = "number",
                    description = "Brightness level from 0 to 100 as a percentage",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val level = (params["level"] as? Number)?.toInt()
                ?: return ToolResult(false, "Brightness level is required (0-100)")

            if (level !in 0..100) {
                return ToolResult(false, "Brightness level must be between 0 and 100")
            }

            if (!Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.i(TAG, "Write settings permission not granted, opening settings")
                return ToolResult(false, "You need to grant Nova permission to change system settings. Opening settings now.")
            }

            val resolver = context.contentResolver

            // Disable auto-brightness first
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            // Map 0-100 to 0-255
            val brightness = (level * 255) / 100
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness)

            Log.i(TAG, "Brightness set to $level% (raw: $brightness)")
            ToolResult(true, "Brightness set to $level%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
            ToolResult(false, "Failed to set brightness: ${e.message}")
        }
    }
}
