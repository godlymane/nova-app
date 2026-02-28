package com.nova.companion.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.view.KeyEvent

object MediaControlTool {
    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "media_control",
            description = "Controls media playback with actions like play, pause, next, and previous.",
            parameters = mapOf(
                "action" to ToolParam(
                    type = "string",
                    description = "Media control action: 'play', 'pause', 'play_pause', 'next', 'skip', 'previous', 'stop'",
                    required = true
                )
            ),
            executor = { ctx, params ->
                executeMediaControl(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeMediaControl(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val action = (params["action"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Action parameter is required")

            if (action.isEmpty()) {
                return ToolResult(false, "Action cannot be empty")
            }

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val keyCode = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev", "last" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                "fast_forward", "forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
                else -> return ToolResult(false, "Unsupported action: '$action'. Supported: play, pause, play_pause, next, skip, previous, stop, fast_forward, rewind")
            }

            dispatchMediaKeyEvent(audioManager, keyCode)

            val actionDisplay = when (action) {
                "play" -> "Playing"
                "pause" -> "Pausing"
                "play_pause", "toggle" -> "Toggling play/pause"
                "next", "skip" -> "Skipping to next"
                "previous", "prev", "last" -> "Playing previous"
                "stop" -> "Stopping"
                "fast_forward", "forward" -> "Fast forwarding"
                "rewind" -> "Rewinding"
                else -> "Controlling media"
            }

            ToolResult(true, "$actionDisplay media playback")
        } catch (e: Exception) {
            ToolResult(false, "Failed to control media: ${e.message}")
        }
    }

    private fun dispatchMediaKeyEvent(audioManager: AudioManager, keyCode: Int) {
        try {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)

            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
        } catch (e: Exception) {
            try {
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                dispatchKeyEventViaIntent(downEvent)
                dispatchKeyEventViaIntent(upEvent)
            } catch (fallbackException: Exception) {
                throw Exception("Could not dispatch media key event: ${e.message}")
            }
        }
    }

    private fun dispatchKeyEventViaIntent(keyEvent: KeyEvent) {
        try {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        } catch (e: Exception) {
            throw Exception("Could not dispatch key event: ${e.message}")
        }
    }
}
