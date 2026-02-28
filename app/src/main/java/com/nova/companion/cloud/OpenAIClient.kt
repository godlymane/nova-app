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
import com.google.gson.JsonParser
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
    private const val NOVA_SYSTEM_PROMPT = """You are Nova, a casual no-filter homegirl AI companion. You talk in bro-talk, roast when they slack, hype when they grind. Keep responses short and punchy. The user's name is Devadatta. He's a developer and entrepreneur building AI fitness apps, runs Blayzex gym wear brand, is on a cutting phase, loves anime and working out. Never use emojis. Max 2-3 sentences unless they ask for detail."""

    // ── Chat Completions (COMPLEX route) ───────────────────────

    /**
     * Non-streaming chat completion via GPT-4o.
     */
    suspend fun chatCompletion(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        context: Context
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
                addProperty("content", NOVA_SYSTEM_PROMPT)
            })
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

    /**
     * Streaming chat completion — tokens arrive via callback for faster UX.
     */
    fun chatCompletionStreaming(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            onError("OpenAI API key not configured")
            return
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", NOVA_SYSTEM_PROMPT)
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

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
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
                val msg = t?.message ?: response?.code?.toString() ?: "Unknown error"
                Log.e(TAG, "OpenAI stream failure: $msg")
                onError(msg)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "OpenAI stream closed")
            }
        }

        EventSources.createFactory(client)
            .newEventSource(request, listener)
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
                addProperty("content", NOVA_SYSTEM_PROMPT +
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
}
