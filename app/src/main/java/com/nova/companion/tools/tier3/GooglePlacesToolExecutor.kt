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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Places tool — find nearby gyms, restaurants, hospitals, anything.
 * Uses Google Places API (Text Search) with the Google API key.
 * "Find MMA gyms near me" / "best cafe near Koramangala" / "nearest pharmacy"
 */
object GooglePlacesToolExecutor {

    private const val TAG = "PlacesTool"
    private const val PLACES_URL = "https://maps.googleapis.com/maps/api/place/textsearch/json"

    fun register(registry: ToolRegistry) {
        registry.registerTool(NovaTool(
            name = "findNearby",
            description = "Find nearby places — gyms, restaurants, cafes, hospitals, ATMs, anything. " +
                    "Use for 'find X near me', 'best gym near Bangalore', 'nearest pharmacy', etc. " +
                    "Returns real places with ratings and addresses.",
            parameters = mapOf(
                "query" to ToolParam(
                    type = "string",
                    description = "What to search for, optionally with location (e.g. 'MMA gym Bangalore', 'coffee near Koramangala')",
                    required = true
                ),
                "open_maps" to ToolParam(
                    type = "boolean",
                    description = "If true, open Google Maps with the search results. Default false.",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        ))
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val query = (params["query"] as? String)?.trim()
            ?: return ToolResult(false, "Query required")
        val openMaps = params["open_maps"] as? Boolean ?: false
        val apiKey = BuildConfig.GOOGLE_API_KEY

        if (openMaps || apiKey.isBlank()) {
            return openInMaps(context, query)
        }

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$PLACES_URL?query=$encoded&key=$apiKey"
                val conn = URL(url).openConnection() as HttpURLConnection
                val json = try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    if (conn.responseCode != 200) return@withContext openInMaps(context, query)
                    conn.inputStream.bufferedReader().readText()
                } finally {
                    conn.disconnect()
                }

                val root = JSONObject(json)
                val status = root.getString("status")
                if (status != "OK") {
                    Log.w(TAG, "Places API status: $status")
                    return@withContext openInMaps(context, query)
                }

                val results = root.getJSONArray("results")
                if (results.length() == 0) return@withContext ToolResult(true, "No places found for \"$query\"")

                val sb = StringBuilder("Places for \"$query\":\n\n")
                for (i in 0 until minOf(results.length(), 5)) {
                    val place = results.getJSONObject(i)
                    val name = place.getString("name")
                    val address = place.optString("formatted_address", "No address")
                    val rating = place.optDouble("rating", 0.0)
                        .let { if (it > 0) " ⭐${it}" else "" }
                    val open = place.optJSONObject("opening_hours")
                        ?.optBoolean("open_now")
                        ?.let { if (it) " • Open now" else " • Closed" } ?: ""
                    sb.append("${i + 1}. $name$rating$open\n   $address\n\n")
                }

                ToolResult(true, sb.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "Places search failed", e)
                openInMaps(context, query)
            }
        }
    }

    private fun openInMaps(context: Context, query: String): ToolResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/maps?q=$encoded"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Opening Google Maps for \"$query\"")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't search places: ${e.message}")
        }
    }
}
