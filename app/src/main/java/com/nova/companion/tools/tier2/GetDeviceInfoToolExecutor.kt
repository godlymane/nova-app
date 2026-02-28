package com.nova.companion.tools.tier2

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object GetDeviceInfoToolExecutor {

    private const val TAG = "GetDeviceInfoTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "getDeviceInfo",
            description = "Get device information including model, Android version, RAM, storage, and uptime.",
            parameters = emptyMap(),
            executor = { ctx, _ -> execute(ctx) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context): ToolResult {
        return try {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            val androidVersion = Build.VERSION.RELEASE
            val apiLevel = Build.VERSION.SDK_INT

            // RAM info
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalRam = formatBytes(memInfo.totalMem)
            val availableRam = formatBytes(memInfo.availMem)

            // Storage info
            val statFs = StatFs(Environment.getDataDirectory().path)
            val totalStorage = formatBytes(statFs.totalBytes)
            val availableStorage = formatBytes(statFs.availableBytes)

            // Screen resolution
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            val screenRes = "${displayMetrics.widthPixels} x ${displayMetrics.heightPixels}"

            // Uptime
            val uptimeMs = SystemClock.elapsedRealtime()
            val uptimeHours = uptimeMs / (1000 * 60 * 60)
            val uptimeMinutes = (uptimeMs / (1000 * 60)) % 60

            val info = buildString {
                append("Device: $manufacturer $model, Android $androidVersion (API $apiLevel). ")
                append("RAM: $availableRam available / $totalRam total. ")
                append("Storage: $availableStorage available / $totalStorage total. ")
                append("Screen: $screenRes. ")
                append("Uptime: ${uptimeHours}h ${uptimeMinutes}m")
            }

            Log.i(TAG, info)
            ToolResult(true, info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device info", e)
            ToolResult(false, "Failed to get device info: ${e.message}")
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format("%.1f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format("%.0f MB", mb)
        }
    }
}
