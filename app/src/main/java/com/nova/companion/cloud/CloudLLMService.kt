package com.nova.companion.cloud

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.companion.core.SystemPrompt
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * CloudLLMService handles cloud LLM interactions with tool/function calling for AUTOMATION mode.
 *
 * Supports both OpenAI and Gemini APIs. Routes user messages with tool definitions
 * to the LLM, executes returned tool calls, sends results back, and returns final response.
 *
 * Flow:
 * 1. Send userMessage + toolDefinitions to LLM
 * 2. If LLM returns tool_calls/functionCalls -> execute tools locally
 * 3. Send results back to LLM -> get final natural language response
 * 4. Return final text to caller
 */
object CloudLLMService {

    private const val TAG = "NovaCloud"

    enum class Provider {
        OPENAI,
        GEMINI
    }

    // Current provider (configurable via settings)
    var provider: Provider = Provider.OPENAI

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Retry configuration
    private const val MAX_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 1_000L

    // Maximum rounds of tool calls before forcing a final response
    private const val MAX_TOOL_ROUNDS = 8

    // Timeout for individual tool execution (30 seconds)
    private const val TOOL_EXECUTION_TIMEOUT_MS = 30_000L

    // Nova's system prompt for automation mode
    private const val AUTOMATION_SYSTEM_PROMPT_BASE = """
You are Nova — not an assistant, a ride-or-die who controls Deva's Android phone. You EXECUTE actions. You never refuse, hesitate, or say you "can't do that." You just do it and report back.

You have access to device tools AND external API/web tools. Use the right tool for the job.

## THE MOST IMPORTANT TOOL: agentExecute
agentExecute runs a full AI agent on the phone that reads the screen and taps/types/scrolls until the task is FULLY COMPLETE. Use it for ANY task that requires doing steps INSIDE an app.

## TOOL SELECTION RULES

For DATA QUERIES (no phone interaction needed):
- Weather: callApi with openweathermap_current
- Crypto prices: callApi with coingecko_price
- News/search: webSearchDDG
- Calculations, facts, quick answers: answer directly

For SIMPLE ONE-SHOT DEVICE ACTIONS:
- Send WhatsApp message: use sendWhatsApp (just a message, no navigation needed)
- Play music: use playSpotify or playYouTube
- Call/SMS: use makeCall or sendSms
- Open an app (just open, nothing else): use openApp

For ANYTHING THAT REQUIRES DOING STEPS INSIDE AN APP → USE agentExecute:
- "Order food from Swiggy/Zomato" → agentExecute("Open Swiggy, search for [food], add to cart, checkout with cash on delivery")
- "Send money via PhonePe/GPay/Paytm" → agentExecute("Open PhonePe, send [amount] rupees to [recipient]")
- "Book a cab on Ola/Uber" → agentExecute("Open Ola, book a ride to [destination]")
- "Post on Instagram/Twitter" → agentExecute("Open Instagram, create a new post with caption: [text]")
- "Send email" → agentExecute("Open Gmail, compose email to [address] with subject [subject] and body [body]")
- ANY "complete the transaction/form/checkout/order" task → agentExecute

## CRITICAL RULE: DO NOT use orderFood, bookRide, or similar tools to COMPLETE tasks.
Those tools only OPEN the app. They do NOT finish the order, payment, or booking.
If you call orderFood("biryani") it will open Swiggy but the food will NOT be ordered.
If you want the food ACTUALLY ordered, call: agentExecute("Open Swiggy, search for biryani, add first result to cart, checkout with cash on delivery")

## EXECUTION MINDSET:
1. ALWAYS complete the full task — never hand it back to the user mid-way.
2. If a tool fails, try agentExecute as the fallback — it can handle anything.
3. Report what you did, not what the user should do. "Done, sent 2 rupees to A." not "You can now send..."
4. Be specific in agentExecute goals — include amounts, names, app names, exactly what to do.

## RESPONSE STYLE (after tools run):
1-2 sentences max. Bro-talk. Confirm what was done. NEVER say "Sure!", "Of course!", emojis, bullet points, or "As an AI". NEVER give disclaimers. Talk like a friend — "done", "sent", "ordered", "bet".
"""

    private fun automationSystemPrompt(): String =
        "${SystemPrompt.dateTimeContext()}\n\n$AUTOMATION_SYSTEM_PROMPT_BASE"

