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
        "weather", "news", "price", "latest", "score",
        "scores", "stock", "stocks", "crypto", "bitcoin", "btc",
        "market", "trending", "right now", "happening", "update",
        "who won", "who is winning", "results", "forecast", "temperature",
        "game", "match", "election", "released", "announced",
        // Phase 7: NegotiationEngine-aligned keywords
        "exchange rate", "convert", "currency", "usd", "inr", "eur",
        "solana", "dogecoin", "cardano", "coin price",
        "trivia", "quiz", "headlines", "how much is"
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
     * Classify a user message into a route type using a scoring system.
     * Each keyword set accumulates a score; the highest score wins.
     * Multi-word phrases score higher (3 pts) than single words (2 pts).
     */
    fun route(message: String): RouteType {
        val lower = message.lowercase().trim()
        val wordCount = lower.split("\\s+".toRegex()).size

        // Calculate scores for each route type
        var liveDataScore = 0
        var complexScore = 0
        var casualScore = 0

        // Score LIVE_DATA keywords
        for (keyword in LIVE_DATA_KEYWORDS) {
            if (Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(lower)) {
                liveDataScore += if (keyword.contains(" ")) 3 else 2
            }
        }

        // Score COMPLEX keywords
        for (keyword in COMPLEX_KEYWORDS) {
            if (Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(lower)) {
                complexScore += if (keyword.contains(" ")) 3 else 2
            }
        }

        // Score CASUAL signals
        for (keyword in CASUAL_SIGNALS) {
            if (Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(lower)) {
                casualScore += 2
            }
        }

        // Length-based scoring adjustments
        if (wordCount > 15 && lower.contains("?")) complexScore += 2
        if (wordCount > 12 && (lower.startsWith("how") || lower.startsWith("what") ||
                    lower.startsWith("why") || lower.startsWith("can you"))) complexScore += 2

        // Short casual messages get a bonus
        if (wordCount < 12) casualScore += 2

        // Log the scores
        Log.d(TAG, "Route scores — LIVE_DATA=$liveDataScore, COMPLEX=$complexScore, CASUAL=$casualScore for: ${message.take(50)}...")

        // Return highest-scoring route
        return when {
            liveDataScore > 0 && liveDataScore >= complexScore && liveDataScore >= casualScore -> {
                Log.d(TAG, "Route: LIVE_DATA (score=$liveDataScore)")
                RouteType.LIVE_DATA
            }
            complexScore > 0 && complexScore >= casualScore -> {
                Log.d(TAG, "Route: COMPLEX (score=$complexScore)")
                RouteType.COMPLEX
            }
            else -> {
                Log.d(TAG, "Route: CASUAL (score=$casualScore, wordCount=$wordCount)")
                RouteType.CASUAL
            }
        }
    }

    private fun containsAny(text: String, keywords: Set<String>): Boolean {
        return keywords.any { keyword ->
            val pattern = "\\b${Regex.escape(keyword)}\\b"
            Regex(pattern).containsMatchIn(text)
        }
    }
}
