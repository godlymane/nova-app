package com.nova.companion.negotiation

import android.util.Log
import com.nova.companion.BuildConfig
import com.nova.companion.cloud.CloudConfig

/**
 * Dynamic registry of external API specs that Nova can call.
 *
 * Ships with a set of bundled free/cheap APIs (weather, search, news, crypto).
 * Specs can also be added at runtime from JSON fetched from a remote catalog.
 */
object ExternalApiRegistry {

    private const val TAG = "ExternalApiRegistry"

    private val specs = mutableMapOf<String, ExternalApiSpec>()

    /** All registered specs */
    fun all(): List<ExternalApiSpec> = specs.values.toList()

    /** Find specs whose tags intersect the given set */
    fun findByTags(tags: Set<String>): List<ExternalApiSpec> =
        specs.values.filter { it.tags.intersect(tags).isNotEmpty() }

    /** Find a single spec by id */
    fun get(id: String): ExternalApiSpec? = specs[id]

    /** Register a spec (replaces if id exists) */
    fun register(spec: ExternalApiSpec) {
        specs[spec.id] = spec
        Log.d(TAG, "Registered API: ${spec.id} [${spec.costTier}]")
    }

    /** Check if a spec's API key is available */
    fun isAvailable(spec: ExternalApiSpec): Boolean {
        if (spec.apiKeyField == null) return true   // no key needed
        return resolveApiKey(spec.apiKeyField).isNotBlank()
    }

    /** Resolve an API key name to its actual value from BuildConfig/CloudConfig */
    fun resolveApiKey(fieldName: String): String {
        return when (fieldName) {
            "OPENAI_API_KEY" -> CloudConfig.openAiApiKey
            "GEMINI_API_KEY" -> try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
            "OPENWEATHER_API_KEY" -> try { BuildConfig.OPENWEATHER_API_KEY } catch (_: Exception) { "" }
            else -> {
                // Try BuildConfig reflection as fallback
                try {
                    val field = BuildConfig::class.java.getField(fieldName)
                    field.get(null) as? String ?: ""
                } catch (_: Exception) { "" }
            }
        }
    }

    // ── Bundled API Specs ─────────────────────────────────────────

    fun registerBuiltinApis() {
        register(openWeatherCurrent)
        register(duckDuckGoInstantAnswer)
        register(duckDuckGoSearch)
        register(coinGeckoPrice)
        register(coinGeckoTrending)
        register(newsApiTopHeadlines)
        register(exchangeRateApi)
        register(openTriviaApi)
        register(numbersApi)
        register(httpBin)
        Log.i(TAG, "Registered ${specs.size} built-in API specs")
    }

    // ── Weather ───────────────────────────────────────────────────

    private val openWeatherCurrent = ExternalApiSpec(
        id = "openweathermap_current",
        name = "OpenWeatherMap Current",
        description = "Get current weather conditions for a city. Returns temperature, humidity, description, wind speed.",
        method = "GET",
        urlTemplate = "https://api.openweathermap.org/data/2.5/weather?q={city}&appid={api_key}&units=metric",
        parameters = listOf(
            ApiParamSpec("city", "string", "City name (e.g. 'London' or 'Mumbai,IN')", required = true, location = "path"),
            ApiParamSpec("api_key", "string", "OpenWeatherMap API key", required = true, location = "path", defaultValue = "__OPENWEATHER_API_KEY__")
        ),
        responseExtractor = "main.temp,weather[0].description,main.humidity,wind.speed,name",
        costTier = CostTier.FREE,
        rateLimit = 60,
        apiKeyField = "OPENWEATHER_API_KEY",
        tags = setOf("weather", "temperature", "forecast", "climate")
    )

    // ── Search ────────────────────────────────────────────────────

    private val duckDuckGoInstantAnswer = ExternalApiSpec(
        id = "ddg_instant_answer",
        name = "DuckDuckGo Instant Answer",
        description = "Get an instant factual answer for a query. Returns abstract, answer, or related topics. Good for quick facts, definitions, and Wikipedia-style info.",
        method = "GET",
        urlTemplate = "https://api.duckduckgo.com/?q={query}&format=json&no_html=1&skip_disambig=1",
        parameters = listOf(
            ApiParamSpec("query", "string", "Search query", required = true, location = "path")
        ),
        responseExtractor = "Abstract,Answer,AbstractText,RelatedTopics[0].Text",
        costTier = CostTier.FREE,
        rateLimit = 30,
        tags = setOf("search", "facts", "definition", "wiki", "lookup")
    )

    private val duckDuckGoSearch = ExternalApiSpec(
        id = "ddg_web_search",
        name = "DuckDuckGo Web Search",
        description = "Search the web using DuckDuckGo's HTML endpoint. Returns search result titles and snippets. Use for current events, prices, or anything requiring up-to-date info.",
        method = "GET",
        urlTemplate = "https://html.duckduckgo.com/html/?q={query}",
        parameters = listOf(
            ApiParamSpec("query", "string", "Search query", required = true, location = "path")
        ),
        costTier = CostTier.FREE,
        rateLimit = 10,
        tags = setOf("search", "web", "current_events", "prices", "live_data")
    )

