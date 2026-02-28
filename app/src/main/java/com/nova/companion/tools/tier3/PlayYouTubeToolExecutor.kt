package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.net.URLEncoder

object PlayYouTubeToolExecutor {

    private const val TAG = "PlayYouTubeTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "playYouTube",
            description = "Search for a video on YouTube.",
            parameters = mapOf(
                "query" to ToolParam(type = "string", description = "What to search for on YouTube", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val query = (params["query"] as? String)?.trim()
                ?: return ToolResult(false, "Please tell me what to search for on YouTube")

            val encoded = URLEncoder.encode(query, "UTF-8")
            val youtubePackage = "com.google.android.youtube"

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            )

            if (isAppInstalled(context, youtubePackage)) {
                intent.setPackage(youtubePackage)
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            val message = "Searching YouTube for $query"
            Log.i(TAG, message)
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open YouTube", e)
            ToolResult(false, "Failed to open YouTube: ${e.message}")
        }
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            false
        }
    }
}
