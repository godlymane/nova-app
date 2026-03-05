package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.SmartRoutine
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

object SmartRoutineToolExecutor {

    private const val TAG = "SmartRoutineTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "smartRoutine",
            description = "Create and manage automated routines. Create morning briefings, bedtime routines, work mode automations. Actions: 'create', 'list', 'enable', 'disable', 'delete', 'run'.",
            parameters = mapOf(
                "action" to ToolParam("string", "Action: create, list, enable, disable, delete, run", true),
                "name" to ToolParam("string", "Routine name", false),
                "trigger" to ToolParam("string", "Trigger: time, manual, battery, app_open", false),
                "config" to ToolParam("string", "Trigger config JSON or time like '8:00 AM weekdays'", false),
                "steps" to ToolParam("string", "Comma-separated actions: weather,calendar,news,focus,dnd", false),
                "id" to ToolParam("number", "Routine ID (for enable/disable/delete/run)", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "list"
        val db = NovaDatabase.getInstance(context)

        return try {
            when (action) {
                "create" -> createRoutine(db, params)
                "list" -> listRoutines(db)
                "enable" -> toggleRoutine(db, params, true)
                "disable" -> toggleRoutine(db, params, false)
                "delete" -> deleteRoutine(db, params)
                "run" -> runRoutine(db, params, context)
                else -> ToolResult(false, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Smart routine error", e)
            ToolResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun createRoutine(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val name = params["name"] as? String
            ?: return ToolResult(false, "Routine name required.")

        val trigger = (params["trigger"] as? String)?.lowercase() ?: "manual"
        val configStr = params["config"] as? String ?: ""
        val stepsStr = params["steps"] as? String ?: "weather,calendar"

        // Parse trigger config
        val triggerConfig = parseTriggerConfig(trigger, configStr)

        // Parse steps into JSON array
        val actions = JSONArray()
        stepsStr.split(",").map { it.trim().lowercase() }.forEach { step ->
            val obj = JSONObject()
            obj.put("type", step)
            actions.put(obj)
        }

        val routine = SmartRoutine(
            name = name,
            triggerType = trigger,
            triggerConfig = triggerConfig,
            actions = actions.toString()
        )
        val id = db.smartRoutineDao().insert(routine)

        return ToolResult(true, buildString {
            append("Routine #$id created: $name\n")
            append("Trigger: $trigger")
            if (configStr.isNotBlank()) append(" ($configStr)")
            append("\nSteps: $stepsStr\n")
            append("Status: enabled")
        })
    }

    private suspend fun listRoutines(db: NovaDatabase): ToolResult {
        val routines = db.smartRoutineDao().getAll()
        if (routines.isEmpty()) {
            return ToolResult(true, buildString {
                append("No routines set up yet.\n\n")
                append("Try creating one:\n")
                append("  'create a morning routine with weather and calendar'\n")
                append("  'create a bedtime routine that enables DND'\n")
                append("  'create a work focus routine'")
            })
        }

        return ToolResult(true, buildString {
            append("Your routines (${routines.size}):\n\n")
            routines.forEach { r ->
                val status = if (r.isEnabled) "ON" else "OFF"
                append("  #${r.id} $status ${r.name}\n")
                append("    Trigger: ${r.triggerType}")
                if (r.triggerConfig.isNotBlank() && r.triggerConfig != "{}") {
                    append(" (${r.triggerConfig})")
                }
                append("\n")
                try {
                    val actions = JSONArray(r.actions)
                    val steps = (0 until actions.length()).map {
                        actions.getJSONObject(it).getString("type")
                    }
                    append("    Steps: ${steps.joinToString(" → ")}\n")
                } catch (_: Exception) {}
                if (r.runCount > 0) append("    Run ${r.runCount} times\n")
                append("\n")
            }
        })
    }

    private suspend fun toggleRoutine(db: NovaDatabase, params: Map<String, Any>, enable: Boolean): ToolResult {
        val id = extractId(params) ?: return ToolResult(false, "Routine ID required.")
        db.smartRoutineDao().setEnabled(id, enable)
        val status = if (enable) "enabled" else "disabled"
        return ToolResult(true, "Routine #$id $status.")
    }

    private suspend fun deleteRoutine(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val id = extractId(params) ?: return ToolResult(false, "Routine ID required.")
        val routine = db.smartRoutineDao().getById(id) ?: return ToolResult(false, "Routine #$id not found.")
        db.smartRoutineDao().delete(routine)
        return ToolResult(true, "Routine '${routine.name}' deleted.")
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun runRoutine(db: NovaDatabase, params: Map<String, Any>, context: Context): ToolResult {
        val id = extractId(params) ?: return ToolResult(false, "Routine ID required.")
        val routine = db.smartRoutineDao().getById(id) ?: return ToolResult(false, "Routine #$id not found.")

        db.smartRoutineDao().markRun(id)

        // Parse and describe actions (actual execution happens via CloudLLMService tool loop)
        val actions = try {
            val arr = JSONArray(routine.actions)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("type") }
        } catch (_: Exception) { listOf("unknown") }

        return ToolResult(true, buildString {
            append("Running routine: ${routine.name}\n")
            append("Steps: ${actions.joinToString(" → ")}\n")
            append("Each step will be executed. Use individual tools for each action.")
        })
    }

    private fun parseTriggerConfig(trigger: String, config: String): String {
        if (config.startsWith("{")) return config // Already JSON
        if (config.isBlank()) return "{}"

        val obj = JSONObject()
        when (trigger) {
            "time" -> {
                // Parse "8:00 AM weekdays" or "10pm daily"
                val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(config)
                if (timeMatch != null) {
                    var hour = timeMatch.groupValues[1].toIntOrNull() ?: 8
                    val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
                    val meridiem = timeMatch.groupValues[3].lowercase()
                    if (meridiem == "pm" && hour < 12) hour += 12
                    if (meridiem == "am" && hour == 12) hour = 0
                    obj.put("hour", hour)
                    obj.put("minute", minute)
                }

                val days = mutableListOf<Int>()
                val lower = config.lowercase()
                when {
                    "weekday" in lower -> days.addAll(listOf(2, 3, 4, 5, 6)) // Mon-Fri
                    "weekend" in lower -> days.addAll(listOf(1, 7)) // Sun, Sat
                    "daily" in lower || "every day" in lower -> days.addAll(listOf(1, 2, 3, 4, 5, 6, 7))
                    else -> days.addAll(listOf(1, 2, 3, 4, 5, 6, 7)) // default daily
                }
                obj.put("days", JSONArray(days))
            }
            "battery" -> {
                val level = Regex("(\\d+)").find(config)?.value?.toIntOrNull() ?: 20
                obj.put("level", level)
                obj.put("trigger", if ("low" in config.lowercase()) "below" else "above")
            }
            else -> obj.put("raw", config)
        }
        return obj.toString()
    }

    private fun extractId(params: Map<String, Any>): Long? {
        return when (val i = params["id"]) {
            is Number -> i.toLong()
            is String -> i.toLongOrNull()
            else -> null
        }
    }
}
