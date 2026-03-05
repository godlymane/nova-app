package com.nova.companion.tools.tier3

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.FitnessLog
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.*

object FitnessToolExecutor {

    private const val TAG = "FitnessTool"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "fitness",
            description = "Track fitness: steps, workouts, water, sleep, weight. Actions: 'log' to record, 'today' for daily summary, 'week' for weekly overview, 'steps' for step count.",
            parameters = mapOf(
                "action" to ToolParam("string", "Action: log, today, week, steps", true),
                "type" to ToolParam("string", "Type: steps, workout, water, sleep, weight", false),
                "value" to ToolParam("number", "Value (steps count, minutes, ml, hours, kg)", false),
                "details" to ToolParam("string", "Extra details: workout type, exercises, notes", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "today"
        val db = NovaDatabase.getInstance(context)
        val today = dateFormat.format(Date())

        return try {
            when (action) {
                "log" -> logEntry(db, params, today)
                "today" -> getDailySummary(db, today, context)
                "week" -> getWeeklySummary(db)
                "steps" -> getSteps(db, today, context)
                else -> ToolResult(false, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fitness tool error", e)
            ToolResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun logEntry(db: NovaDatabase, params: Map<String, Any>, today: String): ToolResult {
        val type = (params["type"] as? String)?.lowercase()
            ?: return ToolResult(false, "Type required: steps, workout, water, sleep, weight")

        val value = when (val v = params["value"]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        } ?: return ToolResult(false, "Value required (number).")

        val details = params["details"] as? String ?: ""

        val (unit, calories) = when (type) {
            "steps" -> "steps" to (value * 0.04).toInt()
            "workout" -> "min" to (value * 7).toInt()  // ~7 cal/min avg
            "water" -> "ml" to 0
            "sleep" -> "hrs" to 0
            "weight" -> "kg" to 0
            else -> "" to 0
        }

        val log = FitnessLog(
            type = type,
            value = value,
            unit = unit,
            details = details,
            caloriesBurned = calories,
            date = today
        )
        db.fitnessDao().insert(log)

        // Get daily total for this type
        val dayTotal = db.fitnessDao().getDailyTotal(type, today) ?: value
        val totalCals = db.fitnessDao().getCaloriesBurned(today) ?: 0

        return ToolResult(true, buildString {
            append("Logged: ${value.toInt()} $unit of $type")
            if (details.isNotBlank()) append(" ($details)")
            append("\nToday's $type total: ${dayTotal.toInt()} $unit")
            if (totalCals > 0) append(" | Calories burned today: $totalCals")
        })
    }

    private suspend fun getDailySummary(db: NovaDatabase, today: String, context: Context): ToolResult {
        val logs = db.fitnessDao().getByDate(today)
        val steps = db.fitnessDao().getDailyTotal("steps", today) ?: 0.0
        val water = db.fitnessDao().getDailyTotal("water", today) ?: 0.0
        val workoutMins = db.fitnessDao().getDailyTotal("workout", today) ?: 0.0
        val sleep = db.fitnessDao().getDailyTotal("sleep", today) ?: 0.0
        val calories = db.fitnessDao().getCaloriesBurned(today) ?: 0

        // Try reading device step sensor
        val sensorSteps = readStepSensor(context)

        return ToolResult(true, buildString {
            append("Today's fitness:\n")
            append("  Steps: ${steps.toInt()}")
            if (sensorSteps > 0) append(" (sensor: $sensorSteps)")
            append(" / 10,000 goal\n")
            append("  Water: ${water.toInt()} ml / 3,000 ml goal\n")
            append("  Workout: ${workoutMins.toInt()} min\n")
            if (sleep > 0) append("  Sleep: ${sleep}h\n")
            append("  Calories burned: $calories\n")

            // Progress bars
            val stepPct = ((steps / 10000) * 100).toInt().coerceIn(0, 100)
            val waterPct = ((water / 3000) * 100).toInt().coerceIn(0, 100)
            append("\nProgress:\n")
            append("  Steps  ${"█".repeat(stepPct / 5)}${"░".repeat(20 - stepPct / 5)} $stepPct%\n")
            append("  Water  ${"█".repeat(waterPct / 5)}${"░".repeat(20 - waterPct / 5)} $waterPct%\n")

            if (logs.isEmpty() && sensorSteps == 0) {
                append("\nNo fitness data logged yet today. Say 'log 2000 steps' or 'log 30 min workout'.")
            }
        })
    }

    private suspend fun getWeeklySummary(db: NovaDatabase): ToolResult {
        val cal = Calendar.getInstance()
        val endDate = dateFormat.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, -7)
        val startDate = dateFormat.format(cal.time)

        val stepDays = db.fitnessDao().getDailyTotals("steps", startDate, endDate)
        val waterDays = db.fitnessDao().getDailyTotals("water", startDate, endDate)
        val workoutDays = db.fitnessDao().getDailyTotals("workout", startDate, endDate)

        val totalSteps = stepDays.sumOf { it.total }
        val totalWater = waterDays.sumOf { it.total }
        val totalWorkout = workoutDays.sumOf { it.total }
        val activeDays = stepDays.count { it.total > 1000 }

        return ToolResult(true, buildString {
            append("Week summary ($startDate to $endDate):\n")
            append("  Total steps: ${totalSteps.toInt()} (avg ${(totalSteps / 7).toInt()}/day)\n")
            append("  Total water: ${totalWater.toInt()} ml (avg ${(totalWater / 7).toInt()}/day)\n")
            append("  Workout: ${totalWorkout.toInt()} min across ${workoutDays.size} days\n")
            append("  Active days: $activeDays/7\n")

            if (stepDays.isNotEmpty()) {
                append("\nDaily steps:\n")
                stepDays.forEach { day ->
                    val bar = "█".repeat((day.total / 500).toInt().coerceIn(0, 20))
                    append("  ${day.date.takeLast(5)} $bar ${day.total.toInt()}\n")
                }
            }
        })
    }

    private suspend fun getSteps(db: NovaDatabase, today: String, context: Context): ToolResult {
        val logged = db.fitnessDao().getDailyTotal("steps", today) ?: 0.0
        val sensor = readStepSensor(context)
        val total = if (sensor > logged.toInt()) sensor else logged.toInt()
        val goal = 10000
        val pct = (total * 100 / goal).coerceIn(0, 100)

        return ToolResult(true, buildString {
            append("Steps today: $total / $goal ($pct%)\n")
            append("${"█".repeat(pct / 5)}${"░".repeat(20 - pct / 5)}\n")
            val remaining = (goal - total).coerceAtLeast(0)
            if (remaining > 0) append("$remaining steps to go!")
            else append("Goal reached!")
        })
    }

    private fun readStepSensor(context: Context): Int {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val stepSensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor != null) {
                // Step counter gives total since boot — we'd need a background service
                // to track daily resets. Return 0 for now, log-based is primary.
                0
            } else 0
        } catch (_: Exception) { 0 }
    }
}
