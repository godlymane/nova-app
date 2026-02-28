package com.nova.companion.brain.proactive.triggers

import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.proactive.ProactiveMessage

class BatteryTrigger {

    fun evaluate(snapshot: ContextSnapshot): ProactiveMessage? {
        val battery = snapshot.batteryLevel
        if (battery < 0 || snapshot.isCharging) return null

        return when {
            battery <= 5 -> ProactiveMessage(
                message = "Critical battery warning — you're at $battery%. Plug in now or I might go quiet.",
                priority = ProactiveMessage.Priority.CRITICAL,
                triggerType = "battery_critical",
                speakImmediately = true
            )
            battery <= 15 -> ProactiveMessage(
                message = "Battery is getting low at $battery%. Might want to charge soon.",
                priority = ProactiveMessage.Priority.HIGH,
                triggerType = "battery_low"
            )
            else -> null
        }
    }
}
