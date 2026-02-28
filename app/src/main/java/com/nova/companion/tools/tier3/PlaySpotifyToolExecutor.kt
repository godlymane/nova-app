package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.net.URLEncoder

object PlaySpotifyToolExecutor {

    private const val TAG = "PlaySpotifyTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "playSpotify",
            description = "Search and play music on Spotify. Can search for songs, artists, albums, or playlists.",
            parameters = mapOf(
                "query" to ToolParam(type = "string", description = "What to search for or play", required = true),
                "type" to ToolParam(type = "string", description = "Type of content: track, artist, album, or playlist", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val query = (params["query"] as? String)?.trim()
                ?: return ToolResult(false, "Please tell me what to search for on Spotify")
            val type = (params["type"] as? String)?.trim()?.lowercase() ?: "track"

            val packageName = "com.spotify.music"
            val isInstalled = isAppInstalled(context, packageName)
            val encoded = URLEncoder.encode(query, "UTF-8")

            val intent = if (isInstalled) {
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
                    setPackage(packageName)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$encoded"))
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            val message = "Searching Spotify for $query"
            Log.i(TAG, "$message (type: $type)")
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Spotify", e)
            ToolResult(false, "Failed to open Spotify: ${e.message}")
        }
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            false
        }
    }
}
