package com.nova.companion.tools.tier1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object OpenUrlToolExecutor {

    private const val TAG = "OpenUrlTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "openUrl",
            description = "Open a specific URL in the device's web browser.",
            parameters = mapOf(
                "url" to ToolParam(type = "string", description = "The URL to open", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            var url = (params["url"] as? String)?.trim()
                ?: return ToolResult(false, "URL is required")
            if (url.isBlank()) return ToolResult(false, "URL cannot be empty")

            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            Log.i(TAG, "Opening URL: $url")
            ToolResult(true, "Opening $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL", e)
            ToolResult(false, "Failed to open URL: ${e.message}")
        }
    }
}