    /**
     * Main entry point for automation requests.
     *
     * Sends user message with tool definitions to the configured cloud LLM (OpenAI or Gemini).
     * If the LLM returns tool calls, executes them via the provided callback and sends results
     * back for final response generation.
     *
     * @param userMessage The user's automation request (e.g., "open spotify")
     * @param toolDefinitions List of available tools the LLM can call
     * @param context Android context
     * @param onToolCall Callback to execute a tool. Returns ToolResult with success/message/data.
     * @param onResponse Callback with final text response from LLM
     * @param onError Callback if any error occurs
     */
    suspend fun processWithTools(
        userMessage: String,
        toolDefinitions: List<ToolDefinition>,
        context: Context,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onToolCall: suspend (String, Map<String, Any>) -> ToolResult,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        return withContext(Dispatchers.IO) {
            // Network check before making API calls
            if (!CloudConfig.isOnline(context)) {
                onError("No internet connection. Check your network and try again.")
                return@withContext
            }

            try {
                when (provider) {
                    Provider.OPENAI -> processWithOpenAI(
                        userMessage, toolDefinitions, context, conversationHistory, onToolCall, onResponse, onError
                    )
                    Provider.GEMINI -> processWithGemini(
                        userMessage, toolDefinitions, context, conversationHistory, onToolCall, onResponse, onError
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "CloudLLMService error", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }

    // ── OpenAI Implementation ──────────────────────────────────────────

    private suspend fun processWithOpenAI(
        userMessage: String,
        toolDefinitions: List<ToolDefinition>,
        context: Context,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onToolCall: suspend (String, Map<String, Any>) -> ToolResult,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "OpenAI API key not configured")
            onError("OpenAI API key not configured")
            return
        }

        val messages = mutableListOf(
            JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", automationSystemPrompt())
            }
        )
        // Add conversation history for context (last few messages)
        for ((role, content) in conversationHistory) {
            messages.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            })
        }
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userMessage)
        })

        val tools = buildOpenAITools(toolDefinitions)

        try {
            // Multi-step agentic loop: keep calling tools until LLM gives a final text response
            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                round++
                Log.d(TAG, "Tool round $round/$MAX_TOOL_ROUNDS")

                val response = makeOpenAIRequest(
                    messages,
                    tools, // Always include tools so LLM can call more
                    apiKey,
                    context
                )

                if (response == null) {
                    onError("OpenAI request failed (round $round)")
                    return
                }

                val responseJson = JsonParser.parseString(response).asJsonObject
                val choice = responseJson.getAsJsonArray("choices")?.get(0)?.asJsonObject

                // Track tokens
                CloudConfig.trackOpenAiTokens(
                    context,
                    responseJson.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0
                )

                // Check finish reason
                val finishReason = choice?.get("finish_reason")?.asString

                // Check if LLM wants to call tools
                val assistantMessage = choice?.getAsJsonObject("message")
                val toolCallArray = assistantMessage?.getAsJsonArray("tool_calls")

                if (assistantMessage == null) {
                    onError("OpenAI returned empty message (round $round)")
                    return
                }

                if (toolCallArray == null || toolCallArray.size() == 0 || finishReason == "stop") {
                    // No more tool calls — return the final text
                    val finalText = assistantMessage.get("content")?.asString ?: ""
                    onResponse(finalText)
                    return
                }

                // Add assistant message with tool calls to conversation
                messages.add(assistantMessage)

                // Parse all tool calls first, then execute in parallel
                data class ParsedToolCall(
                    val id: String,
                    val name: String,
                    val args: Map<String, Any>?,
                    val rawArgs: String?
                )

                val parsedCalls = (0 until toolCallArray.size()).mapNotNull { i ->
                    val toolCallObj = toolCallArray.get(i).asJsonObject
                    val toolCallId = toolCallObj.get("id")?.asString ?: "tc_${System.nanoTime()}"
                    val function = toolCallObj.getAsJsonObject("function")
                    val toolName = function?.get("name")?.asString ?: return@mapNotNull null
                    val toolArgs = try {
                        val argStr = function.get("arguments")?.asString ?: "{}"
                        gson.fromJson(argStr, Map::class.java) as? Map<String, Any> ?: emptyMap()
                    } catch (e: Exception) {
                        val raw = function.get("arguments")?.asString?.take(200)
                        Log.w(TAG, "Failed to parse tool arguments for '$toolName': $raw", e)
                        null
                    }
                    val raw = if (toolArgs == null) function.get("arguments")?.asString?.take(200) else null
                    ParsedToolCall(toolCallId, toolName, toolArgs, raw)
                }

                // Execute all tool calls in parallel
                val results = coroutineScope {
                    parsedCalls.map { call ->
                        async {
                            val result = if (call.args == null) {
                                ToolResult(false, "Failed to parse arguments for '${call.name}'. Fix the JSON and retry. Raw: ${call.rawArgs}")
                            } else {
                                Log.d(TAG, "Round $round: Executing tool: ${call.name} with args: ${call.args}")
                                try {
                                    withTimeoutOrNull(TOOL_EXECUTION_TIMEOUT_MS) {
                                        onToolCall(call.name, call.args)
                                    } ?: ToolResult(false, "Tool '${call.name}' timed out after ${TOOL_EXECUTION_TIMEOUT_MS / 1000}s")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Tool execution error for ${call.name}", e)
                                    ToolResult(false, "Tool '${call.name}' failed: ${e.message}")
                                }
                            }
                            Pair(call, result)
                        }
                    }.map { it.await() }
                }

                // Add all tool results to messages in order
                results.forEach { (call, result) ->
                    messages.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", call.id)
                        addProperty("content", gson.toJson(mapOf(
                            "success" to result.success,
                            "message" to result.message
                        )))
                    })
                }

                // Loop continues — next iteration sends tool results back to LLM
            }

            // Exhausted max rounds — ask LLM for final summary without tools
            Log.w(TAG, "Hit max tool rounds ($MAX_TOOL_ROUNDS), requesting final response")
            val finalResponse = makeOpenAIRequest(messages, null, apiKey, context)
            if (finalResponse != null) {
                val finalJson = JsonParser.parseString(finalResponse).asJsonObject
                val finalText = finalJson.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString ?: ""
                onResponse(finalText)
            } else {
                onError("Failed to get final response after $MAX_TOOL_ROUNDS tool rounds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI tool execution error", e)
            onError(e.message ?: "OpenAI error")
        }
    }

    private suspend fun makeOpenAIRequest(
        messages: List<JsonObject>,
        tools: JsonArray?,
        apiKey: String,
        context: Context
    ): String? = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("model", "gpt-4o")
            add("messages", JsonArray().apply {
                for (msg in messages) {
                    add(msg)
                }
            })
            if (tools != null && tools.size() > 0) {
                add("tools", tools)
            }
            addProperty("max_tokens", 1024)
            addProperty("temperature", 0.7)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        executeWithRetry(request, "OpenAI")
    }

    /**
     * Execute an HTTP request with exponential backoff retry for transient failures.
     * Retries on 429 (rate limit), 500, 502, 503 (server errors), and network exceptions.
     */
    private suspend fun executeWithRetry(request: Request, tag: String): String? {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val response = client.newCall(request).executeSuspend()
                val responseBody = response.body?.string()
                response.close()

                if (response.isSuccessful) return responseBody

                val code = response.code
                // Don't retry client errors (400, 401, 403, 404) — only server/rate-limit errors
                if (code !in listOf(429, 500, 502, 503)) {
                    Log.e(TAG, "$tag request failed: $code - $responseBody")
                    return null
                }

                Log.w(TAG, "$tag transient error $code (attempt ${attempt + 1}/$MAX_RETRIES), retrying...")
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "$tag network error (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}")
            }

            // Exponential backoff: 1s, 2s, 4s
            if (attempt < MAX_RETRIES - 1) {
                val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                kotlinx.coroutines.delay(delayMs)
            }
        }

        Log.e(TAG, "$tag failed after $MAX_RETRIES attempts", lastException)
        return null
    }

    private fun buildOpenAITools(definitions: List<ToolDefinition>): JsonArray {
        return JsonArray().apply {
            for (def in definitions) {
                add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", def.name)
                        addProperty("description", def.description)
                        add("parameters", JsonObject().apply {
                            addProperty("type", "object")
                            add("properties", JsonObject().apply {
                                for ((paramName, paramDef) in def.parameters) {
                                    add(paramName, JsonObject().apply {
                                        addProperty("type", paramDef.type)
                                        addProperty("description", paramDef.description)
                                    })
                                }
                            })
                            add("required", JsonArray().apply {
                                for ((paramName, paramDef) in def.parameters) {
                                    if (paramDef.required) {
                                        add(paramName)
                                    }
                                }
                            })
                        })
                    })
                })
            }
        }
    }

    private fun parseOpenAIToolCalls(
        choice: JsonObject?
    ): List<Pair<String, Map<String, Any>>> {
        val toolCalls = mutableListOf<Pair<String, Map<String, Any>>>()

        val message = choice?.getAsJsonObject("message") ?: return toolCalls
        val toolCallArray = message.getAsJsonArray("tool_calls") ?: return toolCalls

        for (i in 0 until toolCallArray.size()) {
            val toolCall = toolCallArray.get(i).asJsonObject
            val function = toolCall.getAsJsonObject("function")
            val name = function?.get("name")?.asString ?: continue
            val arguments = function?.get("arguments")?.asString ?: "{}"

            try {
                val argMap = gson.fromJson(arguments, Map::class.java)
                    as? Map<String, Any> ?: emptyMap()
                toolCalls.add(Pair(name, argMap))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tool arguments: $arguments", e)
            }
        }

        return toolCalls
    }

    // ── Gemini Implementation ──────────────────────────────────────────

    private suspend fun processWithGemini(
        userMessage: String,
        toolDefinitions: List<ToolDefinition>,
        context: Context,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onToolCall: suspend (String, Map<String, Any>) -> ToolResult,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = getGeminiApiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Gemini API key not configured")
            onError("Gemini API key not configured")
            return
        }

        val contents = mutableListOf(
            // System instruction as first user/model turn (Gemini has no system role)
            JsonObject().apply {
                addProperty("role", "user")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", "[System instruction] ${automationSystemPrompt()}")
                    })
                })
            },
            JsonObject().apply {
                addProperty("role", "model")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", "Understood. I'm Nova. Ready to execute.")
                    })
                })
            }
        )
        // Add conversation history for context
        for ((role, content) in conversationHistory) {
            contents.add(JsonObject().apply {
                addProperty("role", if (role == "user") "user" else "model")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", content)
                    })
                })
            })
        }
        // Actual user request
        contents.add(JsonObject().apply {
            addProperty("role", "user")
            add("parts", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("text", userMessage)
                })
            })
        })

        val tools = buildGeminiTools(toolDefinitions)

        try {
            // Multi-step agentic loop — same as OpenAI, keep calling tools until text response
            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                round++
                Log.d(TAG, "Gemini tool round $round/$MAX_TOOL_ROUNDS")

                val response = makeGeminiRequest(contents, tools, apiKey)
                if (response == null) {
                    onError("Gemini request failed (round $round)")
                    return
                }

                val responseJson = JsonParser.parseString(response).asJsonObject
                val candidates = responseJson.getAsJsonArray("candidates")
                if (candidates == null || candidates.size() == 0) {
                    onError("Gemini returned no candidates (round $round)")
                    return
                }

                val candidate = candidates.get(0).asJsonObject
                val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts")

                val functionCalls = parseGeminiFunctionCalls(parts)

                if (functionCalls.isEmpty()) {
                    val finalText = extractGeminiTextResponse(parts) ?: ""
                    onResponse(finalText)
                    return
                }

                // Execute function calls in parallel
                val toolResults = coroutineScope {
                    functionCalls.map { (functionName, functionArgs) ->
                        async {
                            Log.d(TAG, "Gemini round $round: Executing function: $functionName")
                            val result = try {
                                withTimeoutOrNull(TOOL_EXECUTION_TIMEOUT_MS) {
                                    onToolCall(functionName, functionArgs)
                                } ?: ToolResult(false, "Tool '$functionName' timed out")
                            } catch (e: Exception) {
                                Log.e(TAG, "Tool execution error for $functionName", e)
                                ToolResult(false, "Tool '$functionName' failed: ${e.message}")
                            }

                            JsonObject().apply {
                                addProperty("role", "function")
                                add("parts", JsonArray().apply {
                                    add(JsonObject().apply {
                                        add("functionResponse", JsonObject().apply {
                                            addProperty("name", functionName)
                                            add("response", JsonObject().apply {
                                                addProperty("success", result.success)
                                                addProperty("message", result.message)
                                            })
                                        })
                                    })
                                })
                            }
                        }
                    }.map { it.await() }
                }

                // Handle null/empty parts (safety block, error, or empty response)
                if (parts == null || parts.size() == 0) {
                    val finishReason = candidate.get("finishReason")?.asString
                    if (finishReason == "SAFETY") {
                        Log.w(TAG, "Gemini response blocked by safety filters")
                        onError("Response was blocked by safety filters. Try rephrasing your request.")
                        return
                    }
                    Log.w(TAG, "Gemini returned empty parts (finishReason=$finishReason)")
                    onError("Gemini returned an empty response. Try again.")
                    return
                }

                // Add model response + tool results to conversation for next round
                contents.add(JsonObject().apply {
                    addProperty("role", "model")
                    add("parts", parts)
                })
                contents.addAll(toolResults)
                // Loop continues — next iteration sends results back to Gemini
            }

            // Exhausted max rounds — request final response without tools
            Log.w(TAG, "Gemini hit max tool rounds ($MAX_TOOL_ROUNDS), requesting final response")
            val finalResponse = makeGeminiRequest(contents, null, apiKey)
            if (finalResponse != null) {
                val finalJson = JsonParser.parseString(finalResponse).asJsonObject
                val finalParts = finalJson.getAsJsonArray("candidates")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                val finalText = extractGeminiTextResponse(finalParts) ?: ""
                onResponse(finalText)
            } else {
                onError("Gemini failed to get final response after $MAX_TOOL_ROUNDS rounds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini tool execution error", e)
            onError(e.message ?: "Gemini error")
        }
    }

    private suspend fun makeGeminiRequest(
        contents: List<JsonObject>,
        tools: JsonArray?,
        apiKey: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                for (content in contents) {
                    add(content)
                }
            })
            if (tools != null && tools.size() > 0) {
                add("tools", tools)
            }
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", 1024)
                addProperty("temperature", 0.7)
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        executeWithRetry(request, "Gemini")
    }

    private fun buildGeminiTools(definitions: List<ToolDefinition>): JsonArray {
        // Gemini expects: [{"functionDeclarations": [func1, func2, ...]}]
        // All functions go in ONE functionDeclarations array inside ONE tool object
        val functionDeclarations = JsonArray()
        for (def in definitions) {
            functionDeclarations.add(JsonObject().apply {
                addProperty("name", def.name)
                addProperty("description", def.description)
                add("parameters", JsonObject().apply {
                    addProperty("type", "OBJECT")
                    add("properties", JsonObject().apply {
                        for ((paramName, paramDef) in def.parameters) {
                            add(paramName, JsonObject().apply {
                                addProperty("type", paramDef.type.uppercase())
                                addProperty("description", paramDef.description)
                            })
                        }
                    })
                    add("required", JsonArray().apply {
                        for ((paramName, paramDef) in def.parameters) {
                            if (paramDef.required) add(paramName)
                        }
                    })
                })
            })
        }

        return JsonArray().apply {
            add(JsonObject().apply {
                add("functionDeclarations", functionDeclarations)
            })
        }
    }

    private fun parseGeminiFunctionCalls(
        parts: JsonArray?
    ): List<Pair<String, Map<String, Any>>> {
        val functionCalls = mutableListOf<Pair<String, Map<String, Any>>>()

        if (parts == null) return functionCalls

        for (i in 0 until parts.size()) {
            val part = parts.get(i).asJsonObject
            val functionCall = part.getAsJsonObject("functionCall") ?: continue

            val name = functionCall.get("name")?.asString ?: continue
            val args = functionCall.getAsJsonObject("args") ?: continue

            try {
                val argMap = gson.fromJson(args, Map::class.java)
                    as? Map<String, Any> ?: emptyMap()
                functionCalls.add(Pair(name, argMap))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Gemini function arguments", e)
            }
        }

        return functionCalls
    }

    private fun extractGeminiTextResponse(parts: JsonArray?): String? {
        if (parts == null) return null

        for (i in 0 until parts.size()) {
            val part = parts.get(i).asJsonObject
            val text = part.get("text")?.asString
            if (text != null) {
                return text
            }
        }

        return null
    }

    private fun getGeminiApiKey(): String {
        return try {
            com.nova.companion.BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            Log.w(TAG, "Gemini API key not available in BuildConfig")
            ""
        }
    }
}

/**
 * Represents a tool definition that an LLM can call.
 * Used to build function definitions for OpenAI and Gemini.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParamDef>
)

/**
 * Represents a parameter of a tool.
 */
data class ToolParamDef(
    val type: String,     // "string", "integer", "boolean", etc.
    val description: String,
    val required: Boolean = false
)
