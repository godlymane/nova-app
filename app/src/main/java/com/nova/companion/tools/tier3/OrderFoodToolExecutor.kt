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

object OrderFoodToolExecutor {

    private const val TAG = "OrderFoodTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "orderFood",
            description = "Open a food ordering app like Swiggy or Zomato to order food. Can search for a specific restaurant or food item.",
            parameters = mapOf(
                "app" to ToolParam(type = "string", description = "The food app to use: swiggy or zomato", required = true),
                "restaurant_name" to ToolParam(type = "string", description = "Restaurant to search for", required = false),
                "food_item" to ToolParam(type = "string", description = "Food item to search for", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val app = (params["app"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Please specify which app to use: swiggy or zomato")
            val restaurantName = (params["restaurant_name"] as? String)?.trim()
            val foodItem = (params["food_item"] as? String)?.trim()

            val query = restaurantName ?: foodItem

            when (app) {
                "swiggy" -> openSwiggy(context, query)
                "zomato" -> openZomato(context, query)
                else -> ToolResult(false, "I only support Swiggy and Zomato. Which one do you want?")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open food app", e)
            ToolResult(false, "Failed to open food app: ${e.message}")
        }
    }

    private fun openSwiggy(context: Context, query: String?): ToolResult {
        val packageName = "in.swiggy.android"
        val isInstalled = isAppInstalled(context, packageName)

        val intent = if (isInstalled) {
            if (query != null) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                Intent(Intent.ACTION_VIEW, Uri.parse("swiggy://explore?query=$encoded"))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("swiggy://"))
            }
        } else {
            val url = if (query != null) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                "https://www.swiggy.com/search?query=$encoded"
            } else {
                "https://www.swiggy.com"
            }
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        val message = if (query != null) "Opening Swiggy to search for $query" else "Opening Swiggy"
        Log.i(TAG, message)
        return ToolResult(true, message)
    }

    private fun openZomato(context: Context, query: String?): ToolResult {
        val packageName = "com.application.zomato"
        val isInstalled = isAppInstalled(context, packageName)

        val intent = if (isInstalled) {
            if (query != null) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                Intent(Intent.ACTION_VIEW, Uri.parse("zomato://search?q=$encoded"))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("zomato://"))
            }
        } else {
            val url = if (query != null) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                "https://www.zomato.com/search?q=$encoded"
            } else {
                "https://www.zomato.com"
            }
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        val message = if (query != null) "Opening Zomato to search for $query" else "Opening Zomato"
        Log.i(TAG, message)
        return ToolResult(true, message)
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            false
        }
    }
}
