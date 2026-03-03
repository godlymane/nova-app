package com.nova.companion.negotiation

import android.content.Context
import android.util.Log
import com.nova.companion.agent.AgentExecutor
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.CloudLLMService
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

/**
 * NegotiationEngine — Nova's Phase 7 brain for resolving complex goals.
 *
 * Sits between the ChatViewModel and the execution layer. When a message
 * is classified as needing external data or multi-step resolution, this
 * engine takes over:
 *
 *   1. Decides if a simple API call suffices (fast path)
 *   2. If not, invokes MasterPlanner for goal decomposition
 *   3. Executes the plan using cost-aware dispatch:
 *        Free API → Web Scrape → Device Tool → UI Automation
 *   4. Returns a synthesized answer to the user
 *
 * Integration: Called from ChatViewModel for LIVE_DATA and complex
 * AUTOMATION queries. Also exposes negotiation tools to CloudLLMService's
 * agentic tool-calling loop.
 */
object NegotiationEngine {

    private const val TAG = "NegotiationEngine"

    private var initialized = false

    /**
     * Initialize the negotiation engine — registers external API specs
     * and negotiation tools into ToolRegistry.
     *
     * Call once from ChatViewModel or Application.onCreate.
     */
    fun initialize() {
        if (initialized) return

        // Register built-in external APIs
        ExternalApiRegistry.registerBuiltinApis()

        // Register negotiation tools into ToolRegistry
        NegotiationToolExecutors.registerAll(ToolRegistry)

        initialized = true
        Log.i(TAG, "NegotiationEngine initialized — ${ExternalApiRegistry.all().size} APIs, negotiation tools registered")
    }

    /**
     * Attempt to resolve a user query using the cheapest method available.
     *
     * Fast-path checks:
     *  - Direct API match by tags → single API call
     *  - Simple web search → DDG instant answer
     *
     * If fast-path fails, falls through to MasterPlanner for full decomposition.
     *
     * @param userMessage The user's message
     * @param context Android context
     * @param onProgress Status callback for UI
     * @return Resolution result, or null if this engine can't handle it
     */
    suspend fun resolve(
        userMessage: String,
        context: Context,
        onProgress: (String) -> Unit = {}
    ): String? {
        if (!CloudConfig.isOnline(context)) return null

        val lower = userMessage.lowercase()

        // ── Fast path: Direct API match ──────────────────────────

        // Weather
        if (containsAny(lower, setOf("weather", "temperature", "forecast", "how hot", "how cold", "rain"))) {
            val result = tryWeatherFastPath(lower, context)
            if (result != null) return result
        }

        // Crypto prices
        if (containsAny(lower, setOf("bitcoin", "ethereum", "crypto", "btc", "eth", "solana", "coin price"))) {
            val result = tryCryptoFastPath(lower)
            if (result != null) return result
        }

        // Currency conversion
        if (containsAny(lower, setOf("exchange rate", "convert", "usd to", "inr to", "eur to", "currency"))) {
            val result = tryCurrencyFastPath(lower)
            if (result != null) return result
        }

        // Quick facts via DDG instant answer
        if (containsAny(lower, setOf("what is", "who is", "define", "meaning of", "how many", "when did"))) {
            val result = tryInstantAnswerFastPath(userMessage)
            if (result != null) return result
        }

        // ── Slow path: MasterPlanner decomposition ───────────────

        onProgress("Planning the best approach...")

        val plan = MasterPlanner.plan(userMessage, context)
        if (plan == null) {
            Log.d(TAG, "MasterPlanner returned null — falling back")
            return null
        }

        Log.i(TAG, "Plan: ${plan.analysis} — ${plan.steps.size} steps")
        onProgress("Executing ${plan.steps.size}-step plan...")

        return MasterPlanner.execute(
            plan = plan,
            context = context,
            onDeviceTool = { name, params ->
                val tool = ToolRegistry.getTool(name)
                if (tool != null) {
                    tool.executor(context, params)
                } else {
                    ToolResult(false, "Tool '$name' not found")
                }
            },
            onUiAutomation = { description ->
                try {
                    val agent = AgentExecutor(context)
                    val result = agent.execute(description)
                    if (result.goalAchieved) result.summary else "UI automation failed: ${result.summary}"
                } catch (e: Exception) {
                    "UI automation unavailable: ${e.message}"
                }
            },
            onProgress = onProgress
        )
    }

    // ── Fast Paths ───────────────────────────────────────────────

    private suspend fun tryWeatherFastPath(query: String, context: Context): String? {
        val spec = ExternalApiRegistry.get("openweathermap_current") ?: return null
        if (!ExternalApiRegistry.isAvailable(spec)) return null

        // Extract city name — rough heuristic
        val city = extractCity(query) ?: return null

        val response = DynamicApiDispatcher.execute(spec, mapOf(
            "city" to city,
            "api_key" to "__OPENWEATHER_API_KEY__"
        ))

        return if (response.success) {
            formatWeatherResponse(response.rawBody, city)
        } else null
    }

