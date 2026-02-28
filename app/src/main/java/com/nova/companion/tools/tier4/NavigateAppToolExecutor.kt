package com.nova.companion.tools.tier4

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.delay

object NavigateAppToolExecutor {

    private const val TAG = "NavigateAppTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "navigateApp",
            description = "Open an app and navigate to a specific section within it. More powerful than openApp because it can also tap to navigate after opening.",
            parameters = mapOf(
                "app_name" to ToolParam(type = "string", description = "The app to open", required = true),
                "target" to ToolParam(type = "string", description = "Section to navigate to within the app (e.g. Settings, Profile, Search)", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val appName = (params["app_name"] as? String)?.trim()
                ?: return ToolResult(false, "App name is required")
            val target = (params["target"] as? String)?.trim()

            // Launch the app (same logic as Tier 1 OpenApp)
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

            if (bestMatch == null || bestMatchLabel == null) {
                return ToolResult(false, "I couldn't find an app called $appName on your phone")
            }

            val launchIntent = pm.getLaunchIntentForPackage(bestMatch.packageName)
                ?: return ToolResult(false, "I found $bestMatchLabel but it can't be launched")

            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)

            // Wait for app to load
            delay(2000)

            if (target.isNullOrBlank()) {
                Log.i(TAG, "Opened $bestMatchLabel")
                return ToolResult(true, "Opened $bestMatchLabel")
            }

            // If accessibility service is available, try to navigate
            if (!NovaAccessibilityService.isRunning()) {
                Log.i(TAG, "Opened $bestMatchLabel (accessibility not enabled for navigation)")
                return ToolResult(true, "Opened $bestMatchLabel but accessibility service not enabled for navigation to $target")
            }

            // Try to find and tap the target
            var navigated = UIAutomator.tapByText(target)
            if (!navigated) {
                navigated = UIAutomator.tapByDescription(target)
            }

            // If not found, try scrolling down once and searching again
            if (!navigated) {
                UIAutomator.scroll("down")
                delay(500)
                navigated = UIAutomator.tapByText(target)
                if (!navigated) {
                    navigated = UIAutomator.tapByDescription(target)
                }
            }

            if (navigated) {
                Log.i(TAG, "Opened $bestMatchLabel and navigated to $target")
                ToolResult(true, "Opened $bestMatchLabel and navigated to $target")
            } else {
                Log.w(TAG, "Opened $bestMatchLabel but couldn't find $target")
                ToolResult(true, "Opened $bestMatchLabel but couldn't find '$target' on screen")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate app", e)
            ToolResult(false, "Failed to navigate app: ${e.message}")
        }
    }
}
