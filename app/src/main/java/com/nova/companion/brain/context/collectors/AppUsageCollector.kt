package com.nova.companion.brain.context.collectors

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.nova.companion.brain.context.ContextSnapshot

/**
 * Supplementary collector for recent app usage patterns.
 * Returns top apps used in the last hour (useful for context window).
 * Requires PACKAGE_USAGE_STATS — graceful fallback if denied.
 */
object AppUsageCollector {

    fun getRecentApps(context: Context, limitMinutes: Int = 60): List<String> {
        return try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            if (mode != AppOpsManager.MODE_ALLOWED) return emptyList()

            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val since = now - limitMinutes * 60 * 1000L

            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, since, now)
                ?.filter { it.totalTimeInForeground > 0 }
                ?.sortedByDescending { it.lastTimeUsed }
                ?.take(5)
                ?.map { it.packageName }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
