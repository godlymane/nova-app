package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.nova.companion.BuildConfig
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Browserless tool — lets Nova actually browse the web and extract content.
 * Uses browserless.io Chrome API to scrape pages, extract text, get live data.
 *
 * Example: "what's on Blayzex's homepage?" → fetches and reads the page
 */
object BrowserlessToolExecutor {

    private const val TAG = "BrowserlessTool"
    private const val SCRAPE_URL = "https://chrome.browserless.io/scrape"
    private const val CONTENT_URL = "https://chrome.browserless.io/content"

    fun register(registry: ToolRegistry) {
        registry.registerTool(NovaTool(
            name = "browseWeb",
            description = "Browse a URL and extract text content from the page. " +
                    "Use this to read articles, get live prices, check websites, read documentation, " +
                    "or get any content from a URL that other tools can't access.",
            parameters = mapOf(
                "url" to ToolParam(
                    type = "string",
                    description = "Full URL to browse (e.g. https://example.com)",
                    required = true
                ),
                "extract" to ToolParam(
                    type = "string",
                    description = "What to extract: 'text' (default, all visible text), " +
                            "'title' (page title only), 'links' (all links)",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        ))
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val url = (params["url"] as? String)?.trim() ?: return ToolResult(false, "URL required")
        val extract = (params["extract"] as? String)?.trim() ?: "text"

        if (BuildConfig.BROWSERLESS_API_KEY.isBlank()) {
            return ToolResult(false, "Browserless API key not configured")
        }

        return withContext(Dispatchers.IO) {
            try {
                val content = fetchPageContent(url)
                when (extract) {
                    "title" -> {
                        val title = extractTitle(content)
                        ToolResult(true, "Page title: $title")
                    }
                    "links" -> {
                        val links = extractLinks(content)
                        ToolResult(true, "Links found:\n$links")
                    }
                    else -> {
                        val text = extractText(content)
                        val trimmed = text.take(2000) // keep context manageable
                        ToolResult(true, trimmed)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Browserless fetch failed for $url", e)
                ToolResult(false, "Couldn't browse $url: ${e.message}")
            }
        }
    }

    private fun fetchPageContent(targetUrl: String): String {
        val apiUrl = "$CONTENT_URL?token=${BuildConfig.BROWSERLESS_API_KEY}"
        val body = JSONObject().apply {
            put("url", targetUrl)
            put("waitFor", 1500)
        }.toString()

        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw Exception("Browserless returned $code: ${err.take(200)}")
            }

            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(html: String): String {
        // Strip tags, collapse whitespace
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractTitle(html: String): String {
        val match = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.get(1)?.trim() ?: "No title found"
    }

    private fun extractLinks(html: String): String {
        val links = Regex("""href="(https?://[^"]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(20)
            .joinToString("\n")
        return links.ifEmpty { "No links found" }
    }
}
