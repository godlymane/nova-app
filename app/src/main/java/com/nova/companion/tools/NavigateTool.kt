package com.nova.companion.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

object NavigateTool {
    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "navigate",
            description = "Opens Google Maps navigation to a specified destination.",
            parameters = mapOf(
                "destination" to ToolParam(
                    type = "string",
                    description = "The destination address or place name (e.g., 'Central Park', '123 Main St, New York')",
                    required = true
                )
            ),
            executor = { ctx, params ->
                executeNavigate(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeNavigate(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val destination = (params["destination"] as? String)?.trim()
                ?: return ToolResult(false, "Destination parameter is required")

            if (destination.isEmpty()) {
                return ToolResult(false, "Destination cannot be empty")
            }

            val encodedDestination = Uri.encode(destination)
            val navigationUri = "google.navigation:q=$encodedDestination"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.parse(navigationUri)
            }

            try {
                context.startActivity(intent)
                ToolResult(true, "Opening navigation to $destination")
            } catch (e: Exception) {
                val fallbackUri = "geo:0,0?q=$encodedDestination"
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    data = Uri.parse(fallbackUri)
                }
                context.startActivity(fallbackIntent)
                ToolResult(true, "Opening maps to $destination")
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to open navigation: ${e.message}")
        }
    }
}
