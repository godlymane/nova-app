package com.nova.companion.tools.tier4

import android.util.Log
import com.nova.companion.agent.AgentExecutor
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

/**
 * AgentExecuteTool — Nova's "hands". Wraps AgentExecutor (ReAct loop) as a first-class tool.
 *
 * When GPT-4o needs to complete any multi-step task that requires reading the screen
 * and performing a sequence of taps/types/scrolls — ordering food, sending payments,
 * filling forms, navigating apps end-to-end — it calls this tool with a clear goal string.
 *
 * AgentExecutor then runs a full Think→Act→Observe loop (up to 15 steps) using
 * UIAutomator + accessibility service + optional vision fallback to complete the task.
 *
 * This is the bridge between CloudLLMService's tool-calling loop and the
 * fully autonomous UI agent. Without this, tools like orderFood just open the app and
 * return success — GPT-4o thinks the task is done and tells the user to "finish it up",
 * which is exactly the "dumb Nova" bug.
 */
object AgentExecuteToolExecutor {

    private const val TAG = "AgentExecuteTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "agentExecute",
            description = """Execute a multi-step goal on the phone autonomously.
Use this for ANY task that requires opening an app AND completing steps inside it —
ordering food, sending money/payments, filling out forms, posting on social media,
navigating to a specific screen inside an app, completing checkouts, etc.
Pass a clear, specific natural-language goal. The agent reads the screen at each step
and taps/types/scrolls until the goal is accomplished or it determines it cannot continue.
Returns success=true with a summary of what was accomplished, or success=false with reason.""",
            parameters = mapOf(
                "goal" to ToolParam(
                    type = "string",
                    description = "The specific task to complete on the phone. Be precise — include app name, amounts, recipient names, item names, etc. Example: 'Open PhonePe and send 2 rupees to contact A'. Example: 'Open Swiggy, search for RRR Biryani, add it to cart, and complete the order with Cash on Delivery'.",
                    required = true
                )
            ),
            executor = { ctx, params -> executeAgent(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeAgent(
        context: android.content.Context,
        params: Map<String, Any>
    ): ToolResult {
        val goal = (params["goal"] as? String)?.trim()
        if (goal.isNullOrBlank()) {
            return ToolResult(false, "No goal provided to agentExecute")
        }

        Log.i(TAG, "agentExecute called — goal: $goal")

        val agentExecutor = AgentExecutor(context)
        val result = agentExecutor.execute(
            goal = goal,
            taskId = "tool_agent_${System.currentTimeMillis()}"
        )

        Log.i(TAG, "agentExecute finished — goalAchieved=${result.goalAchieved}, summary=${result.summary}, steps=${result.stepsExecuted}")

        return ToolResult(
            success = result.goalAchieved,
            message = result.summary,
            data = mapOf(
                "steps_executed" to result.stepsExecuted,
                "termination_reason" to result.terminationReason.name
            )
        )
    }
}
