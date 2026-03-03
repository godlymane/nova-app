package com.nova.companion.inference

import android.content.Context
import android.util.Log
import com.nova.companion.core.SystemPrompt
import com.nova.companion.brain.context.ContextInjector
import com.nova.companion.memory.MemoryManager
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * LocalInferenceClient — mirrors OpenAIClient's interface but runs entirely on-device
 * via NovaInference (llama.cpp). No API calls, no internet required.
 *
 * Capabilities:
 * - chatCompletion(): Non-streaming text generation
 * - chatCompletionStreaming(): Streaming text generation with token callbacks
 * - localToolCompletion(): Local tool-calling loop for offline automation
 * - quickResponse(): Ultra-fast short response for gap-filling (e.g., "I'm on it...")
 *
 * The local model uses the same ### System/User/Assistant prompt template as NovaInference.
 * For tool calling, we inject tool descriptions into the system prompt and parse
 * structured JSON tool calls from the model output.
 */
object LocalInferenceClient {

    private const val TAG = "LocalInference"

    // Max tool rounds for local model (fewer than cloud since SLM is less capable)
    private const val MAX_LOCAL_TOOL_ROUNDS = 3

    // ── Chat Completion (mirrors OpenAIClient.chatCompletion) ────────

    /**
     * Non-streaming chat completion using local llama.cpp model.
     *
     * @param userMessage The user's input text
     * @param conversationHistory Recent chat as list of (role, content) pairs
     * @param context Android context
     * @param injectedContext Optional memory/device context to inject
     * @return Response text, or null if model not ready
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun chatCompletion(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        context: Context,  // kept for API parity with OpenAIClient
        injectedContext: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!NovaInference.isReady()) {
            Log.w(TAG, "Local model not ready for chatCompletion")
            return@withContext null
        }

        val history = buildHistoryString(conversationHistory)
        val memoryContext = injectedContext ?: ""

        try {
            val result = NovaInference.generate(
                userMessage = userMessage,
                history = history,
                memoryContext = memoryContext
            )
            result.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Local chatCompletion error", e)
            null
        }
    }

    /**
     * Streaming chat completion — tokens arrive via callback.
     * Mirrors OpenAIClient.chatCompletionStreaming but runs locally.
     */
    fun chatCompletionStreaming(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        injectedContext: String? = null,
        scope: CoroutineScope,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ): Job? {
        if (!NovaInference.isReady()) {
            onError("Local model not loaded")
            return null
        }

        val history = buildHistoryString(conversationHistory)
        val memoryContext = injectedContext ?: ""
        val fullText = StringBuilder()

        return NovaInference.generateStreaming(
            userMessage = userMessage,
            history = history,
            memoryContext = memoryContext,
            scope = scope,
            onToken = { token ->
                fullText.append(token)
                onToken(token)
            },
            onComplete = {
                onComplete(fullText.toString().trim())
            },
            onError = { error ->
                onError(error.message ?: "Local inference error")
            }
        )
    }

    // ── Quick Response (for gap-filling) ─────────────────────────────

    /**
     * Generate an ultra-fast acknowledgment response.
     * Uses minimal tokens and low-context prompt for instant replies.
     *
     * Used for wake-word gap-filling: local model says "I'm on it..." while
     * the cloud processes the full request.
     *
     * @param userMessage What the user just said
     * @return Quick acknowledgment text (1 sentence max)
     */
    suspend fun quickResponse(userMessage: String): String? = withContext(Dispatchers.IO) {
        if (!NovaInference.isReady()) return@withContext null

        // Save and restore settings for ultra-fast generation
        val savedMaxTokens = NovaInference.maxTokens
        val savedTemp = NovaInference.temperature

        try {
            NovaInference.maxTokens = 32  // Very short response
            NovaInference.temperature = 0.9f  // Slightly creative for natural ack

            // Quick ack via normal generate — system prompt already defines Nova's personality
            val result = NovaInference.generate(userMessage = userMessage)
            result.trim().take(80) // Cap at 80 chars for speed
        } catch (e: Exception) {
            Log.e(TAG, "Quick response error", e)
            null
        } finally {
            NovaInference.maxTokens = savedMaxTokens
            NovaInference.temperature = savedTemp
        }
    }

    // ── Local Tool Calling ───────────────────────────────────────────

    /**
     * Process a user message with local tool execution capability.
     *
     * The local SLM generates structured tool calls in JSON format.
     * We parse these, execute the tools, and feed results back for a final response.
     *
     * Only supports offline-capable tools (Tier 1/2: alarms, timers, flashlight,
     * volume, brightness, open app, etc.)
     *
     * @param userMessage User's request
     * @param context Android context for tool execution
     * @param offlineToolNames Set of tool names available offline
     * @param onToolCall Callback when a tool is being executed
     * @param onResponse Final natural language response
     * @param onError Error callback
     */
    suspend fun localToolCompletion(
        userMessage: String,
        context: Context,
        offlineToolNames: Set<String>,
        onToolCall: (String) -> Unit = {},
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!NovaInference.isReady()) {
            onError("Local model not loaded")
            return@withContext
        }

        val toolDescriptions = buildLocalToolDescriptions(offlineToolNames)
        val toolPrompt = buildToolCallingPrompt(userMessage, toolDescriptions)

