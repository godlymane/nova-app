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
        // AgentExecute: bridges CloudLLMService tool loop → AgentExecutor ReAct loop
        // This is the key to Nova being fully autonomous — GPT-4o calls this for any
        // multi-step task (order food, send payment, complete form) instead of the
        // shallow tools that just open the app and return success.
        AgentExecuteToolExecutor.register(registry)
        AnalyzeScreenToolExecutor.register(registry)
    }
}
