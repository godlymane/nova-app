package com.nova.companion.cloud

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Nova's system prompt for automation mode
    private const val AUTOMATION_SYSTEM_PROMPT = """
You are Nova, a casual AI companion. The user wants you to perform an action on their phone.
Analyze the request and call the appropriate tool. Available tools will be provided.
Be brief in your responses. Use casual language. Keep confirmations to 1-2 sentences.
"""

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
        onToolCall: suspend (String, Map<String, Any>) -> ToolResult,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        return withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    Provider.OPENAI -> processWithOpenAI(
                        userMessage, toolDefinitions, context, onToolCall, onResponse, onError
                    )
                    Provider.GEMINI -> processWithGemini(
                        userMessage, toolDefinitions, context, onToolCall, onResponse, onError
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
                addProperty("content", AUTOMATION_SYSTEM_PROMPT)
            },
            JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            }
        )

        val tools = buildOpenAITools(toolDefinitions)

        try {
            // First request: send message + tools
            val initialResponse = makeOpenAIRequest(
                messages,
                tools,
                apiKey,
                context
            )

            if (initialResponse == null) {
                onError("OpenAI request failed")
                return
            }

            val responseJson = JsonParser.parseString(initialResponse).asJsonObject
            val choice = responseJson.getAsJsonArray("choices")?.get(0)?.asJsonObject

            // Check if LLM wants to call tools
            val toolCalls = parseOpenAIToolCalls(choice)

            if (toolCalls.isEmpty()) {
                // No tool calls, just return the text response
                val finalText = choice?.getAsJsonObject("message")?.get("content")?.asString ?: ""
                CloudConfig.trackOpenAiTokens(
                    context,
                    responseJson.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0
                )
                onResponse(finalText)
                return
            }

            // Execute tool calls and collect results paired with their IDs
            val assistantMessage = choice!!.getAsJsonObject("message")
            val toolCallArray = assistantMessage.getAsJsonArray("tool_calls")

            messages.add(assistantMessage)

            for (i in 0 until (toolCallArray?.size() ?: 0)) {
                val toolCallObj = toolCallArray!!.get(i).asJsonObject
                val toolCallId = toolCallObj.get("id")?.asString ?: "tc_${System.nanoTime()}"
                val function = toolCallObj.getAsJsonObject("function")
                val toolName = function?.get("name")?.asString ?: continue
                val toolArgs = try {
                    val argStr = function.get("arguments")?.asString ?: "{}"
                    gson.fromJson(argStr, Map::class.java) as? Map<String, Any> ?: emptyMap()
                } catch (e: Exception) { emptyMap<String, Any>() }

                Log.d(TAG, "Executing tool: $toolName with args: $toolArgs")
                val result = onToolCall(toolName, toolArgs)

                // OpenAI expects role=tool with matching tool_call_id
                messages.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", toolCallId)
                    addProperty("content", gson.toJson(mapOf(
                        "success" to result.success,
                        "message" to result.message
                    )))
                })
            }

            // Second request: send tool results, get final response
            val finalResponse = makeOpenAIRequest(
                messages,
                null, // Don't include tools in second request
                apiKey,
                context
            )

            if (finalResponse == null) {
                onError("OpenAI final response failed")
                return
            }

            val finalJson = JsonParser.parseString(finalResponse).asJsonObject
            val finalText = finalJson.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString ?: ""

            CloudConfig.trackOpenAiTokens(
                context,
                finalJson.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0
            )

            onResponse(finalText)
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

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "OpenAI request failed: ${response.code} - $responseBody")
                return@withContext null
            }

            responseBody
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI request error", e)
            null
        }
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
            JsonObject().apply {
                addProperty("role", "user")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", userMessage)
                    })
                })
            }
        )

        val tools = buildGeminiTools(toolDefinitions)

        try {
            // First request: send message + tools
            val initialResponse = makeGeminiRequest(
                contents,
                tools,
                apiKey
            )

            if (initialResponse == null) {
                onError("Gemini request failed")
                return
            }

            val responseJson = JsonParser.parseString(initialResponse).asJsonObject
            val candidates = responseJson.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                onError("Gemini returned no candidates")
                return
            }

            val candidate = candidates.get(0).asJsonObject
            val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts")

            // Check if Gemini wants to call functions
            val functionCalls = parseGeminiFunctionCalls(parts)

            if (functionCalls.isEmpty()) {
                // No function calls, just return the text response
                val finalText = extractGeminiTextResponse(parts) ?: ""
                onResponse(finalText)
                return
            }

            // Execute function calls
            val toolResults = mutableListOf<JsonObject>()
            for ((functionName, functionArgs) in functionCalls) {
                Log.d(TAG, "Executing Gemini function: $functionName with args: $functionArgs")
                val result = onToolCall(functionName, functionArgs)
                toolResults.add(JsonObject().apply {
                    addProperty("role", "function")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            add("functionResponse", JsonObject().apply {
                                addProperty("name", functionName)
                                add("response", JsonObject().apply {
                                    addProperty("success", result.success)
                                    addProperty("message", result.message)
                                    if (result.data != null) {
                                        add("data", gson.toJsonTree(result.data))
                                    }
                                })
                            })
                        })
                    })
                })
            }

            // Add assistant response with function calls
            contents.add(JsonObject().apply {
                addProperty("role", "model")
                add("parts", parts!!)
            })

            // Add tool results
            contents.addAll(toolResults)

            // Second request: send tool results, get final response
            val finalResponse = makeGeminiRequest(
                contents,
                null, // Don't include tools in second request
                apiKey
            )

            if (finalResponse == null) {
                onError("Gemini final response failed")
                return
            }

            val finalJson = JsonParser.parseString(finalResponse).asJsonObject
            val finalCandidates = finalJson.getAsJsonArray("candidates")
            if (finalCandidates == null || finalCandidates.size() == 0) {
                onError("Gemini returned no final response")
                return
            }

            val finalParts = finalCandidates.get(0).asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")

            val finalText = extractGeminiTextResponse(finalParts) ?: ""
            onResponse(finalText)
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

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini request failed: ${response.code} - $responseBody")
                return@withContext null
            }

            responseBody
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request error", e)
            null
        }
    }

    private fun buildGeminiTools(definitions: List<ToolDefinition>): JsonArray {
        return JsonArray().apply {
            add(JsonObject().apply {
                addProperty("googleSearch", JsonObject().toString()) // Gemini web search
            })
            for (def in definitions) {
                add(JsonObject().apply {
                    add("functionDeclarations", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("name", def.name)
                            addProperty("description", def.description)
                            add("parameters", JsonObject().apply {
                                addProperty("type", "OBJECT")
                                add("properties", JsonObject().apply {
                                    for ((paramName, paramDef) in def.parameters) {
                                        add(paramName, JsonObject().apply {
                                            addProperty(
                                                "type",
                                                paramDef.type.uppercase()
                                            )
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
                })
            }
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
