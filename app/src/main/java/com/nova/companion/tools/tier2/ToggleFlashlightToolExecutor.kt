package com.nova.companion.tools.tier2

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object ToggleFlashlightToolExecutor {

    private const val TAG = "ToggleFlashlightTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "toggleFlashlight",
            description = "Turn the device flashlight on or off.",
            parameters = mapOf(
                "enabled" to ToolParam(
                    type = "boolean",
                    description = "Whether to turn the flashlight on (true) or off (false)",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val enabled = params["enabled"] as? Boolean
                ?: return ToolResult(false, "The enabled parameter is required (true or false)")

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolResult(false, "No camera found on this device")

            cameraManager.setTorchMode(cameraId, enabled)

            Log.i(TAG, "Flashlight turned ${if (enabled) "on" else "off"}")
            ToolResult(true, "Flashlight turned ${if (enabled) "on" else "off"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            ToolResult(false, "Failed to toggle flashlight: ${e.message}")
        }
    }
}
