package com.nova.companion.cloud

import android.content.Context
import android.util.Log
import com.nova.companion.inference.NovaInference
import kotlinx.coroutines.*

/**
 * Main entry point for the Cloud Bridge module.
 *
 * The Chat UI and other agents call CloudBridge.processMessage() instead of
 * calling NovaInference directly. The SmartRouter decides whether to handle
 * locally or via cloud APIs.
 *
 * Flow:
 * 1. SmartRouter.route(message) → routeType
 * 2. CASUAL    → local InferenceEngine (NovaInference)
 * 3. COMPLEX   → OpenAI GPT-4o chat completion
 * 4. LIVE_DATA → OpenAI with web_search tool
 * 5. Response  → ElevenLabsTTS.speak() if cloud TTS enabled
 * 6. Return response text + audio via callback
 */
object CloudBridge {

    private const val TAG = "NovaCloud"

    /**
     * Callback interface for async response delivery.
     * Supports streaming text, audio, completion, and error states.
     */
    interface NovaResponseCallback {
        /** Called as text chunks arrive (streaming). */
        fun onTextChunk(text: String)

        /** Called when TTS audio is ready. */
        fun onAudioReady(audioBytes: ByteArray)

        /** Called when the full response is complete. */
        fun onComplete(fullResponse: String)

        /** Called on any error — UI should show fallback. */
        fun onError(error: String)
    }

