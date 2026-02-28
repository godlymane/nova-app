package com.nova.companion.inference

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.companion.BuildConfig
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.core.SystemPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * InferenceRouter — routes LLM inference between cloud providers and local llama.cpp.
 *
 * Routing strategy:
 * 1. If online → try cloud provider (default: OPENAI)
 * 2. If cloud fails → retry next configured provider
 * 3. If all cloud providers fail OR offline → fall back to NovaInference (local)
 *
 * All cloud requests use SystemPrompt.getContextualPrompt() to inject Nova's personality,
 * memory context, and time-of-day awareness.
 *
 * Streaming: Both cloud and local paths stream tokens via [onToken] callback.
 *
 * Usage:
 *   InferenceRouter.route(
 *       userMessage = "what's my plan today",
 *       context = applicationContext,
 *       conversationHistory = buildConversationHistory(),
 *       memoryContext = memoryManager.injectContext(userMessage),
 *       onToken = { token -> /* append to UI */ },
 *       onComplete = { fullResponse -> /* finalize message */ },
 *       onError = { error -> /* show error */ }
 *   )
 */
object InferenceRouter {

    private const val TAG = "InferenceRouter"

    // Active cloud provider — can be changed at runtime via settings
    var cloudProvider: CloudProvider = CloudProvider.OPENAI

    // Fallback order when primary provider fails
    private val fallbackOrder: List<CloudProvider> = listOf(
        CloudProvider.OPENAI,
        CloudProvider.GEMINI,
        CloudProvider.CLAUDE
    )

    private val gson = GsonBuilder().create()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Provider enum ─────────────────────────────────────────────────

    enum class CloudProvider {
        OPENAI,
        GEMINI,
        CLAUDE
    }

    // ── Main routing entry point ──────────────────────────────────────

    /**
     * Route a user message through the appropriate inference backend.
     *
     * @param userMessage     The user's input text.
     * @param context         Android context (for network check and time of day).
     * @param conversationHistory  Recent chat in "### User:\n...\n### Assistant:\n..." format.
     * @param memoryContext   Relevant memories from MemoryManager.injectContext().
     * @param onToken         Called for each streamed token as it arrives.
     * @param onComplete      Called once with the complete response text.
     * @param onError         Called if all inference paths fail.
     */
    suspend fun route(
        userMessage: String,
        context: Context,
        conversationHistory: String,
        memoryContext: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val isOnline = CloudConfig.isOnline(context)
        Log.i(TAG, "route() — online=$isOnline, provider=$cloudProvider")

        if (!isOnline) {
            Log.i(TAG, "Offline — routing to local NovaInference")
            routeLocal(userMessage, conversationHistory, memoryContext, onToken, onComplete, onError)
            return
        }

        // Try cloud providers in order, starting with the configured primary
        val orderedProviders = buildList {
            add(cloudProvider)
            addAll(fallbackOrder.filter { it != cloudProvider })
        }

        var lastError: Throwable? = null
        for (provider in orderedProviders) {
            if (!hasKeyForProvider(provider)) {
                Log.d(TAG, "Skipping $provider — no API key configured")
                continue
            }

            Log.i(TAG, "Attempting cloud inference via $provider")
            val systemPrompt = buildSystemPrompt(context, memoryContext)

            try {
                val success = when (provider) {
                    CloudProvider.OPENAI -> routeOpenAI(
                        userMessage, systemPrompt, conversationHistory, onToken, onComplete
                    )
                    CloudProvider.GEMINI -> routeGemini(
                        userMessage, systemPrompt, conversationHistory, onToken, onComplete
                    )
                    CloudProvider.CLAUDE -> routeClaude(
                        userMessage, systemPrompt, conversationHistory, onToken, onComplete
                    )
                }
                if (success) return // Cloud succeeded — done
                Log.w(TAG, "$provider returned failure, trying next provider")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "$provider failed with exception: ${e.message}, trying next provider")
            }
        }

