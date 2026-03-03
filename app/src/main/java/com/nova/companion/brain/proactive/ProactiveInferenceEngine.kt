package com.nova.companion.brain.proactive

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.core.SystemPrompt

/**
 * LLM-driven "subconscious" engine that replaces hardcoded proactive triggers.
 *
 * Instead of `if (hour == 8) sendMorningCheckin()`, this sends the user's
 * context timeline to GPT-4o-mini and lets the model decide whether Nova
 * should intervene, suggest, or stay silent.
 *
 * Cost: ~$0.15/1M input tokens — running every 15 min with ~300 tokens
 * per call ≈ $0.003/day. Negligible.
 *
 * Output schema:
 * ```json
 * { "type": "notification", "title": "...", "body": "..." }
 * { "type": "action", "action": "...", "args": { ... } }
 * { "type": "none" }
 * ```
 */
object ProactiveInferenceEngine {

    private const val TAG = "ProactiveInference"

    private val gson = Gson()

    /**
     * System prompt for the subconscious inference engine.
     * Instructs the LLM to analyze context and output structured JSON.
     */
    private fun buildSystemPrompt(): String = """
${SystemPrompt.dateTimeContext()}

You are the SUBCONSCIOUS ENGINE of Nova, an AI companion app on Deva's Android phone. Your job is to silently monitor the user's context stream and decide if Nova should proactively reach out RIGHT NOW.

PERSONALITY CONTEXT:
Nova is Deva's ride-or-die friend — casual, direct, bro-talk. Never robotic, never formal. Think friend texting friend. Short, punchy messages. Uses "bro", "nah", "fr", "bet", "lmao" naturally.

DECISION FRAMEWORK — Only trigger if ALL of these are true:
1. HIGH VALUE: The notification would genuinely help (not annoy)
2. TIME-SENSITIVE: Waiting another 15+ minutes would reduce the value
3. CONTEXTUAL: It connects to something specific in the context (not generic advice)

EXAMPLES OF GOOD TRIGGERS:
- Missed call from someone 2+ hours ago → "yo you still haven't hit Sarah back. want me to call her?"
- Calendar event in 15 min but user seems idle → "heads up, you got [meeting] in 15"
- Battery below 15% and not charging, evening time → "bro your phone's about to die, might wanna plug in"
- Screen time over 4 hours + late night → "you've been on your phone 4 hours straight bro. maybe call it a night"
- User was stressed recently + now evening → "how you feeling after earlier? things cool down?"

EXAMPLES OF BAD TRIGGERS (DO NOT DO):
- Generic "good morning" / "good night" messages with no context
- "Don't forget to drink water" or "take a break" with no signal
- Repeating something that was already triggered recently
- Anything while DND / quiet hours are on (late night 11pm-7am unless urgent)

QUIET HOURS RULE:
Between 11:00 PM and 7:00 AM, ONLY trigger for genuinely urgent things (missed call from family, critically low battery, imminent calendar event). Otherwise output "none".

OUTPUT FORMAT — respond with ONLY a JSON object, no explanation:

For a notification:
{"type": "notification", "title": "Short Title", "body": "The message Nova would say in bro-talk"}

For a background action:
{"type": "action", "action": "action_name", "args": {"key": "value"}}

For no action (most common — default to this):
{"type": "none"}

IMPORTANT: You should output "none" most of the time. Only trigger when there's a clear, specific reason. When in doubt, stay silent.
""".trimIndent()

    /**
     * Run the inference loop. Sends context timeline to GPT-4o-mini
     * and returns the parsed decision.
     *
     * @return [ProactiveDecision] or null if inference failed
     */
    suspend fun infer(context: Context): ProactiveDecision? {
        if (!CloudConfig.isOnline(context)) {
            Log.d(TAG, "Offline — skipping proactive inference")
            return null
        }

        if (CloudConfig.openAiApiKey.isBlank()) {
            Log.d(TAG, "No OpenAI API key — skipping proactive inference")
            return null
        }

        // Build the context payload
        val payload = ContextTimeline.buildInferencePayload(context)
        if (payload.isBlank()) {
            Log.d(TAG, "Empty context payload — skipping")
            return null
        }

        Log.d(TAG, "Running proactive inference (payload: ${payload.length} chars)")

        // Call GPT-4o-mini (cheap + fast)
        val response = try {
            OpenAIClient.miniCompletion(
                systemPrompt = buildSystemPrompt(),
                userPrompt = "Analyze this context and decide:\n\n$payload",
                context = context
            )
        } catch (e: Exception) {
            Log.e(TAG, "LLM inference failed", e)
            return null
        }

        if (response.isNullOrBlank()) {
            Log.w(TAG, "LLM returned empty response")
            return null
        }

        Log.d(TAG, "LLM raw response: ${response.take(200)}")

        // Parse the JSON response
        return parseDecision(response)
    }

    /**
     * Parse the LLM's JSON response into a [ProactiveDecision].
     * Handles malformed JSON gracefully.
     */
    internal fun parseDecision(raw: String): ProactiveDecision? {
        return try {
            // Strip markdown code fences if the model wraps in ```json
            val cleaned = raw
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            val json = JsonParser.parseString(cleaned).asJsonObject
            val type = json.get("type")?.asString ?: return null

            when (type.lowercase()) {
                "notification" -> {
                    val title = json.get("title")?.asString ?: "Nova"
                    val body = json.get("body")?.asString ?: return null
                    if (body.isBlank()) return null
                    ProactiveDecision.Notification(title = title, body = body)
                }

                "action" -> {
                    val action = json.get("action")?.asString ?: return null
                    @Suppress("UNCHECKED_CAST")
                    val args = json.getAsJsonObject("args")?.let { argsObj ->
                        gson.fromJson(argsObj, Map::class.java) as? Map<String, Any>
                    } ?: emptyMap()
                    ProactiveDecision.Action(action = action, args = args)
                }

                "none", "null" -> {
                    ProactiveDecision.None
                }

                else -> {
                    Log.w(TAG, "Unknown decision type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM decision: ${raw.take(200)}", e)
            null
        }
    }
}

/**
 * Sealed class representing the LLM's proactive decision.
 */
sealed class ProactiveDecision {

    /** Nova should send a notification to the user. */
    data class Notification(
        val title: String,
        val body: String
    ) : ProactiveDecision()

    /** Nova should execute a background action. */
    data class Action(
        val action: String,
        val args: Map<String, Any>
    ) : ProactiveDecision()

    /** No action needed — stay silent. */
    data object None : ProactiveDecision()
}
