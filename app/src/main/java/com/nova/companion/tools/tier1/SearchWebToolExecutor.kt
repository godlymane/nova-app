package com.nova.companion.tools.tier1

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.net.URLEncoder

object SearchWebToolExecutor {

    private const val TAG = "SearchWebTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "searchWeb",
            description = "Search the web using Google for any query.",
            parameters = mapOf(
                "query" to ToolParam(type = "string", description = "The search query", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val query = (params["query"] as? String)?.trim()
                ?: return ToolResult(false, "Search query is required")
            if (query.isBlank()) return ToolResult(false, "Search query cannot be empty")

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encodedQuery")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            Log.i(TAG, "Searching web for: $query")
            ToolResult(true, "Searching the web for: $query")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search web", e)
            ToolResult(false, "Failed to search the web: ${e.message}")
        }
    }
}