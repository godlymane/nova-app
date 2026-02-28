package com.nova.companion.tools

import com.nova.companion.tools.tier1.Tier1ToolRegistry
import com.nova.companion.tools.tier2.Tier2ToolRegistry
import com.nova.companion.tools.tier3.Tier3ToolRegistry
import com.nova.companion.tools.tier4.Tier4ToolRegistry

object NovaToolRegistry {

    /**
     * Register all tiered tools into an existing ToolRegistry singleton.
     * Called by ToolRegistry.initializeTools().
     */
    fun registerAll(registry: ToolRegistry) {
        Tier1ToolRegistry.registerAll(registry)
        Tier2ToolRegistry.registerAll(registry)
        Tier3ToolRegistry.registerAll(registry)
        Tier4ToolRegistry.registerAll(registry)
    }

    /**
     * Build a standalone registry (kept for backward compatibility).
     */
    fun buildRegistry(): ToolRegistry {
        val registry = ToolRegistry
        registerAll(registry)
        return registry
    }
}
