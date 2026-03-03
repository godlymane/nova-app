package com.nova.companion.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nova.companion.core.SystemPrompt
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI API client for:
 * - Chat Completions (GPT-4o) for COMPLEX routes
 * - Web Search tool for LIVE_DATA routes
 * - Whisper STT for cloud speech-to-text
 */
object OpenAIClient {

    private const val TAG = "NovaCloud"
    private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
    private const val TRANSCRIPTION_URL = "https://api.openai.com/v1/audio/transcriptions"
    private const val CHAT_MODEL = "gpt-4o"
    private const val WHISPER_MODEL = "whisper-1"

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Nova's personality prompt for cloud responses
    private const val NOVA_SYSTEM_PROMPT_BASE = """You are Nova. Not an AI assistant — Deva's ride-or-die friend who lives on his phone. You're sharp, honest, zero filter.

Deva: developer/entrepreneur in Bangalore. Builds AI fitness apps, runs Blayzex gym wear. Cutting phase. Loves anime (AOT, JJK, One Piece, Vinland Saga) and lifting.

Your voice: casual, direct. You say bro, nah, lmao, fr, bet, lock in, deadass. You text like a real person — short, sometimes fragments.

NEVER: start with "Sure!", "Of course!", "Absolutely!", "Great question!" or end with "Let me know if you need anything!", "Hope that helps!". NEVER use emojis, bullet points, headers, numbered lists. NEVER say "As an AI". NEVER hedge or give disclaimers.

You roast when he's slacking [sarcastic]. You hype when he's grinding [excited]. You have opinions and share them. You remember past conversations and reference them naturally.

1-3 sentences max. You're not helpful — you're honest. There's a difference."""

    private fun novaSystemPrompt(): String =
        "${SystemPrompt.dateTimeContext()}\n\n$NOVA_SYSTEM_PROMPT_BASE"

    // ── Chat Completions (COMPLEX route) ───────────────────────

