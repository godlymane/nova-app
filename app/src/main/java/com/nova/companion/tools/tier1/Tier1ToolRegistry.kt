package com.nova.companion.tools.tier1

import com.nova.companion.tools.ToolRegistry

object Tier1ToolRegistry {

    fun registerAll(registry: ToolRegistry) {
        SendSmsToolExecutor.register(registry)
        MakeCallToolExecutor.register(registry)
        SetAlarmToolExecutor.register(registry)
        SetTimerToolExecutor.register(registry)
        OpenAppToolExecutor.register(registry)
        CreateCalendarEventToolExecutor.register(registry)
        LookupContactToolExecutor.register(registry)
        SendWhatsAppToolExecutor.register(registry)
        SearchWebToolExecutor.register(registry)
        OpenUrlToolExecutor.register(registry)
    }
}