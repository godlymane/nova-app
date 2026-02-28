package com.nova.companion.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings

object OpenAppTool {
    private val appPackageMap = mapOf(
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "telegram" to "org.telegram.messenger",
        "discord" to "com.discord",
        "snapchat" to "com.snapchat.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "youtube" to "com.google.android.youtube",
        "youtube music" to "com.google.android.apps.youtube.music",
        "spotify" to "com.spotify.music",
        "apple music" to "com.apple.android.music",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "samsung browser" to "com.sec.android.app.sbrowser",
        "edge" to "com.microsoft.emmx",
        "reddit" to "com.reddit.frontpage",
        "pinterest" to "com.pinterest",
        "linkedin" to "com.linkedin.android",
        "uber" to "com.ubercab",
        "lyft" to "com.lyft.android",
        "uber eats" to "com.ubercab.eats",
        "doordash" to "com.dd.doordash",
        "grubhub" to "com.grubhub.android",
        "amazon" to "com.amazon.venezia",
        "amazon shopping" to "com.amazon.venezia",
        "ebay" to "com.ebay.mobile",
        "aliexpress" to "com.alibaba.aliexpresshd",
        "dropbox" to "com.dropbox.android",
        "google drive" to "com.google.android.apps.docs",
        "onedrive" to "com.microsoft.skydrive",
        "icloud" to "com.apple.iCloud",
        "paypal" to "com.paypal.android.p2pmobile",
        "venmo" to "com.venmo",
        "square cash" to "com.square.cash",
        "banking" to "com.infonow.bofa",
        "clock" to "com.google.android.deskclock",
        "weather" to "com.google.android.apps.weather",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.sec.android.gallery3d",
        "notes" to "com.google.android.keep",
        "reminders" to "com.google.android.apps.tasks",
        "tasks" to "com.google.android.apps.tasks",
        "calendar" to "com.google.android.calendar",
        "contacts" to "com.android.contacts",
        "dialer" to "com.google.android.dialer",
        "phone" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "google duo" to "com.google.android.apps.tachyon",
        "google meet" to "com.google.android.apps.tachyon",
        "zoom" to "us.zoom.videomeetings",
        "slack" to "com.Slack",
        "microsoft teams" to "com.microsoft.teams",
        "skype" to "com.skype.raider",
        "google assistant" to "com.google.android.apps.googleassistant",
        "assistant" to "com.google.android.apps.googleassistant",
        "github" to "com.github.android",
        "stackoverflow" to "com.barnacle.stackoverflow"
    )

    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "open_app",
            description = "Opens an app by name. Supports common app names like 'whatsapp', 'youtube', 'chrome', 'camera', etc.",
            parameters = mapOf(
                "app_name" to ToolParam(
                    type = "string",
                    description = "Name of the app to open (e.g., 'whatsapp', 'youtube', 'camera', 'settings')",
                    required = true
                )
            ),
            executor = { ctx, params ->
                executeOpenApp(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeOpenApp(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val appName = (params["app_name"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "App name parameter is required")

            if (appName.isEmpty()) {
                return ToolResult(false, "App name cannot be empty")
            }

            val intent = when {
                appName == "camera" -> Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appName == "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appName == "calculator" -> {
                    val packageName = "com.google.android.calculator"
                    val pm = context.packageManager
                    pm.getLaunchIntentForPackage(packageName)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    } ?: Intent("android.intent.action.MAIN").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        addCategory("android.intent.category.LAUNCHER")
                    }
                }
                else -> {
                    val packageName = appPackageMap[appName]
                    val pm = context.packageManager

                    val launchIntent = if (packageName != null) {
                        pm.getLaunchIntentForPackage(packageName)
                    } else {
                        pm.getLaunchIntentForPackage(appName)
                    }

                    launchIntent?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    } ?: run {
                        if (packageName != null && pm.getLaunchIntentForPackage(packageName) == null) {
                            return ToolResult(false, "App '$appName' is not installed on this device")
                        } else {
                            return ToolResult(false, "Could not find app '$appName'")
                        }
                    }
                }
            }

            context.startActivity(intent)
            ToolResult(true, "Opened ${appName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}")
        } catch (e: Exception) {
            ToolResult(false, "Failed to open app: ${e.message}")
        }
    }
}
