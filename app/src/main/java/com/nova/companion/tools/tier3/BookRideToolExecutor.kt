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

object BookRideToolExecutor {

    private const val TAG = "BookRideTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "bookRide",
            description = "Open a ride-hailing app like Uber or Ola to book a ride.",
            parameters = mapOf(
                "app" to ToolParam(type = "string", description = "The ride app: uber or ola", required = true),
                "destination" to ToolParam(type = "string", description = "Where to go", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val app = (params["app"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Please specify which app to use: uber or ola")
            val destination = (params["destination"] as? String)?.trim()

            when (app) {
                "uber" -> openUber(context, destination)
                "ola" -> openOla(context, destination)
                else -> ToolResult(false, "I only support Uber and Ola. Which one do you want?")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ride app", e)
            ToolResult(false, "Failed to open ride app: ${e.message}")
        }
    }

    private fun openUber(context: Context, destination: String?): ToolResult {
        val packageName = "com.ubercab"
        val isInstalled = isAppInstalled(context, packageName)

        val intent = if (isInstalled) {
            if (destination != null) {
                val encoded = URLEncoder.encode(destination, "UTF-8")
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=$encoded")
                )
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("uber://"))
            }
        } else {
            val url = if (destination != null) {
                val encoded = URLEncoder.encode(destination, "UTF-8")
                "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[formatted_address]=$encoded"
            } else {
                "https://m.uber.com/ul/?action=setPickup&pickup=my_location"
            }
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        val message = if (destination != null) "Opening Uber with destination $destination" else "Opening Uber"
        Log.i(TAG, message)
        return ToolResult(true, message)
    }

    private fun openOla(context: Context, destination: String?): ToolResult {
        val packageName = "com.olacabs.customer"
        val isInstalled = isAppInstalled(context, packageName)

        val intent = if (isInstalled) {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://book.olacabs.com"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://book.olacabs.com"))
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        val message = if (destination != null) "Opening Ola for a ride to $destination" else "Opening Ola"
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
