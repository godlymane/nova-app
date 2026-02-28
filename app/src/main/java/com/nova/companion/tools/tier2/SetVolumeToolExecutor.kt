package com.nova.companion.tools.tier2

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object SetVolumeToolExecutor {

    private const val TAG = "SetVolumeTool"

    private val STREAM_MAP = mapOf(
        "media" to AudioManager.STREAM_MUSIC,
        "ringtone" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION
    )

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "setVolume",
            description = "Set the device volume for a specific audio stream.",
            parameters = mapOf(
                "volume_type" to ToolParam(
                    type = "string",
                    description = "The type of volume to set: media, ringtone, alarm, or notification",
                    required = true
                ),
                "level" to ToolParam(
                    type = "number",
                    description = "Volume level from 0 to 100 as a percentage",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val volumeType = (params["volume_type"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Volume type is required (media, ringtone, alarm, or notification)")
            val level = (params["level"] as? Number)?.toInt()
                ?: return ToolResult(false, "Volume level is required (0-100)")

            if (level !in 0..100) {
                return ToolResult(false, "Volume level must be between 0 and 100")
            }

            val stream = STREAM_MAP[volumeType]
                ?: return ToolResult(false, "Invalid volume type '$volumeType'. Use: media, ringtone, alarm, or notification")

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(stream)
            val vol = (level * maxVol) / 100

            audioManager.setStreamVolume(stream, vol, 0)

            val typeName = volumeType.replaceFirstChar { it.uppercase() }
            Log.i(TAG, "$typeName volume set to $level% (raw: $vol/$maxVol)")
            ToolResult(true, "$typeName volume set to $level%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            ToolResult(false, "Failed to set volume: ${e.message}")
        }
    }
}
