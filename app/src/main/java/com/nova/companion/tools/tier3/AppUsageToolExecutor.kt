package com.nova.companion.tools.tier3

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.*

object AppUsageToolExecutor {

    private const val TAG = "AppUsageTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "appUsage",
            description = "Get screen time and app usage stats. Shows how much time spent on each app today/this week. Actions: 'today' for daily breakdown, 'week' for weekly, 'app' for specific app usage.",
            parameters = mapOf(
                "action" to ToolParam("string", "Action: today, week, app", true),
                "app" to ToolParam("string", "App name (for 'app' action)", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "today"

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return ToolResult(false, "Usage stats not available on this device.")

        // Check if we have permission
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        cal.add(Calendar.MINUTE, -1)
        val testStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, end)
        if (testStats.isNullOrEmpty()) {
            return ToolResult(false, "App usage permission not granted. Go to Settings > Apps > Special app access > Usage access and enable Nova.")
        }

        return try {
            when (action) {
                "today" -> getTodayUsage(context, usm)
                "week" -> getWeekUsage(context, usm)
                "app" -> getAppUsage(context, usm, params["app"] as? String ?: "")
                else -> ToolResult(false, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "App usage error", e)
            ToolResult(false, "Error reading usage stats: ${e.message}")
        }
    }

    private fun getTodayUsage(context: Context, usm: UsageStatsManager): ToolResult {
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            ?.filter { it.totalTimeInForeground > 60_000 } // min 1 minute
            ?.sortedByDescending { it.totalTimeInForeground }
            ?: return ToolResult(true, "No usage data for today yet.")

        val totalMs = stats.sumOf { it.totalTimeInForeground }
        val pm = context.packageManager

        return ToolResult(true, buildString {
            append("Screen time today: ${formatDuration(totalMs)}\n\n")
            append("Top apps:\n")

            stats.take(10).forEach { s ->
                val appName = getAppName(pm, s.packageName)
                val time = formatDuration(s.totalTimeInForeground)
                val pct = (s.totalTimeInForeground * 100 / totalMs).toInt()
                val bar = "█".repeat((pct / 5).coerceIn(0, 20))
                append("  $appName".padEnd(22))
                append("$time".padStart(8))
                append("  $bar $pct%\n")
            }

            // Categories
            val social = stats.filter { isSocialApp(it.packageName) }.sumOf { it.totalTimeInForeground }
            val productivity = stats.filter { isProductivityApp(it.packageName) }.sumOf { it.totalTimeInForeground }
            val entertainment = stats.filter { isEntertainmentApp(it.packageName) }.sumOf { it.totalTimeInForeground }

            if (social > 0 || productivity > 0 || entertainment > 0) {
                append("\nCategories:\n")
                if (social > 0) append("  Social: ${formatDuration(social)}\n")
                if (productivity > 0) append("  Productivity: ${formatDuration(productivity)}\n")
                if (entertainment > 0) append("  Entertainment: ${formatDuration(entertainment)}\n")
            }
        })
    }

    private fun getWeekUsage(context: Context, usm: UsageStatsManager): ToolResult {
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, -7)
        val start = cal.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, start, end)
            ?.filter { it.totalTimeInForeground > 300_000 } // min 5 min
            ?.sortedByDescending { it.totalTimeInForeground }
            ?: return ToolResult(true, "No usage data for this week.")

        val totalMs = stats.sumOf { it.totalTimeInForeground }
        val pm = context.packageManager

        return ToolResult(true, buildString {
            append("Screen time this week: ${formatDuration(totalMs)}\n")
            append("Daily avg: ${formatDuration(totalMs / 7)}\n\n")
            append("Top apps:\n")

            stats.take(10).forEach { s ->
                val appName = getAppName(pm, s.packageName)
                val time = formatDuration(s.totalTimeInForeground)
                append("  $appName".padEnd(22))
                append("$time\n")
            }
        })
    }

    private fun getAppUsage(context: Context, usm: UsageStatsManager, appQuery: String): ToolResult {
        if (appQuery.isBlank()) return ToolResult(false, "App name required.")

        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, -7)
        val start = cal.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        val pm = context.packageManager
        val query = appQuery.lowercase()

        // Find matching app
        val matching = stats
            ?.filter { getAppName(pm, it.packageName).lowercase().contains(query) || it.packageName.lowercase().contains(query) }
            ?.groupBy { it.packageName }
            ?.maxByOrNull { (_, entries) -> entries.sumOf { it.totalTimeInForeground } }

        if (matching == null) return ToolResult(true, "No usage found for '$appQuery'.")

        val (pkg, entries) = matching
        val appName = getAppName(pm, pkg)
        val total = entries.sumOf { it.totalTimeInForeground }
        val df = SimpleDateFormat("EEE", Locale.getDefault())

        return ToolResult(true, buildString {
            append("$appName usage (last 7 days):\n")
            append("Total: ${formatDuration(total)}\n")
            append("Daily avg: ${formatDuration(total / 7)}\n\n")

            // Daily breakdown
            val byDay = entries.sortedBy { it.firstTimeStamp }
            byDay.forEach { e ->
                val day = df.format(Date(e.firstTimeStamp))
                val time = formatDuration(e.totalTimeInForeground)
                val bar = "█".repeat((e.totalTimeInForeground / 600_000).toInt().coerceIn(0, 20))
                append("  $day $bar $time\n")
            }
        })
    }

    // --- Helpers ---

    private fun getAppName(pm: PackageManager, packageName: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun formatDuration(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    private fun isSocialApp(pkg: String): Boolean {
        val social = listOf("instagram", "whatsapp", "facebook", "twitter", "snapchat", "telegram", "tiktok", "reddit", "linkedin", "threads")
        return social.any { pkg.lowercase().contains(it) }
    }

    private fun isProductivityApp(pkg: String): Boolean {
        val prod = listOf("gmail", "calendar", "docs", "sheets", "slack", "notion", "drive", "outlook", "teams", "zoom", "meet")
        return prod.any { pkg.lowercase().contains(it) }
    }

    private fun isEntertainmentApp(pkg: String): Boolean {
        val ent = listOf("youtube", "netflix", "spotify", "prime", "hotstar", "jio", "game", "twitch", "voot")
        return ent.any { pkg.lowercase().contains(it) }
    }
}