    // ── Crypto / Finance ──────────────────────────────────────────

    private val coinGeckoPrice = ExternalApiSpec(
        id = "coingecko_price",
        name = "CoinGecko Price",
        description = "Get current cryptocurrency price in USD (or other currency). Supports Bitcoin, Ethereum, and 10000+ coins.",
        method = "GET",
        urlTemplate = "https://api.coingecko.com/api/v3/simple/price?ids={coin_id}&vs_currencies={currency}&include_24hr_change=true",
        parameters = listOf(
            ApiParamSpec("coin_id", "string", "CoinGecko coin id (e.g. 'bitcoin', 'ethereum', 'solana')", required = true, location = "path"),
            ApiParamSpec("currency", "string", "Target currency code (e.g. 'usd', 'inr', 'eur')", required = false, location = "path", defaultValue = "usd")
        ),
        costTier = CostTier.FREE,
        rateLimit = 30,
        tags = setOf("crypto", "price", "bitcoin", "ethereum", "finance", "currency")
    )

    private val coinGeckoTrending = ExternalApiSpec(
        id = "coingecko_trending",
        name = "CoinGecko Trending",
        description = "Get trending cryptocurrencies on CoinGecko in the last 24 hours.",
        method = "GET",
        urlTemplate = "https://api.coingecko.com/api/v3/search/trending",
        parameters = emptyList(),
        costTier = CostTier.FREE,
        rateLimit = 30,
        tags = setOf("crypto", "trending", "finance")
    )

    private val exchangeRateApi = ExternalApiSpec(
        id = "exchange_rate",
        name = "Exchange Rate API",
        description = "Get current exchange rates between currencies. Convert USD to INR, EUR to GBP, etc.",
        method = "GET",
        urlTemplate = "https://open.er-api.com/v6/latest/{base_currency}",
        parameters = listOf(
            ApiParamSpec("base_currency", "string", "Base currency code (e.g. 'USD', 'EUR', 'INR')", required = true, location = "path")
        ),
        costTier = CostTier.FREE,
        rateLimit = 60,
        tags = setOf("currency", "exchange_rate", "finance", "forex", "conversion")
    )

    // ── News ──────────────────────────────────────────────────────

    private val newsApiTopHeadlines = ExternalApiSpec(
        id = "newsapi_headlines",
        name = "NewsAPI Top Headlines",
        description = "Get top news headlines, optionally filtered by country or search query. Requires API key.",
        method = "GET",
        urlTemplate = "https://newsapi.org/v2/top-headlines?country={country}&q={query}&pageSize=5&apiKey={api_key}",
        parameters = listOf(
            ApiParamSpec("country", "string", "2-letter country code (e.g. 'us', 'in', 'gb')", required = false, location = "path", defaultValue = "us"),
            ApiParamSpec("query", "string", "Optional search keyword", required = false, location = "path", defaultValue = ""),
            ApiParamSpec("api_key", "string", "NewsAPI key", required = true, location = "path", defaultValue = "__NEWSAPI_KEY__")
        ),
        costTier = CostTier.FREE,
        rateLimit = 30,
        apiKeyField = "NEWSAPI_KEY",
        tags = setOf("news", "headlines", "current_events")
    )

    // ── Misc / Fun ────────────────────────────────────────────────

    private val openTriviaApi = ExternalApiSpec(
        id = "opentdb_trivia",
        name = "Open Trivia DB",
        description = "Get random trivia questions. Useful for quizzes and fun facts.",
        method = "GET",
        urlTemplate = "https://opentdb.com/api.php?amount={count}&type=multiple",
        parameters = listOf(
            ApiParamSpec("count", "integer", "Number of questions (1-50)", required = false, location = "path", defaultValue = "5")
        ),
        costTier = CostTier.FREE,
        rateLimit = 60,
        tags = setOf("trivia", "quiz", "fun", "games")
    )

    private val numbersApi = ExternalApiSpec(
        id = "numbers_fact",
        name = "Numbers API",
        description = "Get interesting facts about numbers, dates, or math.",
        method = "GET",
        urlTemplate = "http://numbersapi.com/{number}/{type}?json",
        parameters = listOf(
            ApiParamSpec("number", "string", "A number or 'random'", required = true, location = "path"),
            ApiParamSpec("type", "string", "Type: trivia, math, date, or year", required = false, location = "path", defaultValue = "trivia")
        ),
        costTier = CostTier.FREE,
        rateLimit = 60,
        tags = setOf("facts", "numbers", "math", "fun")
    )

    // ── Debug / Test ──────────────────────────────────────────────

    private val httpBin = ExternalApiSpec(
        id = "httpbin_get",
        name = "HTTPBin Echo",
        description = "Debug endpoint — echoes back request details. For testing API dispatch only.",
        method = "GET",
        urlTemplate = "https://httpbin.org/get",
        parameters = emptyList(),
        costTier = CostTier.FREE,
        rateLimit = 60,
        tags = setOf("debug", "test")
    )
}