    private suspend fun tryCryptoFastPath(query: String): String? {
        val spec = ExternalApiRegistry.get("coingecko_price") ?: return null

        val coinId = when {
            query.contains("bitcoin") || query.contains("btc") -> "bitcoin"
            query.contains("ethereum") || query.contains("eth") -> "ethereum"
            query.contains("solana") || query.contains("sol") -> "solana"
            query.contains("dogecoin") || query.contains("doge") -> "dogecoin"
            query.contains("cardano") || query.contains("ada") -> "cardano"
            else -> return null
        }

        val currency = if (query.contains("inr") || query.contains("rupee")) "inr" else "usd"

        val response = DynamicApiDispatcher.execute(spec, mapOf(
            "coin_id" to coinId,
            "currency" to currency
        ))

        return if (response.success) {
            formatCryptoResponse(response.rawBody, coinId, currency)
        } else null
    }

    private suspend fun tryCurrencyFastPath(query: String): String? {
        val spec = ExternalApiRegistry.get("exchange_rate") ?: return null

        val currencies = listOf("usd", "eur", "gbp", "inr", "jpy", "aud", "cad")
        val baseCurrency = currencies.firstOrNull { query.contains(it) }?.uppercase() ?: return null

        val response = DynamicApiDispatcher.execute(spec, mapOf("base_currency" to baseCurrency))
        return if (response.success) response.extractedData ?: response.rawBody else null
    }

    private suspend fun tryInstantAnswerFastPath(query: String): String? {
        val spec = ExternalApiRegistry.get("ddg_instant_answer") ?: return null

        val response = DynamicApiDispatcher.execute(spec, mapOf("query" to query))
        if (!response.success) return null

        // Check if DDG actually returned a useful answer (not just empty fields)
        val extracted = response.extractedData ?: return null
        val cleaned = extracted.replace(" | ", "").trim()
        return if (cleaned.length > 10) extracted else null
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun extractCity(query: String): String? {
        // Common patterns: "weather in Mumbai", "temperature in London", "what's the weather like in NYC"
        val patterns = listOf(
            Regex("""(?:weather|temperature|forecast|rain|hot|cold)\s+(?:in|at|for|of)\s+(.+?)(?:\?|$|today|tomorrow|now)""", RegexOption.IGNORE_CASE),
            Regex("""(?:in|at|for)\s+(.+?)\s+(?:weather|temperature|forecast)""", RegexOption.IGNORE_CASE),
            Regex("""(?:how's|hows|how is)\s+(?:the\s+)?(?:weather|temperature)\s+(?:in|at)\s+(.+?)(?:\?|$)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(query)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }

        return null
    }

    private fun formatWeatherResponse(rawJson: String, city: String): String? {
        return try {
            val json = com.google.gson.JsonParser.parseString(rawJson).asJsonObject
            val main = json.getAsJsonObject("main")
            val temp = main.get("temp")?.asDouble ?: return null
            val feelsLike = main.get("feels_like")?.asDouble
            val humidity = main.get("humidity")?.asInt
            val description = json.getAsJsonArray("weather")
                ?.get(0)?.asJsonObject?.get("description")?.asString
            val wind = json.getAsJsonObject("wind")?.get("speed")?.asDouble
            val name = json.get("name")?.asString ?: city

            buildString {
                append("$name: ${temp.toInt()}°C")
                if (description != null) append(", $description")
                if (feelsLike != null) append(". Feels like ${feelsLike.toInt()}°C")
                if (humidity != null) append(", humidity $humidity%")
                if (wind != null) append(", wind ${wind}m/s")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Weather format failed", e)
            null
        }
    }

    private fun formatCryptoResponse(rawJson: String, coinId: String, currency: String): String? {
        return try {
            val json = com.google.gson.JsonParser.parseString(rawJson).asJsonObject
            val coinData = json.getAsJsonObject(coinId) ?: return null
            val price = coinData.get(currency)?.asDouble ?: return null
            val change24h = coinData.get("${currency}_24h_change")?.asDouble

            val symbol = if (currency == "usd") "$" else if (currency == "inr") "₹" else currency.uppercase()
            val priceStr = if (price > 1) String.format("%,.2f", price) else price.toString()
            val coinName = coinId.replaceFirstChar { it.uppercase() }

            buildString {
                append("$coinName: $symbol$priceStr")
                if (change24h != null) {
                    val sign = if (change24h >= 0) "+" else ""
                    append(" ($sign${String.format("%.1f", change24h)}% 24h)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crypto format failed", e)
            null
        }
    }

    private fun containsAny(text: String, keywords: Set<String>): Boolean =
        keywords.any { text.contains(it) }
}
