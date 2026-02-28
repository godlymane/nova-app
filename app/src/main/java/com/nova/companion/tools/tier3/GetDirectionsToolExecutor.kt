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

object GetDirectionsToolExecutor {

    private const val TAG = "GetDirectionsTool"

    private val MODE_MAP = mapOf(
        "driving" to "d",
        "walking" to "w",
        "transit" to "r",
        "bicycling" to "b"
    )

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "getDirections",
            description = "Open Google Maps with directions to a destination.",
            parameters = mapOf(
                "destination" to ToolParam(type = "string", description = "Where to navigate to", required = true),
                "mode" to ToolParam(type = "string", description = "Travel mode: driving, walking, transit, or bicycling", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val destination = (params["destination"] as? String)?.trim()
                ?: return ToolResult(false, "Destination is required")
            val mode = (params["mode"] as? String)?.trim()?.lowercase() ?: "driving"
            val modeChar = MODE_MAP[mode] ?: "d"

            val mapsPackage = "com.google.android.apps.maps"
            val encodedDest = URLEncoder.encode(destination, "UTF-8")

            val intent = if (isAppInstalled(context, mapsPackage)) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=$encodedDest&mode=$modeChar")
                ).apply {
                    setPackage(mapsPackage)
                }
            } else {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encodedDest&travelmode=$mode")
                )
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            val message = "Getting directions to $destination by $mode"
            Log.i(TAG, message)
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open directions", e)
            ToolResult(false, "Failed to get directions: ${e.message}")
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
