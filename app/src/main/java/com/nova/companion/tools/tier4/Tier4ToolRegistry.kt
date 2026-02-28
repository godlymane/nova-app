package com.nova.companion.tools.tier4

import com.nova.companion.tools.ToolRegistry

object Tier4ToolRegistry {

    fun registerAll(registry: ToolRegistry) {
        TapOnScreenToolExecutor.register(registry)
        TypeTextToolExecutor.register(registry)
        ScrollScreenToolExecutor.register(registry)
        PressBackToolExecutor.register(registry)
        ReadScreenToolExecutor.register(registry)
        SendWhatsAppFullToolExecutor.register(registry)
        AutoFillFormToolExecutor.register(registry)
        NavigateAppToolExecutor.register(registry)
        WaitForElementToolExecutor.register(registry)
    }
}
