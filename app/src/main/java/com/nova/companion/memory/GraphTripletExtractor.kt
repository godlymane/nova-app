package com.nova.companion.memory

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.OpenAIClient

/**
 * Extracts Node-Edge-Node triplets from conversation text using an LLM.
 *
 * Instead of storing raw paragraph summaries, this converts conversations into
 * structured knowledge graph entries. GPT-4o-mini does the extraction cheaply.
 *
 * Input:  "My sister Sarah really loves oat milk lattes, she gets one every morning"
 * Output: [
 *   {"node1": "Sarah", "node1_type": "person", "edge": "is sister of", "node2": "User", "node2_type": "person"},
 *   {"node1": "Sarah", "node1_type": "person", "edge": "loves", "node2": "Oat Milk Latte", "node2_type": "food"},
 *   {"node1": "Sarah", "node1_type": "person", "edge": "drinks every morning", "node2": "Oat Milk Latte", "node2_type": "food"}
 * ]
 */
object GraphTripletExtractor {

    private const val TAG = "GraphTripletExtractor"
    private val gson = Gson()

    /**
     * The extraction prompt sent to GPT-4o-mini.
     * Designed to be cheap (~100 input tokens + conversation text) and reliable.
     */
    private val EXTRACTION_PROMPT = """
You extract knowledge graph triplets from conversations. The conversation is between a user named "User" and an AI named "Nova".

Extract ONLY factual relationships — things that are true about people, places, preferences, events, or attributes. Skip small talk, jokes, or filler.

Each triplet has:
- node1: entity name (capitalize properly)
- node1_type: one of [person, place, food, brand, concept, activity, preference, event, time, body_stat, tech, goal, emotion]
- edge: the relationship (short verb phrase, lowercase)
- node2: entity name (capitalize properly)
- node2_type: same type options as node1_type

RULES:
- Always refer to the human speaker as "User" (node name).
- Normalize entity names: "my gf Sarah" → node1="Sarah", "Blayzex brand" → "Blayzex".
- Keep edge labels short and reusable: "likes", "is sibling of", "lives in", "works on", "weighs", "has goal".
- Extract 1-8 triplets per exchange. If nothing factual, return empty array [].
- Do NOT extract opinions about Nova, meta-conversation, or instructions to Nova.

Respond with ONLY a JSON array. No explanation.

Example input:
User: "My sister Sarah loves oat milk lattes. I just hit 150kg squat today."
Nova: "That's insane bro, new PR?"

Example output:
[{"node1":"Sarah","node1_type":"person","edge":"is sister of","node2":"User","node2_type":"person"},{"node1":"Sarah","node1_type":"person","edge":"loves","node2":"Oat Milk Latte","node2_type":"food"},{"node1":"User","node1_type":"person","edge":"hit squat pr","node2":"150kg","node2_type":"body_stat"}]
""".trimIndent()

    /**
     * Extract triplets from a user message + Nova response.
     * Uses GPT-4o-mini for cost efficiency (~$0.15/1M input).
     *
     * @return List of extracted triplets, or empty list on failure/offline
     */
    suspend fun extract(
        userMessage: String,
        novaResponse: String,
        context: Context
    ): List<Triplet> {
        if (!CloudConfig.isOnline(context) || !CloudConfig.hasOpenAiKey()) {
            Log.d(TAG, "Offline or no API key — skipping LLM extraction")
            return extractFallback(userMessage)
        }

        val conversationText = "User: \"$userMessage\"\nNova: \"$novaResponse\""

        return try {
            val result = OpenAIClient.miniCompletion(
                systemPrompt = EXTRACTION_PROMPT,
                userPrompt = conversationText,
                context = context
            )

            if (result.isNullOrBlank()) {
                Log.w(TAG, "LLM returned empty response for triplet extraction")
                return extractFallback(userMessage)
            }

            parseTriplets(result)
        } catch (e: Exception) {
            Log.e(TAG, "LLM triplet extraction failed", e)
            extractFallback(userMessage)
        }
    }

