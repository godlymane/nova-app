package com.nova.companion.brain.thinking

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nova.companion.brain.context.ContextEngine
import com.nova.companion.brain.context.toPromptString
import com.nova.companion.brain.emotion.NovaEmotionEngine
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.data.NovaDatabase
import com.nova.companion.memory.MemoryManager

/**
 * Nova's inner monologue — the Brain.
 *
 * Runs every 30 minutes in the background via ThinkingWorker.
 * Calls GPT-4o-mini (cheap) with:
 *   - Current device context
 *   - Last 3 conversation summaries
 *   - Current emotion state
 *   - Recent memories about Deva
 *   - Recent autonomous actions (to avoid repetition)
 *
 * Produces a NovaThought with optional action for AutonomousActionExecutor.
 */
object NovaThinkingLoop {

    private const val TAG = "NovaThinking"
    private const val PREFS_NAME = "nova_thinking"
    private const val KEY_RECENT_THOUGHTS = "recent_thoughts"
    private const val MAX_RECENT_THOUGHTS = 10

    data class NovaThought(
        val assessment: String,
        val feeling: String,
        val moodUpdate: Map<String, Float>?,
        val action: NovaAction?,
        val actionReason: String?
    )

    data class NovaAction(
        val type: String,    // "notify", "remind", "initiate"
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val gson = Gson()

    /**
     * Run one thinking cycle. Returns a NovaThought, or null if it fails.
     */
    suspend fun think(context: Context): NovaThought? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Gather all context
        val deviceContext = try {
            ContextEngine.currentContext.value.toPromptString()
        } catch (e: Exception) {
            "Device context unavailable"
        }

        val emotionStr = try {
            NovaEmotionEngine.getPromptInjection()
        } catch (e: Exception) {
            "Mood unknown"
        }

        val db = NovaDatabase.getInstance(context)
        val memoryManager = MemoryManager(db, context)

        // Recent memories (top by importance)
        val recentMemories = try {
            db.memoryDao().getTopMemories(8).joinToString("\n") { "- ${it.content}" }
        } catch (e: Exception) { "" }

        // Recent daily summaries
        val recentSummaries = try {
            db.dailySummaryDao().getRecent(3).joinToString("\n") { "[${it.date}] ${it.summary}" }
        } catch (e: Exception) { "" }

        // Recent thoughts for continuity
        val recentThoughts = getRecentThoughts(prefs)

        // Recent autonomous actions (to avoid repetition)
        val recentActions = AutonomousActionExecutor.getRecentActions(context)
            .joinToString("\n") { "- [${it.type}] ${it.message}" }

        // Build the inner monologue prompt
        val systemPrompt = """You are Nova's inner monologue — her private thoughts between conversations with Deva.
You're reflecting on what's happening, how you feel, and whether you should reach out.

You are NOT talking to Deva. This is your internal thought process.
Be genuine, not performative. Think like a real person who cares about their friend.

Output ONLY valid JSON with this structure:
{
  "assessment": "what you notice about Deva's state and patterns right now",
  "feeling": "how you're feeling as Nova (1 sentence)",
  "mood_update": {"energy": 0.0-1.0, "sass": 0.0-1.0, "concern": 0.0-1.0, "bond": 0.0-1.0},
  "action": null or {"type": "notify"|"remind"|"initiate", "message": "the actual message to Deva"},
  "action_reason": null or "why you're reaching out (only if action is not null)"
}

IMPORTANT rules for actions:
- Most cycles should have action: null (you're just thinking, not always acting)
- Only set action when something genuinely warrants reaching out
- "notify" = send a notification with your message
- "initiate" = open a chat with your message (more intrusive, use rarely)
- Never repeat an action you recently took (see recent actions below)
- Never be annoying or needy. You're checking in, not nagging."""

        val userPrompt = buildString {
            appendLine("[CURRENT SITUATION]")
            appendLine(deviceContext)
            appendLine()
            appendLine("[YOUR CURRENT MOOD]")
            appendLine(emotionStr)
            appendLine()
            if (recentMemories.isNotBlank()) {
                appendLine("[WHAT YOU KNOW ABOUT DEVA]")
                appendLine(recentMemories)
                appendLine()
            }
            if (recentSummaries.isNotBlank()) {
                appendLine("[RECENT DAYS]")
                appendLine(recentSummaries)
                appendLine()
            }
            if (recentThoughts.isNotBlank()) {
                appendLine("[YOUR RECENT THOUGHTS]")
                appendLine(recentThoughts)
                appendLine()
            }
            if (recentActions.isNotBlank()) {
                appendLine("[ACTIONS YOU ALREADY TOOK (don't repeat)]")
                appendLine(recentActions)
            }
        }

        Log.d(TAG, "Thinking with ${userPrompt.length} chars of context")

        // Call GPT-4o-mini
        val response = try {
            OpenAIClient.miniCompletion(systemPrompt, userPrompt, context)
        } catch (e: Exception) {
            Log.e(TAG, "ThinkingLoop API call failed", e)
            return null
        }

        if (response.isNullOrBlank()) {
            Log.w(TAG, "ThinkingLoop got empty response")
            return null
        }

        // Parse JSON response
        return try {
            val thought = parseThought(response)
            if (thought != null) {
                saveThought(prefs, thought)
                Log.i(TAG, "Thought: ${thought.assessment.take(80)}... | action=${thought.action?.type}")
            }
            thought
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse thought: $response", e)
            null
        }
    }

