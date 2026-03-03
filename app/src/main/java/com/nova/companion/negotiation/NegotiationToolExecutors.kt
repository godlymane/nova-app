package com.nova.companion.negotiation

import android.content.Context
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

/**
 * Registers the Phase 7 Dynamic Negotiation tools into ToolRegistry.
 *
 * These tools are available to CloudLLMService's agentic tool-calling loop,
 * giving it access to external APIs, web scraping, and goal planning — not
 * just on-device actions.
 */
object NegotiationToolExecutors {

    private const val TAG = "NegotiationTools"

    fun registerAll(registry: ToolRegistry) {
        registry.registerTool(callApiTool)
        registry.registerTool(webSearchTool)
        registry.registerTool(webScrapeTool)
        registry.registerTool(solveGoalTool)
        registry.registerTool(listApisTool)
        Log.i(TAG, "Registered 5 negotiation tools")
    }

    // ── callApi: Execute a registered external API ───────────────

    private val callApiTool = NovaTool(
        name = "callApi",
        description = "Call a registered external API by its ID. Use 'listAvailableApis' first to see what's available. Returns the API response data.",
        parameters = mapOf(
            "api_id" to ToolParam("string", "The API spec ID from the registry (e.g. 'openweathermap_current', 'coingecko_price', 'ddg_instant_answer')", true),
            "params" to ToolParam("string", "JSON object of parameters to pass to the API (e.g. '{\"city\":\"Mumbai\"}' or '{\"coin_id\":\"bitcoin\"}')", true)
        ),
        executor = { _, params ->
            try {
                val apiId = params["api_id"]?.toString() ?: return@NovaTool ToolResult(false, "Missing api_id parameter")
                val paramsJson = params["params"]?.toString() ?: "{}"

                val spec = ExternalApiRegistry.get(apiId)
                    ?: return@NovaTool ToolResult(false, "API '$apiId' not found. Use listAvailableApis to see what's available.")

                // Parse params JSON string into map
                val apiParams = try {
                    val gson = com.google.gson.Gson()
                    gson.fromJson(paramsJson, Map::class.java)
                        ?.mapValues { it.value?.toString() ?: "" }
                        ?.mapKeys { it.key.toString() }
                        ?: emptyMap()
                } catch (e: Exception) {
                    return@NovaTool ToolResult(false, "Invalid params JSON: ${e.message}")
                }

                val response = kotlinx.coroutines.runBlocking {
                    DynamicApiDispatcher.execute(spec, apiParams)
                }

                if (response.success) {
                    ToolResult(true, response.toToolMessage(), response.rawBody)
                } else {
                    ToolResult(false, response.toToolMessage())
                }
            } catch (e: Exception) {
                Log.e(TAG, "callApi failed", e)
                ToolResult(false, "API call failed: ${e.message}")
            }
        }
    )

    // ── webSearch: DuckDuckGo search ─────────────────────────────

    private val webSearchTool = NovaTool(
        name = "webSearchDDG",
        description = "Search the web using DuckDuckGo. Returns titles, URLs, and snippets of top results. Free, no API key needed. Use for current events, live prices, recent news, or anything requiring up-to-date information.",
        parameters = mapOf(
            "query" to ToolParam("string", "Search query (e.g. 'cheapest flights NYC next Friday', 'bitcoin price today')", true),
            "max_results" to ToolParam("integer", "Number of results to return (1-10, default 5)", false)
        ),
        executor = { _, params ->
            try {
                val query = params["query"]?.toString() ?: return@NovaTool ToolResult(false, "Missing query")
                val maxResults = (params["max_results"] as? Number)?.toInt() ?: 5

                val results = kotlinx.coroutines.runBlocking {
                    WebScraperTool.searchWeb(query, maxResults.coerceIn(1, 10))
                }

                if (results.isEmpty()) {
                    ToolResult(false, "No search results found for: $query")
                } else {
                    val formatted = results.mapIndexed { i, r ->
                        "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
                    }.joinToString("\n\n")

                    ToolResult(true, formatted)
                }
            } catch (e: Exception) {
                Log.e(TAG, "webSearch failed", e)
                ToolResult(false, "Web search failed: ${e.message}")
            }
        }
    )

