package com.nova.companion.tools

import com.nova.companion.tools.tier1.Tier1ToolRegistry
import com.nova.companion.tools.tier3.Tier3ToolRegistry

object NovaToolRegistry {

    fun registerAll(registry: ToolRegistry) {
        Tier1ToolRegistry.registerAll(registry)
        // Tier2ToolRegistry.registerAll(registry)
        Tier3ToolRegistry.registerAll(registry)
        // Tier4ToolRegistry.registerAll(registry)
    }
}