        try {
            var round = 0
            var currentPrompt = toolPrompt
            val toolResults = mutableListOf<Pair<String, String>>()

            while (round < MAX_LOCAL_TOOL_ROUNDS) {
                val rawOutput = NovaInference.generate(userMessage = currentPrompt)

                if (rawOutput.isBlank()) {
                    onError("Local model returned empty response")
                    return@withContext
                }

                // Try to parse tool calls from the output
                val parsedToolCalls = parseToolCalls(rawOutput)

                if (parsedToolCalls.isEmpty()) {
                    // No tool call — this is the final response
                    val cleanResponse = cleanToolResponse(rawOutput)
                    withContext(Dispatchers.Main) { onResponse(cleanResponse) }
                    return@withContext
                }

                // Execute all parsed tool calls and collect results
                val roundResults = mutableListOf<Pair<String, String>>()
                for ((toolName, toolArgs) in parsedToolCalls) {
                    Log.i(TAG, "Local tool call: $toolName($toolArgs)")
                    withContext(Dispatchers.Main) { onToolCall(toolName) }

                    val tool = ToolRegistry.getTool(toolName)
                    val result = if (tool != null && offlineToolNames.contains(toolName)) {
                        try {
                            tool.executor(context, toolArgs)
                        } catch (e: Exception) {
                            ToolResult(false, "Tool error: ${e.message}")
                        }
                    } else {
                        ToolResult(false, "Tool '$toolName' not available offline")
                    }

                    roundResults.add(toolName to result.message)
                    toolResults.add(toolName to result.message)
                }

                // Build continuation prompt with all tool results
                currentPrompt = buildString {
                    append(currentPrompt)
                    append(rawOutput)
                    for ((toolName, resultMsg) in roundResults) {
                        append("\n\nTool result for $toolName: $resultMsg")
                    }
                    append("\nNow respond to the user naturally about what happened.\n### Assistant:\n")
                }

                round++
            }

            // Max rounds hit — summarize what happened
            val summary = toolResults.joinToString(". ") { (name, msg) -> "$name: $msg" }
            withContext(Dispatchers.Main) { onResponse("Done. $summary") }
        } catch (e: Exception) {
            Log.e(TAG, "Local tool completion error", e)
            withContext(Dispatchers.Main) { onError(e.message ?: "Local tool error") }
        }
    }

    // ── Tool Prompt Builders ─────────────────────────────────────────

    private fun buildToolCallingPrompt(userMessage: String, toolDescriptions: String): String {
        return buildString {
            append("### System:\n")
            append("You are Nova, Deva's phone assistant. You can use tools to control the phone.\n\n")
            append("Available tools:\n")
            append(toolDescriptions)
            append("\n\nTo use a tool, respond with EXACTLY this format:\n")
            append("TOOL_CALL: {\"name\": \"toolName\", \"args\": {\"param\": \"value\"}}\n\n")
            append("If no tool is needed, just respond normally.\n")
            append(SystemPrompt.dateTimeContext())
            append("\n### User:\n")
            append(userMessage)
            append("\n### Assistant:\n")
        }
    }

    private fun buildLocalToolDescriptions(offlineToolNames: Set<String>): String {
        return ToolRegistry.getAllTools()
            .filter { it.key in offlineToolNames }
            .map { (name, tool) ->
                val params = tool.parameters.entries.joinToString(", ") { (k, v) ->
                    "$k(${v.type}${if (v.required) ", required" else ""}): ${v.description}"
                }
                "- $name: ${tool.description}. Params: $params"
            }
            .joinToString("\n")
    }

    // ── Response Parsing ─────────────────────────────────────────────

    /**
     * Parse all TOOL_CALL blocks from model output using brace-counting.
     * Handles nested JSON (e.g., args containing braces) and multiple tool calls.
     * Expected format: TOOL_CALL: {"name": "toolName", "args": {"param": "value"}}
     *
     * @return List of (toolName, argsMap) pairs, empty if no valid tool calls found
     */
    private fun parseToolCalls(output: String): List<Pair<String, Map<String, Any>>> {
        if (!output.contains("TOOL_CALL:")) return emptyList()

        val jsonBlocks = extractJsonBlocks(output)
        val results = mutableListOf<Pair<String, Map<String, Any>>>()

        for (jsonStr in jsonBlocks) {
            try {
                val json = JSONObject(jsonStr)
                val name = json.getString("name")
                val argsJson = json.optJSONObject("args") ?: JSONObject()
                val args = mutableMapOf<String, Any>()

                argsJson.keys().forEach { key ->
                    args[key] = argsJson.get(key)
                }

                results.add(name to args)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed tool call JSON: ${jsonStr.take(200)}", e)
            }
        }

        return results
    }

    /**
     * Extract JSON blocks following TOOL_CALL: markers using brace-counting.
     * Handles nested objects correctly (e.g., args containing braces in values).
     */
    private fun extractJsonBlocks(text: String): List<String> {
        val results = mutableListOf<String>()
        val prefix = "TOOL_CALL:"
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf(prefix, searchFrom)
            if (idx == -1) break
            val braceStart = text.indexOf('{', idx + prefix.length)
            if (braceStart == -1) break
            var depth = 0
            var i = braceStart
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) break }
                }
                i++
            }
            if (depth == 0) {
                results.add(text.substring(braceStart, i + 1))
            }
            searchFrom = i + 1
        }
        return results
    }

    /**
     * Clean up a response that might contain partial tool call artifacts.
     */
    private fun cleanToolResponse(response: String): String {
        return response
            .replace(Regex("""TOOL_CALL:.*""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Convert list of (role, content) pairs to ### User/Assistant format.
     */
    private fun buildHistoryString(history: List<Pair<String, String>>): String {
        if (history.isEmpty()) return ""
        return buildString {
            for ((role, content) in history) {
                val label = if (role == "user") "User" else "Assistant"
                append("### $label:\n$content\n")
            }
        }
    }

    /**
     * Check if the local model is loaded and ready for inference.
     */
    fun isReady(): Boolean = NovaInference.isReady()
}