        // All cloud providers failed — fall back to local
        Log.w(TAG, "All cloud providers exhausted — falling back to local inference")
        if (NovaInference.isReady()) {
            routeLocal(userMessage, conversationHistory, memoryContext, onToken, onComplete, onError)
        } else {
            onError(lastError ?: Exception("All inference providers unavailable"))
        }
    }

    // ── System prompt builder ─────────────────────────────────────────

    private fun buildSystemPrompt(context: Context, memoryContext: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour in 5..11 -> "morning"
            hour in 12..16 -> "afternoon"
            hour in 17..20 -> "evening"
            else -> "night"
        }
        return SystemPrompt.getContextualPrompt(memoryContext, timeOfDay)
    }

    // ── OpenAI GPT-4o streaming ───────────────────────────────────────

    private suspend fun routeOpenAI(
        userMessage: String,
        systemPrompt: String,
        conversationHistory: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENAI_API_KEY
        if (apiKey.isBlank()) return@withContext false

        val messages = buildOpenAIMessages(systemPrompt, conversationHistory, userMessage)

        val requestBody = JsonObject().apply {
            addProperty("model", "gpt-4o")
            add("messages", messages)
            addProperty("max_tokens", 512)
            addProperty("temperature", 0.85)
            addProperty("stream", true)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext streamSSEResponse(
            request = request,
            extractToken = { line ->
                val jsonStr = line.removePrefix("data: ").trim()
                if (jsonStr == "[DONE]") return@streamSSEResponse null
                try {
                    val json = JsonParser.parseString(jsonStr).asJsonObject
                    json.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("delta")
                        ?.get("content")?.asString
                } catch (e: Exception) { null }
            },
            onToken = onToken,
            onComplete = onComplete,
            tag = "OpenAI"
        )
    }

    private fun buildOpenAIMessages(
        systemPrompt: String,
        conversationHistory: String,
        userMessage: String
    ): JsonArray {
        val messages = JsonArray()

        // System message
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt)
        })

        // Parse and inject conversation history
        if (conversationHistory.isNotBlank()) {
            parseConversationHistory(conversationHistory).forEach { (role, content) ->
                messages.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content)
                })
            }
        }

        // Current user message
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userMessage)
        })

        return messages
    }

    // ── Gemini streaming ──────────────────────────────────────────────

    private suspend fun routeGemini(
        userMessage: String,
        systemPrompt: String,
        conversationHistory: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return@withContext false

        // Build Gemini contents array
        val contents = JsonArray()

        // Inject history as alternating user/model turns
        if (conversationHistory.isNotBlank()) {
            parseConversationHistory(conversationHistory).forEach { (role, content) ->
                val geminiRole = if (role == "user") "user" else "model"
                contents.add(JsonObject().apply {
                    addProperty("role", geminiRole)
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", content) })
                    })
                })
            }
        }

        // Current user message
        contents.add(JsonObject().apply {
            addProperty("role", "user")
            add("parts", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", userMessage) })
            })
        })

        val requestBody = JsonObject().apply {
            add("contents", contents)
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", systemPrompt) })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", 512)
                addProperty("temperature", 0.85)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?key=$apiKey&alt=sse"

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext streamSSEResponse(
            request = request,
            extractToken = { line ->
                val jsonStr = line.removePrefix("data: ").trim()
                if (jsonStr.isBlank()) return@streamSSEResponse null
                try {
                    val json = JsonParser.parseString(jsonStr).asJsonObject
                    json.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString
                } catch (e: Exception) { null }
            },
            onToken = onToken,
            onComplete = onComplete,
            tag = "Gemini"
        )
    }

    // ── Anthropic Claude streaming ────────────────────────────────────

    private suspend fun routeClaude(
        userMessage: String,
        systemPrompt: String,
        conversationHistory: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY
        if (apiKey.isBlank()) return@withContext false

        // Build Claude messages array (no system in messages array — it's top-level)
        val messages = JsonArray()

        if (conversationHistory.isNotBlank()) {
            parseConversationHistory(conversationHistory).forEach { (role, content) ->
                messages.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content)
                })
            }
        }

        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userMessage)
        })

        val requestBody = JsonObject().apply {
            addProperty("model", "claude-3-5-sonnet-20241022")
            addProperty("max_tokens", 512)
            addProperty("system", systemPrompt)
            add("messages", messages)
            addProperty("stream", true)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext streamSSEResponse(
            request = request,
            extractToken = { line ->
                val jsonStr = line.removePrefix("data: ").trim()
                if (jsonStr.isBlank()) return@streamSSEResponse null
                try {
                    val json = JsonParser.parseString(jsonStr).asJsonObject
                    // Claude streams content_block_delta events with delta.text
                    val eventType = json.get("type")?.asString
                    if (eventType == "content_block_delta") {
                        json.getAsJsonObject("delta")?.get("text")?.asString
                    } else null
                } catch (e: Exception) { null }
            },
            onToken = onToken,
            onComplete = onComplete,
            tag = "Claude"
        )
    }

    // ── Local llama.cpp fallback ──────────────────────────────────────

    private suspend fun routeLocal(
        userMessage: String,
        conversationHistory: String,
        memoryContext: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (!NovaInference.isReady()) {
            Log.e(TAG, "Local model not ready — cannot route locally")
            onError(Exception("Local model not loaded. Load a .gguf model file to continue offline."))
            return
        }

        Log.i(TAG, "Routing to local NovaInference")
        try {
            val job = NovaInference.generateStreaming(
                userMessage = userMessage,
                history = conversationHistory,
                memoryContext = memoryContext,
                scope = CoroutineScope(Dispatchers.Default),
                onToken = onToken,
                onComplete = { onComplete("") }, // NovaInference onComplete doesn't pass full text — caller reads from onToken accumulation
                onError = onError
            )
            job.join()
        } catch (e: Exception) {
            Log.e(TAG, "Local inference error", e)
            onError(e)
        }
    }

    // ── SSE stream helper ─────────────────────────────────────────────

    /**
     * Executes an HTTP request and streams the SSE response.
     *
     * @param request       The OkHttp request (must accept text/event-stream).
     * @param extractToken  Parser lambda: given a raw SSE line (starting with "data: "),
     *                      returns the token string or null if the line should be skipped.
     * @param onToken       Called for each extracted token.
     * @param onComplete    Called once when stream ends, with the full concatenated response.
     * @param tag           Logging tag for the provider name.
     * @return true if stream completed successfully, false on HTTP error.
     */
    private fun streamSSEResponse(
        request: Request,
        extractToken: (String) -> String?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        tag: String
    ): Boolean {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "$tag network error: ${e.message}")
            return false
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e(TAG, "$tag HTTP ${response.code}: $errorBody")
            response.close()
            return false
        }

        val body = response.body ?: run {
            Log.e(TAG, "$tag empty response body")
            return false
        }

        val fullResponse = StringBuilder()

        try {
            BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val rawLine = line ?: continue
                    if (!rawLine.startsWith("data: ")) continue

                    val token = extractToken(rawLine) ?: continue
                    if (token.isNotEmpty()) {
                        fullResponse.append(token)
                        onToken(token)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$tag stream read error: ${e.message}")
            if (fullResponse.isEmpty()) return false
            // Partial response is still useful — fall through to onComplete
        } finally {
            response.close()
        }

        Log.i(TAG, "$tag stream complete (${fullResponse.length} chars)")
        onComplete(fullResponse.toString().trim())
        return true
    }

    // ── Conversation history parser ───────────────────────────────────

    /**
     * Parse the "### User:\n...\n### Assistant:\n..." format used internally
     * into a list of (role, content) pairs suitable for API messages arrays.
     */
    private fun parseConversationHistory(history: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var currentRole: String? = null
        val currentContent = StringBuilder()

        history.lines().forEach { line ->
            when {
                line.startsWith("### User:") -> {
                    if (currentRole != null && currentContent.isNotBlank()) {
                        result.add(currentRole!! to currentContent.toString().trim())
                        currentContent.clear()
                    }
                    currentRole = "user"
                }
                line.startsWith("### Assistant:") -> {
                    if (currentRole != null && currentContent.isNotBlank()) {
                        result.add(currentRole!! to currentContent.toString().trim())
                        currentContent.clear()
                    }
                    currentRole = "assistant"
                }
                else -> {
                    if (currentRole != null) {
                        currentContent.append(line).append("\n")
                    }
                }
            }
        }

        // Flush last entry
        if (currentRole != null && currentContent.isNotBlank()) {
            result.add(currentRole!! to currentContent.toString().trim())
        }

        return result
    }

    // ── Key availability checks ───────────────────────────────────────

    private fun hasKeyForProvider(provider: CloudProvider): Boolean {
        return when (provider) {
            CloudProvider.OPENAI -> BuildConfig.OPENAI_API_KEY.isNotBlank()
            CloudProvider.GEMINI -> BuildConfig.GEMINI_API_KEY.isNotBlank()
            CloudProvider.CLAUDE -> BuildConfig.ANTHROPIC_API_KEY.isNotBlank()
        }
    }
}

// ── Extension: isReady() convenience on NovaInference ────────────────
private fun NovaInference.isReady(): Boolean =
    this.state.value == NovaInference.ModelState.READY