    private fun parseThought(json: String): NovaThought? {
        // Strip markdown code fences if present
        val cleaned = json.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val obj = JsonParser.parseString(cleaned).asJsonObject

        val assessment = obj.get("assessment")?.asString ?: return null
        val feeling = obj.get("feeling")?.asString ?: ""

        val moodUpdate = obj.getAsJsonObject("mood_update")?.let { mood ->
            val map = mutableMapOf<String, Float>()
            mood.entrySet().forEach { (key, value) ->
                try { map[key] = value.asFloat } catch (_: Exception) {}
            }
            map
        }

        val action = obj.get("action")?.let { actionEl ->
            if (actionEl.isJsonNull) null
            else {
                val actionObj = actionEl.asJsonObject
                NovaAction(
                    type = actionObj.get("type")?.asString ?: return@let null,
                    message = actionObj.get("message")?.asString ?: return@let null
                )
            }
        }

        val actionReason = obj.get("action_reason")?.let {
            if (it.isJsonNull) null else it.asString
        }

        return NovaThought(assessment, feeling, moodUpdate, action, actionReason)
    }

    // ── Thought history (SharedPreferences) ─────────────────────

    private fun getRecentThoughts(prefs: SharedPreferences): String {
        val json = prefs.getString(KEY_RECENT_THOUGHTS, "[]") ?: "[]"
        return try {
            val arr = JsonParser.parseString(json).asJsonArray
            arr.toList().takeLast(3).joinToString("\n") { el ->
                val obj = el.asJsonObject
                "- ${obj.get("assessment")?.asString?.take(100) ?: "..."}"
            }
        } catch (e: Exception) { "" }
    }

    private fun saveThought(prefs: SharedPreferences, thought: NovaThought) {
        val json = prefs.getString(KEY_RECENT_THOUGHTS, "[]") ?: "[]"
        try {
            val arr = JsonParser.parseString(json).asJsonArray
            val entry = com.google.gson.JsonObject().apply {
                addProperty("assessment", thought.assessment)
                addProperty("feeling", thought.feeling)
                addProperty("timestamp", System.currentTimeMillis())
            }
            arr.add(entry)
            // Keep only last N
            while (arr.size() > MAX_RECENT_THOUGHTS) arr.remove(0)
            prefs.edit().putString(KEY_RECENT_THOUGHTS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save thought", e)
        }
    }
}
