package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object ShareContentToolExecutor {

    private const val TAG = "ShareContentTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "shareContent",
            description = "Share text or a URL using the Android share menu.",
            parameters = mapOf(
                "text" to ToolParam(type = "string", description = "The text or URL to share", required = true),
                "title" to ToolParam(type = "string", description = "Title for the share dialog", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val text = (params["text"] as? String)?.trim()
                ?: return ToolResult(false, "Text to share is required")
            val title = (params["title"] as? String)?.trim()

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooserIntent = Intent.createChooser(shareIntent, title ?: "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooserIntent)

            val message = "Opening share menu"
            Log.i(TAG, "$message — sharing: ${text.take(50)}")
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open share sheet", e)
            ToolResult(false, "Failed to share: ${e.message}")
        }
    }
}
