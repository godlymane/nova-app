package com.nova.companion.cloud

import android.util.Log

/**
 * Classifies incoming messages to decide local vs cloud routing.
 *
 * CASUAL   -> local GGUF (roasts, hype, quick chat, fitness motivation)
 * COMPLEX  -> OpenAI GPT-4o (coding, analysis, detailed explanations)
 * LIVE_DATA -> OpenAI + web_search tool (weather, news, crypto, scores)
 */
object SmartRouter {

    private const val TAG = "NovaCloud"

    enum class RouteType {
        CASUAL,     // -> local inference
        COMPLEX,    // -> OpenAI chat completions
        LIVE_DATA   // -> OpenAI + web search tool
    }

    // ── Keyword sets ───────────────────────────────────────────

    private val LIVE_DATA_KEYWORDS = setOf(
        "weather", "news", "price", "today", "latest", "current", "score",
        "scores", "stock", "stocks", "crypto", "bitcoin", "btc", "eth",
        "market", "trending", "right now", "happening", "update",
        "who won", "who is winning", "results", "forecast", "temperature",
        "game", "match", "election", "released", "announced"
    )

    private val COMPLEX_KEYWORDS = setOf(
        "explain", "how does", "how do", "compare", "analyze", "analysis",
        "code", "coding", "program", "function", "algorithm", "debug",
        "write me", "create a", "build a", "implement", "difference between",
        "pros and cons", "in detail", "step by step", "tutorial",
        "what is the meaning", "why does", "how would", "design",
        "architecture", "strategy", "optimize", "review my", "help me write",
        "translate", "summarize this", "breakdown"
    )

    private val CASUAL_SIGNALS = setOf(
        "yo", "bro", "sup", "nah", "lol", "lmao", "bruh", "ayo",
        "lets go", "bet", "aight", "wassup", "hype", "roast",
        "motivate", "push me", "gym", "workout", "gains", "cutting",
        "bulk", "grind", "lock in", "whats good", "chill"
    )

    // ── Routing logic ──────────────────────────────────────────

    /**
     * Classify a user message into a route type.
     */
    fun route(message: String): RouteType {
        val lower = message.lowercase().trim()
        val wordCount = lower.split("\\s+".toRegex()).size

        // 1. Check for live data keywords first (highest priority)
        if (containsAny(lower, LIVE_DATA_KEYWORDS)) {
            Log.d(TAG, "Route: LIVE_DATA (keyword match) for: ${message.take(50)}...")
            return RouteType.LIVE_DATA
        }

        // 2. Check for complex indicators
        if (containsAny(lower, COMPLEX_KEYWORDS)) {
            Log.d(TAG, "Route: COMPLEX (keyword match) for: ${message.take(50)}...")
            return RouteType.COMPLEX
        }

        // 3. Long messages with question marks about non-casual topics -> COMPLEX
        if (wordCount > 30 && lower.contains("?")) {
            Log.d(TAG, "Route: COMPLEX (long question) for: ${message.take(50)}...")
            return RouteType.COMPLEX
        }

        // 4. Medium-length messages with question patterns -> COMPLEX
        if (wordCount > 15 && (lower.startsWith("how") || lower.startsWith("what") ||
                    lower.startsWith("why") || lower.startsWith("can you"))
        ) {
            Log.d(TAG, "Route: COMPLEX (question pattern) for: ${message.take(50)}...")
            return RouteType.COMPLEX
        }

        // 5. Short casual messages -> LOCAL
        if (wordCount < 30 || containsAny(lower, CASUAL_SIGNALS)) {
            Log.d(TAG, "Route: CASUAL (short/casual) for: ${message.take(50)}...")
            return RouteType.CASUAL
        }

        // 6. Default: anything else that's long -> COMPLEX
        Log.d(TAG, "Route: COMPLEX (default long) for: ${message.take(50)}...")
        return RouteType.COMPLEX
    }

    private fun containsAny(text: String, keywords: Set<String>): Boolean {
        return keywords.any { keyword -> text.contains(keyword) }
    }
}
