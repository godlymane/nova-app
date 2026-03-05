package com.nova.companion.tools.tier3

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.nova.companion.BuildConfig
import com.nova.companion.data.NovaDatabase
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

object MorningBriefingToolExecutor {

    private const val TAG = "MorningBriefing"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "morningBriefing",
            description = "Generate a comprehensive morning briefing: weather, calendar, tasks, fitness, battery, and motivational insight. Use this when user says 'good morning', 'morning briefing', 'daily brief'.",
            parameters = mapOf(
                "city" to ToolParam("string", "City for weather (default: Hyderabad)", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val city = params["city"] as? String ?: "Hyderabad"
        val db = NovaDatabase.getInstance(context)
        val dayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        val dateFull = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())

        return try {
            val sb = StringBuilder()
            sb.append("Good morning! Here's your briefing for $dayName, $dateFull:\n\n")

            // 1. Weather
            val weather = fetchWeather(city)
            if (weather != null) {
                sb.append("WEATHER: ${weather.first}\n\n")
            }

            // 2. Tasks
            val tasks = db.taskDao().getActiveTasks()
            val overdue = db.taskDao().getOverdue()
            sb.append("TASKS: ${tasks.size} pending")
            if (overdue.isNotEmpty()) sb.append(" (${overdue.size} overdue)")
            sb.append("\n")
            tasks.take(3).forEach { t ->
                sb.append("  - ${t.title}")
                if (t.dueDate != null) {
                    val df = SimpleDateFormat("MMM dd", Locale.getDefault())
                    sb.append(" (due ${df.format(Date(t.dueDate))})")
                }
                sb.append("\n")
            }
            sb.append("\n")

            // 3. Fitness (yesterday recap)
            val yesterday = run {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, -1)
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            }
            val yesterdaySteps = db.fitnessDao().getDailyTotal("steps", yesterday) ?: 0.0
            val yesterdayWater = db.fitnessDao().getDailyTotal("water", yesterday) ?: 0.0
            if (yesterdaySteps > 0 || yesterdayWater > 0) {
                sb.append("YESTERDAY: ${yesterdaySteps.toInt()} steps")
                if (yesterdayWater > 0) sb.append(", ${yesterdayWater.toInt()}ml water")
                sb.append("\n\n")
            }

            // 4. Battery
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (battery > 0) {
                sb.append("BATTERY: $battery%")
                if (battery < 30) sb.append(" (consider charging)")
                sb.append("\n\n")
            }

            // 5. Spending (yesterday)
            val (yStart, yEnd) = run {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val s = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                Pair(s, cal.timeInMillis)
            }
            val ySpend = db.expenseDao().getTotalByRange(yStart, yEnd) ?: 0.0
            if (ySpend > 0) {
                sb.append("SPENDING: Spent ₹${ySpend.toLong()} yesterday\n\n")
            }

            sb.append("Have a productive day!")

            ToolResult(true, sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Briefing error", e)
            ToolResult(false, "Could not generate briefing: ${e.message}")
        }
    }

    private suspend fun fetchWeather(city: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val key = BuildConfig.OPENWEATHER_API_KEY
            if (key.isBlank() || key == "your_key_here") return@withContext null

            val encoded = URLEncoder.encode(city, "UTF-8")
            val url = URL("https://api.openweathermap.org/data/2.5/weather?q=$encoded&units=metric&appid=$key")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            val main = json.getJSONObject("main")
            val temp = main.getDouble("temp").toInt()
            val desc = json.getJSONArray("weather").getJSONObject(0).getString("description")
            val humidity = main.getInt("humidity")

            Pair("$city: ${temp}°C, $desc, humidity $humidity%", desc)
        } catch (_: Exception) { null }
    }
}
