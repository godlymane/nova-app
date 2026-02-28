package com.nova.companion.brain.context.collectors

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Process
import com.nova.companion.brain.context.ContextSnapshot

object DeviceStateCollector {

    fun collect(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        var updatedSnapshot = snapshot

        // Headphones/audio state (no permission needed)
        updatedSnapshot = collectAudioState(context, updatedSnapshot)

        // Screen time / foreground app (needs PACKAGE_USAGE_STATS, graceful if denied)
        updatedSnapshot = collectUsageStats(context, updatedSnapshot)

        return updatedSnapshot
    }

    private fun collectAudioState(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val isHeadphones = audioManager.isWiredHeadsetOn ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS
                ).any { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO })

            snapshot.copy(isHeadphonesConnected = isHeadphones)
        } catch (e: Exception) {
            snapshot
        }
    }

    private fun collectUsageStats(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        return try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }

            if (mode != AppOpsManager.MODE_ALLOWED) return snapshot

            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val startOfDay = now - (now % (24 * 60 * 60 * 1000L))

            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startOfDay, now
            )

            if (stats.isNullOrEmpty()) return snapshot

            // Foreground app = most recently used
            val foregroundApp = stats.maxByOrNull { it.lastTimeUsed }?.packageName

            // Total screen time = sum of totalTimeInForeground (in ms) → convert to minutes
            val totalScreenTimeMinutes = stats.sumOf { it.totalTimeInForeground } / 1000 / 60

            snapshot.copy(
                currentApp = foregroundApp,
                screenTimeToday = totalScreenTimeMinutes
            )
        } catch (e: Exception) {
            snapshot
        }
    }
}
