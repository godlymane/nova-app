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
 * YouTube tool — search videos and get real metadata (title, views, channel, duration).
 * Uses YouTube Data API v3 with the Google API key.
 */
object YouTubeToolExecutor {

    private const val TAG = "YouTubeTool"
    private const val SEARCH_URL = "https://www.googleapis.com/youtube/v3/search"
    private const val VIDEOS_URL = "https://www.googleapis.com/youtube/v3/videos"

    fun register(registry: ToolRegistry) {
        registry.registerTool(NovaTool(
            name = "searchYouTube",
            description = "Search YouTube for videos and get real results with view counts, " +
                    "channel names, and links. Better than opening YouTube manually.",
            parameters = mapOf(
                "query" to ToolParam(
                    type = "string",
                    description = "What to search for on YouTube",
                    required = true
                ),
                "play" to ToolParam(
                    type = "boolean",
                    description = "If true, open the top result in YouTube app. Default false.",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        ))
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val query = (params["query"] as? String)?.trim()
            ?: return ToolResult(false, "Query required")
        val play = params["play"] as? Boolean ?: false
        val apiKey = BuildConfig.GOOGLE_API_KEY

        if (apiKey.isBlank()) {
            return openYouTubeApp(context, query)
        }

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$SEARCH_URL?part=snippet&q=$encoded&type=video" +
                        "&maxResults=5&key=$apiKey"
                val searchResp = get(searchUrl)
                    ?: return@withContext openYouTubeApp(context, query)

                val root = JSONObject(searchResp)
                val items = root.getJSONArray("items")
                if (items.length() == 0) return@withContext ToolResult(true, "No results found for \"$query\"")

                // Get video IDs for stats (views, duration)
                val videoIds = (0 until minOf(items.length(), 5))
                    .map { items.getJSONObject(it).getJSONObject("id").getString("videoId") }
                    .joinToString(",")

                val statsUrl = "$VIDEOS_URL?part=statistics,contentDetails&id=$videoIds&key=$apiKey"
                val statsResp = get(statsUrl)
                val statsMap = mutableMapOf<String, Pair<String, String>>() // id → (views, duration)

                if (statsResp != null) {
                    val statsItems = JSONObject(statsResp).getJSONArray("items")
                    for (i in 0 until statsItems.length()) {
                        val item = statsItems.getJSONObject(i)
                        val id = item.getString("id")
                        val views = item.getJSONObject("statistics")
                            .optString("viewCount", "0").toLongOrNull()
                            ?.let { formatViews(it) } ?: "?"
                        val dur = item.getJSONObject("contentDetails")
                            .optString("duration", "").let { parseDuration(it) }
                        statsMap[id] = Pair(views, dur)
                    }
                }

                val sb = StringBuilder("YouTube results for \"$query\":\n\n")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val videoId = item.getJSONObject("id").getString("videoId")
                    val snippet = item.getJSONObject("snippet")
                    val title = snippet.getString("title")
                    val channel = snippet.getString("channelTitle")
                    val (views, dur) = statsMap[videoId] ?: Pair("?", "?")
                    sb.append("${i + 1}. $title\n   $channel • $views views • $dur\n   https://youtu.be/$videoId\n\n")
                }

                if (play) {
                    val topId = items.getJSONObject(0).getJSONObject("id").getString("videoId")
                    openVideoInApp(context, topId)
                    sb.append("Opening top result in YouTube...")
                }

                ToolResult(true, sb.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "YouTube search failed", e)
                openYouTubeApp(context, query)
            }
        }
    }

    private fun openYouTubeApp(context: Context, query: String): ToolResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Opening YouTube search for \"$query\"")
        } catch (e: Exception) {
            ToolResult(false, "Couldn't open YouTube: ${e.message}")
        }
    }

    private fun openVideoInApp(context: Context, videoId: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/$videoId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't open video $videoId", e)
        }
    }

    private fun formatViews(views: Long): String = when {
        views >= 1_000_000_000 -> "${views / 1_000_000_000}B"
        views >= 1_000_000 -> "${views / 1_000_000}M"
        views >= 1_000 -> "${views / 1_000}K"
        else -> views.toString()
    }

    private fun parseDuration(iso: String): String {
        // ISO 8601 duration: PT4M13S
        if (iso.isBlank()) return "?"
        val h = Regex("(\\d+)H").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)M").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val s = Regex("(\\d+)S").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            Log.e(TAG, "GET failed: $url", e)
            null
        } finally {
            conn.disconnect()
        }
    }
}
