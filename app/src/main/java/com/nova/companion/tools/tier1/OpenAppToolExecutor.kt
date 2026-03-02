package com.nova.companion.tools.tier1

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object OpenAppToolExecutor {

    private const val TAG = "OpenAppToolExec"

    /** Fast-path: common app names → package names (avoids fuzzy-match mistakes) */
    private val KNOWN_APPS = mapOf(
        "instagram" to "com.instagram.android",
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "snapchat" to "com.snapchat.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "telegram" to "org.telegram.messenger",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "netflix" to "com.netflix.mediaclient",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "camera" to "com.android.camera",
        "settings" to "com.android.settings",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "photos" to "com.google.android.apps.photos",
        "google photos" to "com.google.android.apps.photos",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts",
        "messages" to "com.google.android.apps.messaging",
        "files" to "com.google.android.apps.nbu.files",
        "play store" to "com.android.vending",
        "google play" to "com.android.vending",
        "uber" to "com.ubercab",
        "uber eats" to "com.ubercab.eats",
        "amazon" to "com.amazon.mShop.android.shopping",
        "reddit" to "com.reddit.frontpage",
        "discord" to "com.discord",
        "pinterest" to "com.pinterest",
        "linkedin" to "com.linkedin.android",
        "twitch" to "tv.twitch.android.app",
        "zoom" to "us.zoom.videomeetings",
        "teams" to "com.microsoft.teams",
        "slack" to "com.Slack",
        "notion" to "notion.id",
        "notes" to "com.google.android.keep",
        "keep" to "com.google.android.keep",
        "drive" to "com.google.android.apps.docs",
        "google drive" to "com.google.android.apps.docs",
        "docs" to "com.google.android.apps.docs.editors.docs",
        "sheets" to "com.google.android.apps.docs.editors.sheets",
        "slides" to "com.google.android.apps.docs.editors.slides",
        "weather" to "com.google.android.apps.weather",
        "music" to "com.google.android.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "yt music" to "com.google.android.apps.youtube.music",
    )

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

            // Strategy 1: Known app fast-path — most reliable
            val knownPackage = KNOWN_APPS[searchTerm]
            if (knownPackage != null) {
                val launched = tryLaunch(context, pm, knownPackage, appName)
                if (launched != null) return launched
                Log.d(TAG, "Known package $knownPackage not installed, falling back to search")
            }

            // Strategy 2: Fuzzy match by app label
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
            var bestMatch: ApplicationInfo? = null
            var bestMatchLabel: String? = null
            var exactMatch = false

            for (appInfo in installedApps) {
                val label = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { continue }
                val labelLower = label.lowercase()

                // Exact match — use immediately
                if (labelLower == searchTerm) {
                    bestMatch = appInfo
                    bestMatchLabel = label
                    exactMatch = true
                    break
                }
                // Partial match — prefer shorter labels (closer match)
                if (labelLower.contains(searchTerm) || searchTerm.contains(labelLower)) {
                    if (bestMatch == null || label.length < (bestMatchLabel?.length ?: Int.MAX_VALUE)) {
                        bestMatch = appInfo
                        bestMatchLabel = label
                    }
                }
            }

            if (bestMatch == null || bestMatchLabel == null)
                return ToolResult(false, "I couldn't find an app called $appName on your phone")

            Log.i(TAG, "Matched '$appName' → $bestMatchLabel (${bestMatch.packageName}), exact=$exactMatch")

            val launched = tryLaunch(context, pm, bestMatch.packageName, bestMatchLabel)
            if (launched != null) return launched

            ToolResult(false, "I found $bestMatchLabel but it can't be launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app", e)
            ToolResult(false, "Failed to open app: ${e.message}")
        }
    }

    /**
     * Actually launch the app. Returns ToolResult on success, null if the package
     * has no launchable activity.
     */
    private fun tryLaunch(context: Context, pm: PackageManager, packageName: String, displayName: String): ToolResult? {
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return null

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        context.startActivity(launchIntent)
        Log.i(TAG, "Launched $displayName ($packageName)")
        return ToolResult(true, "Opening $displayName")
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }
}