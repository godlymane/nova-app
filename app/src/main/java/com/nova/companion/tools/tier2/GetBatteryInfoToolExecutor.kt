package com.nova.companion.tools.tier2

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object GetBatteryInfoToolExecutor {

    private const val TAG = "GetBatteryInfoTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "getBatteryInfo",
            description = "Get current battery level, charging status, and temperature.",
            parameters = emptyMap(),
            executor = { ctx, _ -> execute(ctx) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context): ToolResult {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }

            if (batteryStatus == null) {
                return ToolResult(false, "Could not read battery information")
            }

            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val statusText = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            }

            val plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val pluggedText = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless charger"
                else -> "not plugged in"
            }

            val temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val tempCelsius = if (temperature > 0) temperature / 10.0 else null

            val info = buildString {
                append("Battery is at $batteryPct%, $statusText")
                if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                    append(" via $pluggedText")
                }
                if (tempCelsius != null) {
                    append(". Temperature: ${String.format("%.1f", tempCelsius)}°C")
                }
            }

            Log.i(TAG, info)
            ToolResult(true, info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery info", e)
            ToolResult(false, "Failed to get battery info: ${e.message}")
        }
    }
}
