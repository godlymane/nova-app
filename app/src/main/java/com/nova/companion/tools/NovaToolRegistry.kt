package com.nova.companion.tools

import com.nova.companion.tools.tier1.Tier1ToolRegistry
import com.nova.companion.tools.tier2.Tier2ToolRegistry
import com.nova.companion.tools.tier4.Tier4ToolRegistry

object NovaToolRegistry {

    fun buildRegistry(): ToolRegistry {
        val registry = ToolRegistry()
        Tier1ToolRegistry.registerAll(registry)
        Tier2ToolRegistry.registerAll(registry)
        Tier4ToolRegistry.registerAll(registry)
        return registry
    }
}
