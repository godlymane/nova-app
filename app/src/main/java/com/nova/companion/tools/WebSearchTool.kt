package com.nova.companion.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

object WebSearchTool {
    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "web_search",
            description = "Performs a web search and opens the results in the default browser.",
            parameters = mapOf(
                "query" to ToolParam(
                    type = "string",
                    description = "The search query to perform on Google",
                    required = true
                )
            ),
            executor = { ctx, params ->
                executeWebSearch(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeWebSearch(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val query = (params["query"] as? String)?.trim()
                ?: return ToolResult(false, "Search query is required")

            if (query.isEmpty()) {
                return ToolResult(false, "Search query cannot be empty")
            }

            val encodedQuery = Uri.encode(query)
            val searchUrl = "https://www.google.com/search?q=$encodedQuery"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.parse(searchUrl)
            }

            context.startActivity(intent)
            ToolResult(true, "Searching for '$query' in Google", mapOf("url" to searchUrl))
        } catch (e: Exception) {
            ToolResult(false, "Failed to perform search: ${e.message}")
        }
    }
}
