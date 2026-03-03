package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nova.companion.data.NovaDatabase
import com.nova.companion.overlay.bubble.TaskBubbleService
import com.nova.companion.overlay.bubble.TaskProgressManager
import com.nova.companion.routines.RecordedStep
import com.nova.companion.routines.RoutinePlayer
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object RunLearnedRoutineToolExecutor {

    private const val TAG = "RunLearnedRoutineTool"
    private val gson = Gson()

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "runLearnedRoutine",
            description = "Run a routine that the user previously taught Nova. Use this when the user asks to do something that matches a learned routine (e.g. 'post a reel', 'upload a story'). Call this instead of manually doing the steps.",
            parameters = mapOf(
                "routine_name" to ToolParam(
                    type = "string",
                    description = "Name or trigger phrase of the routine to run (e.g. 'post instagram reel')",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val routineName = (params["routine_name"] as? String)?.trim()
            if (routineName.isNullOrBlank()) {
                return ToolResult(false, "routine_name is required")
            }

            val dao = NovaDatabase.getInstance(context).learnedRoutineDao()
            val matches = dao.findByTrigger(routineName)

            if (matches.isEmpty()) {
                return ToolResult(false, "No learned routine found matching '$routineName'. Available routines: ${dao.getAll().joinToString { it.name }}")
            }

            val routine = matches.first()
            Log.i(TAG, "Running learned routine: ${routine.name} (${routine.useCount} previous uses)")

            // Parse steps from JSON
            val stepsType = object : TypeToken<List<RecordedStep>>() {}.type
            val steps: List<RecordedStep> = try {
                gson.fromJson(routine.steps, stepsType)
            } catch (e: Exception) {
                return ToolResult(false, "Failed to parse routine steps: ${e.message}")
            }

            if (steps.isEmpty()) {
                return ToolResult(false, "Routine '${routine.name}' has no steps")
            }

            // Show floating bubble for replay progress
            val taskId = "routine_${routine.id}"
            TaskProgressManager.startTask(taskId, "Running: ${routine.name}")
            try { TaskBubbleService.start(context) } catch (e: Exception) {
                Log.w(TAG, "Could not start bubble overlay", e)
            }

            // Execute the routine (RoutinePlayer pushes step progress to TaskProgressManager)
            val result = RoutinePlayer.play(steps, context, taskId)

            // Mark as used
            dao.markUsed(routine.id)

            // Bubble auto-dismisses after complete/fail via TaskBubbleService observer

            if (result.success) {
                ToolResult(true, "Completed routine '${routine.name}': ${result.stepsCompleted}/${result.totalSteps} steps done.")
            } else {
                ToolResult(false, "Routine '${routine.name}' failed: ${result.message} (${result.stepsCompleted}/${result.totalSteps} steps completed)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run routine", e)
            ToolResult(false, "Failed to run routine: ${e.message}")
        }
    }
}
