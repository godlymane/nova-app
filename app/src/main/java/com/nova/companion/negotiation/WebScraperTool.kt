package com.nova.companion.negotiation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

/**
 * WebScraperTool — The Bootstrapper's API.
 *
 * When no free API exists for a query, Nova falls back to:
 *   1. Search the web (DuckDuckGo HTML)
 *   2. Fetch the most promising result page
 *   3. Extract readable text via Jsoup
 *   4. Feed the text to the LLM for structured extraction
 *
 * This is slower and noisier than a clean API, but it works for anything
 * on the public web — for free.
 */
object WebScraperTool {

    private const val TAG = "WebScraper"
    private const val MAX_CONTENT_CHARS = 6_000   // Keep content small for LLM context
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // ── Core: Fetch + Clean ─────────────────────────────────────

    /**
     * Fetch a URL and extract clean readable text.
     *
     * @param url The page to fetch
     * @param selector Optional CSS selector to target specific content (e.g. "article", ".main-content")
     * @return ScrapeResult with title, text, and metadata
     */
    suspend fun fetchAndClean(url: String, selector: String? = null): ScrapeResult =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching: $url")

                val doc: Document = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(CONNECT_TIMEOUT_MS)
                    .maxBodySize(512 * 1024)  // 512KB cap
                    .followRedirects(true)
                    .get()

                val title = doc.title()

                // Remove noise elements
                doc.select("script, style, nav, footer, header, aside, .ad, .ads, .cookie-banner, .popup, #cookie-consent, .social-share, .comments, noscript, iframe").remove()

                // Extract text from targeted selector or full body
                val textSource = if (selector != null) {
                    doc.select(selector).firstOrNull() ?: doc.body()
                } else {
                    // Try common article selectors first
                    doc.select("article").firstOrNull()
                        ?: doc.select("[role=main]").firstOrNull()
                        ?: doc.select("main").firstOrNull()
                        ?: doc.select(".post-content, .article-body, .entry-content").firstOrNull()
                        ?: doc.body()
                }

                val rawText = textSource?.let { Jsoup.clean(it.html(), Safelist.none()) } ?: ""

                // Clean up whitespace
                val cleanText = rawText
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .take(MAX_CONTENT_CHARS)

                // Extract links for potential follow-up
                val links = doc.select("a[href]")
                    .take(10)
                    .mapNotNull { el ->
                        val href = el.absUrl("href")
                        val text = el.text().trim()
                        if (href.isNotBlank() && text.isNotBlank() && text.length > 3) {
                            LinkInfo(text.take(80), href)
                        } else null
                    }

                // Extract meta description
                val metaDesc = doc.select("meta[name=description]").attr("content")

                ScrapeResult(
                    success = true,
                    url = url,
                    title = title,
                    text = cleanText,
                    metaDescription = metaDesc,
                    links = links
                )
            } catch (e: Exception) {
                Log.e(TAG, "Scrape failed for $url", e)
                ScrapeResult(
                    success = false,
                    url = url,
                    error = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

    // ── Search + Scrape Pipeline ─────────────────────────────────

    /**
     * Search the web via DuckDuckGo HTML and return structured results.
     * This is the "free API" fallback — no key needed.
     */
    suspend fun searchWeb(query: String, maxResults: Int = 5): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Web search: $query")

                val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
                    .userAgent(USER_AGENT)
                    .timeout(CONNECT_TIMEOUT_MS)
                    .data("q", query)
                    .post()

                val results = doc.select(".result")
                    .take(maxResults)
                    .mapNotNull { result ->
                        val titleEl = result.select(".result__a").firstOrNull() ?: return@mapNotNull null
                        val title = titleEl.text()
                        val href = titleEl.absUrl("href").let { url ->
                            // DDG wraps URLs — extract the actual URL
                            if (url.contains("uddg=")) {
                                java.net.URLDecoder.decode(
                                    url.substringAfter("uddg=").substringBefore("&"),
                                    "UTF-8"
                                )
                            } else url
                        }
                        val snippet = result.select(".result__snippet").text()

                        if (title.isNotBlank() && href.isNotBlank()) {
                            SearchResult(title, href, snippet)
                        } else null
                    }

                Log.d(TAG, "Found ${results.size} search results for: $query")
                results
            } catch (e: Exception) {
                Log.e(TAG, "Web search failed", e)
                emptyList()
            }
        }

    /**
     * Search the web, pick the best result, fetch and clean it.
     * One-shot convenience for the MasterPlanner.
     */
    suspend fun searchAndScrape(query: String): ScrapeResult {
        val results = searchWeb(query, maxResults = 3)
        if (results.isEmpty()) {
            return ScrapeResult(success = false, url = "", error = "No search results found for: $query")
        }

        // Try the first non-PDF, non-video result
        val bestResult = results.firstOrNull { result ->
            !result.url.endsWith(".pdf") &&
            !result.url.contains("youtube.com") &&
            !result.url.contains("youtu.be")
        } ?: results.first()

        val scrapeResult = fetchAndClean(bestResult.url)
        return scrapeResult.copy(
            searchQuery = query,
            searchResults = results
        )
    }

    /**
     * Extract structured JSON from raw HTML by passing it to the LLM.
     * This is used when we scrape a page and need the LLM to pull
     * specific fields (prices, names, dates, etc.) from messy HTML.
     *
     * Returns a prompt that should be sent to a cheap LLM (GPT-4o-mini).
     */
    fun buildExtractionPrompt(
        pageText: String,
        extractionGoal: String
    ): String {
        return """
Extract the following information from this web page content.
Return ONLY a JSON object with the extracted data. No explanation.

## What to extract
$extractionGoal

## Page content
${pageText.take(MAX_CONTENT_CHARS)}

## Response format
Return a JSON object. If information is not found, use null for that field.
""".trimIndent()
    }
}

data class ScrapeResult(
    val success: Boolean,
    val url: String,
    val title: String = "",
    val text: String = "",
    val metaDescription: String = "",
    val links: List<LinkInfo> = emptyList(),
    val error: String? = null,
    val searchQuery: String? = null,
    val searchResults: List<SearchResult>? = null
) {
    /** Formatted for feeding into an LLM */
    fun toToolMessage(): String = when {
        !success -> "Web scrape failed: $error"
        text.isBlank() -> "Page loaded but no readable content found at $url"
        else -> buildString {
            appendLine("# $title")
            if (metaDescription.isNotBlank()) appendLine("_${metaDescription}_")
            appendLine()
            appendLine(text)
        }
    }
}

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

data class LinkInfo(
    val text: String,
    val url: String
)
