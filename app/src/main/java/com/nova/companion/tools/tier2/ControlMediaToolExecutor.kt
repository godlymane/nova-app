package com.nova.companion.tools.tier2

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object ControlMediaToolExecutor {

    private const val TAG = "ControlMediaTool"

    private val ACTION_MAP = mapOf(
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "toggle" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS
    )

    private val ACTION_LABELS = mapOf(
        "play" to "Media playing",
        "pause" to "Media paused",
        "toggle" to "Media play/pause toggled",
        "next" to "Skipped to next track",
        "previous" to "Went to previous track"
    )

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "controlMedia",
            description = "Control media playback — play, pause, skip to next, or go to previous track.",
            parameters = mapOf(
                "action" to ToolParam(
                    type = "string",
                    description = "The media action: play, pause, toggle, next, or previous",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val action = (params["action"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Media action is required (play, pause, toggle, next, or previous)")

            val keyCode = ACTION_MAP[action]
                ?: return ToolResult(false, "Invalid media action '$action'. Use: play, pause, toggle, next, or previous")

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Dispatch both ACTION_DOWN and ACTION_UP events
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            val label = ACTION_LABELS[action] ?: "Media $action"
            Log.i(TAG, label)
            ToolResult(true, label)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to control media", e)
            ToolResult(false, "Failed to control media: ${e.message}")
        }
    }
}
