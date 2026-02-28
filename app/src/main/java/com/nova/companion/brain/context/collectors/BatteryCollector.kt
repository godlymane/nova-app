package com.nova.companion.brain.context.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.nova.companion.brain.context.ContextSnapshot

object BatteryCollector {
    fun collect(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale) else -1

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            snapshot.copy(
                batteryLevel = batteryPct,
                isCharging = isCharging
            )
        } catch (e: Exception) {
            snapshot // Return unchanged if anything fails
        }
    }
}
