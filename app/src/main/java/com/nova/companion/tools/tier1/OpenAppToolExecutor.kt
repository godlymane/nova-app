package com.nova.companion.tools.tier1

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object OpenAppToolExecutor {

    private const val TAG = "OpenAppToolExec"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "openApp",
            description = "Open an installed app on the device by its name.",
            parameters = mapOf(
                "app_name" to ToolParam(type = "string", description = "The name of the app to open (e.g. Instagram, YouTube, Chrome, Spotify)", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val appName = (params["app_name"] as? String)?.trim()
                ?: return ToolResult(false, "App name is required")
            if (appName.isBlank()) return ToolResult(false, "App name cannot be empty")

            val pm = context.packageManager
            val searchTerm = appName.lowercase()
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            var bestMatch: ApplicationInfo? = null
            var bestMatchLabel: String? = null

            for (appInfo in installedApps) {
                val label = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { continue }
                val labelLower = label.lowercase()

                if (labelLower == searchTerm) {
                    bestMatch = appInfo
                    bestMatchLabel = label
                    break
                }
                if (bestMatch == null && (labelLower.contains(searchTerm) || searchTerm.contains(labelLower))) {
                    bestMatch = appInfo
                    bestMatchLabel = label
                }
            }

            if (bestMatch == null || bestMatchLabel == null)
                return ToolResult(false, "I couldn't find an app called $appName on your phone")

            val launchIntent = pm.getLaunchIntentForPackage(bestMatch.packageName)
                ?: return ToolResult(false, "I found $bestMatchLabel but it can't be launched")

            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)

            Log.i(TAG, "Opened $bestMatchLabel (${bestMatch.packageName})")
            ToolResult(true, "Opening $bestMatchLabel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app", e)
            ToolResult(false, "Failed to open app: ${e.message}")
        }
    }
}