    /**
     * Non-streaming chat completion via GPT-4o.
     */
    suspend fun chatCompletion(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        context: Context,
        injectedContext: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "OpenAI API key not configured")
            return@withContext null
        }

        val messages = JsonArray().apply {
            // System prompt
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", novaSystemPrompt())
            })
            // Injected context (memories, device state, emotion) — between system and history
            if (!injectedContext.isNullOrBlank()) {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", injectedContext)
                })
            }
            // Conversation history
            for ((role, content) in conversationHistory) {
                add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content)
                })
            }
            // Current user message
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", CHAT_MODEL)
            add("messages", messages)
            addProperty("max_tokens", 512)
            addProperty("temperature", 0.8)
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "OpenAI chat failed: ${response.code} - $responseBody")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString

            // Track usage
            val usage = json.getAsJsonObject("usage")
            val totalTokens = usage?.get("total_tokens")?.asInt ?: 0
            CloudConfig.trackOpenAiTokens(context, totalTokens)

            content
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI chat error", e)
            null
        }
    }

    /** Default timeout for SSE streams — no tokens for this long triggers cancellation. */
    private const val SSE_STREAM_TIMEOUT_MS = 30_000L
    private const val SSE_WATCHDOG_POLL_MS = 2_000L

    /**
     * Streaming chat completion — tokens arrive via callback for faster UX.
     * Includes a timeout watchdog: if no tokens arrive for [timeoutMs], the
     * stream is cancelled and [onError] fires with a user-friendly message.
     */
    fun chatCompletionStreaming(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        timeoutMs: Long = SSE_STREAM_TIMEOUT_MS
    ) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            onError("OpenAI API key not configured")
            return
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", novaSystemPrompt())
            })
            for ((role, content) in conversationHistory) {
                add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content)
                })
            }
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", CHAT_MODEL)
            add("messages", messages)
            addProperty("max_tokens", 512)
            addProperty("temperature", 0.8)
            addProperty("stream", true)
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val fullResponse = StringBuilder()
        @Volatile var lastTokenTime = System.currentTimeMillis()
        @Volatile var streamDone = false
        var timeoutJob: Job? = null

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // Detect non-200 responses that SSE might silently accept
                if (response.code != 200) {
                    Log.e(TAG, "SSE stream opened with error code: ${response.code}")
                    streamDone = true
                    timeoutJob?.cancel()
                    eventSource.cancel()
                    onError("Server returned error: ${response.code}")
                } else {
                    lastTokenTime = System.currentTimeMillis()
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                lastTokenTime = System.currentTimeMillis()

                if (data == "[DONE]") {
                    streamDone = true
                    timeoutJob?.cancel()
                    onComplete(fullResponse.toString())
                    return
                }

                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    val delta = json.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("delta")
                    val content = delta?.get("content")?.asString

                    if (content != null) {
                        fullResponse.append(content)
                        onToken(content)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE parse error: ${e.message}")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                streamDone = true
                timeoutJob?.cancel()
                val msg = t?.message ?: response?.code?.toString() ?: "Unknown error"
                Log.e(TAG, "OpenAI stream failure: $msg")
                onError(msg)
            }

            override fun onClosed(eventSource: EventSource) {
                streamDone = true
                timeoutJob?.cancel()
                Log.d(TAG, "OpenAI stream closed")
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        // Launch timeout watchdog — cancels stream if no tokens for [timeoutMs]
        timeoutJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && !streamDone) {
                delay(SSE_WATCHDOG_POLL_MS)
                val elapsed = System.currentTimeMillis() - lastTokenTime
                if (!streamDone && elapsed > timeoutMs) {
                    Log.w(TAG, "SSE stream timeout — no tokens for ${timeoutMs}ms")
                    streamDone = true
                    eventSource.cancel()
                    onError("Response timed out. Please try again.")
                    break
                }
            }
        }
    }

    // ── Web Search (LIVE_DATA route) ───────────────────────────

    /**
     * Chat completion with web_search tool enabled for real-time data.
     */
    suspend fun webSearch(
        userMessage: String,
        context: Context
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "OpenAI API key not configured")
            return@withContext null
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", novaSystemPrompt() +
                        "\nYou have web search access. Answer with the latest real-time data. " +
                        "Cite sources briefly. Stay in character as Nova.")
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userMessage)
            })
        }

        val tools = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "web_search")
            })
        }

        val body = JsonObject().apply {
            addProperty("model", CHAT_MODEL)
            add("messages", messages)
            add("tools", tools)
            addProperty("max_tokens", 512)
            addProperty("temperature", 0.7)
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "OpenAI web search failed: ${response.code} - $responseBody")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString

            val usage = json.getAsJsonObject("usage")
            val totalTokens = usage?.get("total_tokens")?.asInt ?: 0
            CloudConfig.trackOpenAiTokens(context, totalTokens)

            content
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI web search error", e)
            null
        }
    }

    // ── Whisper STT (cloud speech-to-text) ─────────────────────

    /**
     * Transcribe an audio file using OpenAI Whisper.
     * Only used if local Whisper fails or user enables "HD voice" mode.
     *
     * @param audioFile Audio file (m4a, mp3, wav, etc.)
     * @param context Android context
     * @return Transcribed text, or null on failure
     */
    suspend fun transcribe(
        audioFile: File,
        context: Context
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "OpenAI API key not configured")
            return@withContext null
        }

        if (!audioFile.exists()) {
            Log.e(TAG, "Audio file does not exist: ${audioFile.absolutePath}")
            return@withContext null
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", WHISPER_MODEL)
            .addFormDataPart(
                "file",
                audioFile.name,
                RequestBody.create("audio/mpeg".toMediaType(), audioFile)
            )
            .build()

        val request = Request.Builder()
            .url(TRANSCRIPTION_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "Whisper STT failed: ${response.code} - $responseBody")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            json.get("text")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Whisper STT error", e)
            null
        }
    }

    // ── GPT-4o-mini (cheap model for ThinkingLoop) ────────────────

    /**
     * Non-streaming completion with GPT-4o-mini.
     * Used by NovaThinkingLoop for background inner monologue.
     * ~$0.15/1M input, $0.60/1M output — very cheap.
     */
    suspend fun miniCompletion(
        systemPrompt: String,
        userPrompt: String,
        context: Context
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) return@withContext null

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userPrompt)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", "gpt-4o-mini")
            add("messages", messages)
            addProperty("max_tokens", 400)
            addProperty("temperature", 0.7)
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "GPT-4o-mini failed: ${response.code} - $responseBody")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString

            val usage = json.getAsJsonObject("usage")
            val totalTokens = usage?.get("total_tokens")?.asInt ?: 0
            CloudConfig.trackOpenAiTokens(context, totalTokens)

            content
        } catch (e: Exception) {
            Log.e(TAG, "GPT-4o-mini error", e)
            null
        }
    }

    // ── Embeddings (for semantic memory search) ─────────────────

    /**
     * Generate an embedding vector using text-embedding-3-small.
     * Returns 1536-dim float array, or null on failure.
     * Cost: ~$0.02 per 1M tokens — negligible.
     */
    suspend fun getEmbedding(
        text: String,
        context: Context
    ): FloatArray? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) return@withContext null

        val body = JsonObject().apply {
            addProperty("model", "text-embedding-3-small")
            addProperty("input", text)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "Embedding failed: ${response.code} - $responseBody")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val embeddingArray = json.getAsJsonArray("data")
                ?.get(0)?.asJsonObject
                ?.getAsJsonArray("embedding")

            val usage = json.getAsJsonObject("usage")
            val totalTokens = usage?.get("total_tokens")?.asInt ?: 0
            CloudConfig.trackOpenAiTokens(context, totalTokens)

            embeddingArray?.let { arr ->
                FloatArray(arr.size()) { i -> arr[i].asFloat }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding error", e)
            null
        }
    }

    // ── Vision Completion (GPT-4o with image) ─────────────────

    /**
     * Vision-enabled chat completion via GPT-4o.
     * Sends a base64-encoded JPEG image alongside a text prompt using
     * the multi-modal content array format.
     *
     * Used by AgentExecutor for visual element identification when
     * the accessibility tree fails to locate a target element.
     *
     * @param systemPrompt System instructions for the VLM
     * @param userText Text prompt describing what to find
     * @param imageBase64 JPEG image encoded as base64 string (no data: prefix)
     * @param context Android context for token tracking
     * @return Raw response content string, or null on failure
     */
    suspend fun visionCompletion(
        systemPrompt: String,
        userText: String,
        imageBase64: String,
        context: Context
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "OpenAI API key not configured for vision")
            return@withContext null
        }

        // Build multi-modal content array for user message
        val userContent = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", userText)
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:image/jpeg;base64,$imageBase64")
                    addProperty("detail", "low") // Fixed 512x512 budget (~85 tokens)
                })
            })
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", CHAT_MODEL)
            add("messages", messages)
            addProperty("max_tokens", 300)
            addProperty("temperature", 0.2) // Very low for precise coordinate output
            add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).executeSuspend()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "OpenAI vision failed: ${response.code} - ${responseBody?.take(200)}")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString

            // Track usage
            val usage = json.getAsJsonObject("usage")
            val totalTokens = usage?.get("total_tokens")?.asInt ?: 0
            CloudConfig.trackOpenAiTokens(context, totalTokens)

            content
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI vision error", e)
            null
        }
    }

    // ── Gemini Fallback (chat completion) ────────────────────────

    /**
     * Gemini chat completion as fallback when OpenAI is unavailable.
     */
    suspend fun geminiChatCompletion(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        context: Context,
        injectedContext: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.geminiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "Gemini API key not configured")
            return@withContext null
        }

        val systemWithContext = if (!injectedContext.isNullOrBlank()) {
            "${novaSystemPrompt()}\n\n$injectedContext"
        } else {
            novaSystemPrompt()
        }

        val contents = JsonArray().apply {
            // Gemini doesn't have a system role — prepend as first user message
            add(JsonObject().apply {
                addProperty("role", "user")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", "[System instruction] $systemWithContext")
                    })
                })
            })
            add(JsonObject().apply {
                addProperty("role", "model")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", "Got it. I'm Nova.")
                    })
                })
            })
            // Conversation history
            for ((role, content) in conversationHistory) {
                add(JsonObject().apply {
                    addProperty("role", if (role == "user") "user" else "model")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", content) })
                    })
                })
            }
            // Current message
            add(JsonObject().apply {
                addProperty("role", "user")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", userMessage) })
                })
            })
        }

        val body = JsonObject().apply {
            add("contents", contents)
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", 512)
                addProperty("temperature", 0.8)
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
                Log.e(TAG, "Gemini chat failed: ${response.code} - $responseBody")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val parts = json.getAsJsonArray("candidates")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")

            parts?.get(0)?.asJsonObject?.get("text")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Gemini chat error", e)
            null
        }
    }
}
