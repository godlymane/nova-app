package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.BuildConfig
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object GetWeatherToolExecutor {

    private const val TAG = "GetWeatherTool"
    private const val DEFAULT_CITY = "Hyderabad"
    private const val API_BASE = "https://api.openweathermap.org/data/2.5/weather"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "getWeather",
            description = "Get the current weather for a city. Defaults to Hyderabad if no city specified.",
            parameters = mapOf(
                "city" to ToolParam(type = "string", description = "The city to get weather for", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val city = (params["city"] as? String)?.trim()?.ifEmpty { null } ?: DEFAULT_CITY

        return try {
            val apiKey = BuildConfig.OPENWEATHER_API_KEY
            if (apiKey.isBlank()) {
                return openWeatherInBrowser(context, city)
            }
            fetchWeatherFromApi(context, city, apiKey)
        } catch (e: Exception) {
            Log.e(TAG, "Weather API call failed, falling back to browser", e)
            openWeatherInBrowser(context, city)
        }
    }

    private suspend fun fetchWeatherFromApi(context: Context, city: String, apiKey: String): ToolResult {
        return withContext(Dispatchers.IO) {
            val encodedCity = URLEncoder.encode(city, "UTF-8")
            val urlString = "$API_BASE?q=$encodedCity&appid=$apiKey&units=metric"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Weather API returned code $responseCode for city: $city")
                    return@withContext openWeatherInBrowser(context, city)
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val main = json.getJSONObject("main")
                val temp = main.getDouble("temp")
                val feelsLike = main.getDouble("feels_like")
                val humidity = main.getInt("humidity")
                val weatherArray = json.getJSONArray("weather")
                val description = if (weatherArray.length() > 0) {
                    weatherArray.getJSONObject(0).getString("description")
                } else {
                    "unknown"
                }
                val wind = json.getJSONObject("wind").getDouble("speed")
                val windKmh = String.format("%.1f", wind * 3.6)

                val message = "Weather in $city: ${String.format("%.1f", temp)}°C, $description. " +
                        "Feels like ${String.format("%.1f", feelsLike)}°C. " +
                        "Humidity: $humidity%. Wind: $windKmh km/h"

                Log.i(TAG, message)
                ToolResult(true, message)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openWeatherInBrowser(context: Context, city: String): ToolResult {
        return try {
            val encoded = URLEncoder.encode(city, "UTF-8")
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://weather.google.com/weather/$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val message = "Opening weather for $city"
            Log.i(TAG, message)
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open weather in browser", e)
            ToolResult(false, "Failed to get weather: ${e.message}")
        }
    }
}