    /**
     * Parse the LLM's JSON array response into Triplet objects.
     * Handles messy LLM output (markdown fences, trailing commas, etc.).
     */
    private fun parseTriplets(raw: String): List<Triplet> {
        return try {
            // Strip markdown code fences if present
            val cleaned = raw
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val type = object : TypeToken<List<RawTriplet>>() {}.type
            val parsed: List<RawTriplet> = gson.fromJson(cleaned, type)

            parsed.mapNotNull { raw ->
                if (raw.node1.isNullOrBlank() || raw.edge.isNullOrBlank() || raw.node2.isNullOrBlank()) {
                    null
                } else {
                    Triplet(
                        node1 = raw.node1.trim(),
                        node1Type = (raw.node1_type ?: "concept").trim().lowercase(),
                        edge = raw.edge.trim().lowercase(),
                        node2 = raw.node2.trim(),
                        node2Type = (raw.node2_type ?: "concept").trim().lowercase()
                    )
                }
            }.also {
                Log.d(TAG, "Extracted ${it.size} triplets from LLM")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse triplets JSON: ${raw.take(200)}", e)
            emptyList()
        }
    }

    /**
     * Offline fallback: use simple regex patterns to extract basic triplets.
     * Much less intelligent than the LLM but works without network.
     */
    private fun extractFallback(userMessage: String): List<Triplet> {
        val msg = userMessage.lowercase().trim()
        val triplets = mutableListOf<Triplet>()

        // Relationship patterns
        val relPatterns = listOf(
            Regex("""my (?:sister|sis) (?:is )?([A-Z][a-z]+)""") to "is sister of",
            Regex("""my (?:brother|bro) (?:is )?([A-Z][a-z]+)""") to "is brother of",
            Regex("""my (?:girlfriend|gf) (?:is )?([A-Z][a-z]+)""") to "is girlfriend of",
            Regex("""my (?:mom|mother) (?:is )?([A-Z][a-z]+)""") to "is mother of",
            Regex("""my (?:dad|father) (?:is )?([A-Z][a-z]+)""") to "is father of",
            Regex("""my (?:best friend|bestie) (?:is )?([A-Z][a-z]+)""") to "is best friend of"
        )
        for ((pattern, relation) in relPatterns) {
            pattern.find(userMessage)?.let { match ->
                triplets.add(
                    Triplet(
                        node1 = match.groupValues[1],
                        node1Type = "person",
                        edge = relation,
                        node2 = "User",
                        node2Type = "person"
                    )
                )
            }
        }

        // Location pattern
        val locPattern = Regex("""(?:i live in|i'm from|based in|living in)\s+([A-Z][a-z]+(?:\s[A-Z][a-z]+)?)""")
        locPattern.find(userMessage)?.let { match ->
            triplets.add(
                Triplet(
                    node1 = "User",
                    node1Type = "person",
                    edge = "lives in",
                    node2 = match.groupValues[1].trim(),
                    node2Type = "place"
                )
            )
        }

        // Food preference
        val foodPattern = Regex("""(?:i love|favorite food is|addicted to|can't live without)\s+([a-z\s]{3,25})""")
        foodPattern.find(msg)?.let { match ->
            triplets.add(
                Triplet(
                    node1 = "User",
                    node1Type = "person",
                    edge = "loves",
                    node2 = match.groupValues[1].trim().replaceFirstChar { it.uppercase() },
                    node2Type = "food"
                )
            )
        }

        if (triplets.isNotEmpty()) {
            Log.d(TAG, "Fallback extraction: ${triplets.size} triplets")
        }
        return triplets
    }

    /** JSON deserialization model matching the LLM output format */
    private data class RawTriplet(
        val node1: String? = null,
        val node1_type: String? = null,
        val edge: String? = null,
        val node2: String? = null,
        val node2_type: String? = null
    )
}

/**
 * A clean, validated triplet ready for graph insertion.
 */
data class Triplet(
    val node1: String,
    val node1Type: String,
    val edge: String,
    val node2: String,
    val node2Type: String
)