    /**
     * Process a user message through the smart routing pipeline.
     *
     * @param userMessage The user's input text
     * @param context Android context for network checks and preferences
     * @param conversationHistory Recent chat pairs for context (role, content)
     * @param memoryContext Memory injection string from Agent 4
     * @param callback Async response callback
     * @param scope CoroutineScope for managing async work
     */
    fun processMessage(
        userMessage: String,
        context: Context,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        memoryContext: String = "",
        callback: NovaResponseCallback,
        scope: CoroutineScope
    ): Job {
        val routeType = SmartRouter.route(userMessage)
        Log.i(TAG, "Processing message via route: $routeType")

        return scope.launch {
            try {
                when (routeType) {
                    SmartRouter.RouteType.CASUAL -> {
                        handleCasualRoute(userMessage, conversationHistory, memoryContext, callback)
                    }

                    SmartRouter.RouteType.COMPLEX -> {
                        handleComplexRoute(userMessage, context, conversationHistory, callback)
                    }

                    SmartRouter.RouteType.LIVE_DATA -> {
                        handleLiveDataRoute(userMessage, context, callback)
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Message processing cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "CloudBridge error", e)
                callback.onError(e.message ?: "Unknown error in CloudBridge")
            }
        }
    }

    // ── Route handlers ─────────────────────────────────────────

    /**
     * CASUAL route: delegate to local GGUF inference.
     */
    private suspend fun handleCasualRoute(
        userMessage: String,
        conversationHistory: List<Pair<String, String>>,
        memoryContext: String,
        callback: NovaResponseCallback
    ) {
        if (!NovaInference.isReady()) {
            callback.onError("Local model not loaded — can't handle casual chat")
            return
        }

        val history = conversationHistory.joinToString("\n") { (role, content) ->
            val tag = if (role == "user") "### User:" else "### Assistant:"
            "$tag\n$content"
        }

        val fullResponse = StringBuilder()

        // Use streaming for real-time token delivery
        val job = NovaInference.generateStreaming(
            userMessage = userMessage,
            history = history,
            memoryContext = memoryContext,
            scope = CoroutineScope(Dispatchers.Default),
            onToken = { token ->
                fullResponse.append(token)
                callback.onTextChunk(token)
            },
            onComplete = {
                callback.onComplete(fullResponse.toString().trim())
            },
            onError = { error ->
                callback.onError(error.message ?: "Local inference error")
            }
        )

        // Wait for generation to complete
        job.join()
    }

    /**
     * COMPLEX route: use OpenAI GPT-4o for detailed responses.
     * Falls back to local if cloud fails.
     */
    private suspend fun handleComplexRoute(
        userMessage: String,
        context: Context,
        conversationHistory: List<Pair<String, String>>,
        callback: NovaResponseCallback
    ) {
        // Check if we can use cloud
        if (!CloudConfig.isOnline(context) || !CloudConfig.hasOpenAiKey()) {
            Log.w(TAG, "Cloud unavailable for COMPLEX, falling back to local")
            handleCasualRoute(userMessage, conversationHistory, "", callback)
            return
        }

        // Use streaming for faster UX
        val completionDeferred = CompletableDeferred<String>()

        OpenAIClient.chatCompletionStreaming(
            userMessage = userMessage,
            conversationHistory = conversationHistory,
            onToken = { token ->
                callback.onTextChunk(token)
            },
            onComplete = { fullResponse ->
                callback.onComplete(fullResponse)
                completionDeferred.complete(fullResponse)
            },
            onError = { error ->
                Log.e(TAG, "OpenAI streaming failed: $error, falling back to local")
                completionDeferred.completeExceptionally(Exception(error))
            }
        )

        try {
            val response = completionDeferred.await()
            // Speak response if cloud TTS is enabled
            speakIfEnabled(response, context)
        } catch (e: Exception) {
            // Fallback to local inference
            Log.w(TAG, "Cloud failed, falling back to local inference")
            handleCasualRoute(userMessage, conversationHistory, "", callback)
        }
    }

    /**
     * LIVE_DATA route: use OpenAI with web_search tool.
     * Falls back to local if cloud fails.
     */
    private suspend fun handleLiveDataRoute(
        userMessage: String,
        context: Context,
        callback: NovaResponseCallback
    ) {
        if (!CloudConfig.isOnline(context) || !CloudConfig.hasOpenAiKey()) {
            Log.w(TAG, "Cloud unavailable for LIVE_DATA")
            callback.onError("Need internet for live data — I can't check that offline bro")
            return
        }

        val response = OpenAIClient.webSearch(userMessage, context)

        if (response != null) {
            callback.onTextChunk(response)
            callback.onComplete(response)
            speakIfEnabled(response, context)
        } else {
            callback.onError("Web search failed — couldn't get live data right now")
        }
    }

    // ── TTS helper ─────────────────────────────────────────────

    /**
     * Speak the response via ElevenLabs TTS if cloud voice is enabled.
     */
    private suspend fun speakIfEnabled(text: String, context: Context) {
        val voiceMode = VoiceMode.load(context)
        if (!voiceMode.usesCloudTTS) return

        if (!CloudConfig.hasElevenLabsKey() || !CloudConfig.isOnline(context)) return

        try {
            val audioBytes = ElevenLabsTTS.synthesize(text, context)
            // Audio bytes are available for the callback if needed
            // The actual playback is handled by the voice agent (Agent 3)
            if (audioBytes != null) {
                Log.d(TAG, "TTS audio ready: ${audioBytes.size} bytes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud TTS failed, local Piper will handle it", e)
        }
    }

    // ── Convenience methods ────────────────────────────────────

    /**
     * Quick check: is cloud available right now?
     */
    fun isCloudAvailable(context: Context): Boolean {
        return CloudConfig.isOnline(context) &&
                (CloudConfig.hasOpenAiKey() || CloudConfig.hasElevenLabsKey())
    }

    /**
     * Get current voice mode setting.
     */
    fun getVoiceMode(context: Context): VoiceMode = VoiceMode.load(context)

    /**
     * Update voice mode setting.
     */
    fun setVoiceMode(context: Context, mode: VoiceMode) = VoiceMode.save(context, mode)

    /**
     * Get API usage stats: (ElevenLabs chars, OpenAI tokens).
     */
    fun getUsageStats(context: Context): Pair<Long, Long> = CloudConfig.getUsageStats(context)
}