    // ── webScrape: Fetch + clean a URL ───────────────────────────

    private val webScrapeTool = NovaTool(
        name = "webScrape",
        description = "Fetch a web page and extract its readable text content. Use when you need detailed information from a specific URL. Good for reading articles, product pages, documentation, etc.",
        parameters = mapOf(
            "url" to ToolParam("string", "The URL to fetch and scrape", true),
            "selector" to ToolParam("string", "Optional CSS selector to target specific content (e.g. 'article', '.main-content', '#price')", false)
        ),
        executor = { _, params ->
            try {
                val url = params["url"]?.toString() ?: return@NovaTool ToolResult(false, "Missing URL")
                val selector = params["selector"]?.toString()

                val result = kotlinx.coroutines.runBlocking {
                    WebScraperTool.fetchAndClean(url, selector)
                }

                if (result.success) {
                    ToolResult(true, result.toToolMessage(), result)
                } else {
                    ToolResult(false, result.toToolMessage())
                }
            } catch (e: Exception) {
                Log.e(TAG, "webScrape failed", e)
                ToolResult(false, "Web scrape failed: ${e.message}")
            }
        }
    )

    // ── solveGoal: Full planning + execution pipeline ─────────────

    private val solveGoalTool = NovaTool(
        name = "solveGoal",
        description = "Break down a complex goal into steps and execute them using the cheapest available method (free APIs > web scraping > UI automation). Use this for multi-step tasks like 'find flights and email details' or 'compare crypto prices and summarize'.",
        parameters = mapOf(
            "goal" to ToolParam("string", "The complex goal to solve (e.g. 'find the weather in Mumbai and convert the temperature to Fahrenheit')", true)
        ),
        executor = { ctx, params ->
            try {
                val goal = params["goal"]?.toString() ?: return@NovaTool ToolResult(false, "Missing goal")

                val plan = kotlinx.coroutines.runBlocking {
                    MasterPlanner.plan(goal, ctx)
                }

                if (plan == null) {
                    ToolResult(false, "Could not generate a plan for: $goal")
                } else {
                    Log.d(TAG, "Plan: ${plan.analysis} — ${plan.steps.size} steps")

                    val result = kotlinx.coroutines.runBlocking {
                        MasterPlanner.execute(
                            plan = plan,
                            context = ctx,
                            onDeviceTool = { name, toolParams ->
                                val tool = ToolRegistry.getTool(name)
                                if (tool != null) {
                                    tool.executor(ctx, toolParams)
                                } else {
                                    ToolResult(false, "Device tool '$name' not found")
                                }
                            },
                            onUiAutomation = { description ->
                                // For now, return a placeholder — full AgentExecutor integration
                                // happens in ChatViewModel where we have the accessibility service
                                "UI automation not available in this context. Task: $description"
                            }
                        )
                    }

                    ToolResult(true, result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "solveGoal failed", e)
                ToolResult(false, "Goal solving failed: ${e.message}")
            }
        }
    )

    // ── listAvailableApis: Show what APIs are available ───────────

    private val listApisTool = NovaTool(
        name = "listAvailableApis",
        description = "List all registered external APIs that Nova can call. Shows API IDs, descriptions, cost tiers, and whether API keys are configured. Use this to discover what's available before calling callApi.",
        parameters = mapOf(
            "tag_filter" to ToolParam("string", "Optional tag to filter by (e.g. 'weather', 'crypto', 'news', 'search')", false)
        ),
        executor = { _, params ->
            try {
                val tagFilter = params["tag_filter"]?.toString()

                val apis = if (tagFilter != null) {
                    ExternalApiRegistry.findByTags(setOf(tagFilter))
                } else {
                    ExternalApiRegistry.all()
                }

                val formatted = apis.joinToString("\n\n") { api ->
                    val available = if (ExternalApiRegistry.isAvailable(api)) "READY" else "KEY MISSING"
                    "- ${api.id} [$available] [${api.costTier}]\n  ${api.description}\n  Tags: ${api.tags.joinToString(", ")}"
                }

                ToolResult(true, "Available APIs (${apis.size}):\n\n$formatted")
            } catch (e: Exception) {
                ToolResult(false, "Failed to list APIs: ${e.message}")
            }
        }
    )
}
