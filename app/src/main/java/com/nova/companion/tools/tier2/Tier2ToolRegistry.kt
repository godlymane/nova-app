package com.nova.companion.tools.tier2

import com.nova.companion.tools.ToolRegistry

object Tier2ToolRegistry {

    fun registerAll(registry: ToolRegistry) {
        ToggleWifiToolExecutor.register(registry)
        ToggleBluetoothToolExecutor.register(registry)
        SetDndToolExecutor.register(registry)
        SetBrightnessToolExecutor.register(registry)
        SetVolumeToolExecutor.register(registry)
        ControlMediaToolExecutor.register(registry)
        GetBatteryInfoToolExecutor.register(registry)
        GetDeviceInfoToolExecutor.register(registry)
        ReadNotificationsToolExecutor.register(registry)
        ToggleFlashlightToolExecutor.register(registry)
        TakeScreenshotToolExecutor.register(registry)
    }
}
