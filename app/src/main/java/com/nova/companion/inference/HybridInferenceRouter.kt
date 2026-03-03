package com.nova.companion.inference

import android.content.Context
import android.util.Log
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.CloudLLMService
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.brain.context.ContextInjector
import com.nova.companion.core.NovaMode
import com.nova.companion.core.NovaRouter
import com.nova.companion.memory.MemoryManager
import com.nova.companion.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HybridInferenceRouter — "The Brain Stem"
 *
 * Intercepts ALL prompts and routes to the optimal inference backend:
 *
 * ┌────────────────────────────────────────────────────────────┐
 * │                     User Message                           │
 * │                         │                                  │
 * │                  ┌──────▼──────┐                           │
 * │                  │ Classify    │                           │
 * │                  │ Complexity  │                           │
 * │                  └──────┬──────┘                           │
 * │            ┌────────┬───┴───┬────────┐                    │
 * │         INSTANT  SIMPLE  COMPLEX  CLOUD_ONLY              │
 * │            │        │       │        │                     │
 * │         Local    Local    Cloud    Cloud                   │
 * │        (32 tok) (256 tok) (512tok) (required)             │
 * │            │        │       │        │                     │
 * │            └────────┴───┬───┴────────┘                    │
 * │                         │                                  │
 * │                    Response                                │
 * └────────────────────────────────────────────────────────────┘
 *
 * Task Complexity Levels:
 * - INSTANT: Wake-word ack, yes/no, greetings → local SLM, 32 tokens max
 * - SIMPLE: Casual chat, quick facts, short opinions → local SLM, 256 tokens
 * - COMPLEX: Analysis, coding, detailed explanations → cloud LLM
 * - CLOUD_ONLY: Vision, web search, multi-step automation, embeddings → cloud (no local fallback)
 *
 * Offline Behavior:
 * - INSTANT/SIMPLE: Work normally via local model
 * - COMPLEX: Downgrade to local with warning ("I'm offline so this might be rough...")
 * - CLOUD_ONLY: Fail gracefully ("I need internet for that one")
 *
 * Gap-Filling Strategy:
 * For COMPLEX tasks when online, the router fires BOTH local and cloud in parallel:
 * - Local generates a quick acknowledgment (32 tokens, ~200ms)
 * - Cloud processes the full request (512 tokens, ~2-4 seconds)
 * - UI shows local ack immediately, then replaces with cloud response
 */
object HybridInferenceRouter {

    private const val TAG = "HybridRouter"

    // ── Task Complexity Classification ───────────────────────────────

    enum class TaskComplexity {
        INSTANT,     // Greetings, acks, yes/no → local, 32 tokens
        SIMPLE,      // Casual chat, opinions → local, 256 tokens
        COMPLEX,     // Analysis, coding, explanations → cloud preferred
        CLOUD_ONLY   // Vision, web search, automation → cloud required
    }

    // ── Offline Tool Set ─────────────────────────────────────────────

    /**
     * Tools that work without internet (Tier 1 intents + Tier 2 device controls).
     * These can be triggered by the local SLM when offline.
     */
    val OFFLINE_TOOLS = setOf(
        // Tier 1: Intent-based (no cloud needed)
        "openApp",
        "setAlarm",
        "setTimer",
        "makeCall",
        "sendSms",
        "lookupContact",

        // Tier 2: Device controls (purely local)
        "toggleWifi",
        "toggleBluetooth",
        "toggleFlashlight",
        "setDnd",
        "setBrightness",
        "setVolume",
        "controlMedia",
        "getBatteryInfo",
        "getDeviceInfo",
        "readNotifications",
        "takeScreenshot"
    )

    /**
     * Tools that require internet and cannot work offline.
     */
    private val CLOUD_REQUIRED_TOOLS = setOf(
        "searchWeb",
        "openUrl",
        "getWeather",
        "getDirections",
        "sendWhatsApp",
        "sendWhatsAppFull",
        "sendEmail",
        "orderFood",
        "bookRide",
        "playSpotify",
        "playYouTube",
        "shareContent",
        "translateText",
        "postInstagramStory"
    )

    // ── Keywords for classification ──────────────────────────────────

    private val INSTANT_PATTERNS = setOf(
        "hey", "hi", "hello", "sup", "yo", "what's up", "wassup",
        "yes", "no", "yeah", "nah", "sure", "ok", "okay", "bet", "cool",
        "thanks", "thank you", "bye", "later", "night", "gn", "gm",
        "good morning", "good night", "what time"
    )

    private val COMPLEX_PATTERNS = setOf(
        "explain", "analyze", "compare", "write code", "debug", "fix this",
        "help me with", "break down", "in detail", "step by step",
        "what do you think about", "review", "summarize", "rewrite",
        "how does", "how do i", "what is the difference", "pros and cons",
        "create a", "design", "architect", "implement", "refactor",
        "essay", "paragraph", "article", "story"
    )

    private val CLOUD_ONLY_PATTERNS = setOf(
        "weather", "news", "stock", "price", "score", "trending",
        "search", "google", "look up", "find online",
        "what's happening", "latest", "current"
    )

    // ── Main Routing API ─────────────────────────────────────────────

    /**
     * Route a text message through the hybrid inference system.
     *
     * This is the primary entry point — replaces direct calls to
     * InferenceRouter.route() or NovaInference.generate().
     *
     * @param userMessage User's input text
     * @param context Android context
     * @param conversationHistory Chat history as (role, content) pairs
     * @param memoryContext Injected memory/device context string
     * @param scope Coroutine scope for streaming
     * @param onToken Token callback for streaming responses
     * @param onComplete Called with the full final response
     * @param onError Called on failure
     * @param onGapFill Called with quick local ack while cloud is processing (optional)
     */
    suspend fun route(
        userMessage: String,
        context: Context,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        memoryContext: String = "",
        scope: CoroutineScope,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onGapFill: ((String) -> Unit)? = null
    ) {
        val isOnline = CloudConfig.isOnline(context)
        val complexity = classifyComplexity(userMessage)
        val localReady = LocalInferenceClient.isReady()

        Log.i(TAG, "route() — complexity=$complexity, online=$isOnline, localReady=$localReady")

        when (complexity) {
            TaskComplexity.INSTANT -> {
                // Always local — ultra fast
                routeLocal(userMessage, conversationHistory, memoryContext, 32, scope, onToken, onComplete, onError)
            }

            TaskComplexity.SIMPLE -> {
                if (localReady) {
                    // Local is great for simple tasks
                    routeLocal(userMessage, conversationHistory, memoryContext, 256, scope, onToken, onComplete, onError)
                } else if (isOnline) {
                    // No local model → fall back to cloud
                    routeCloud(userMessage, conversationHistory, memoryContext, context, scope, onToken, onComplete, onError)
                } else {
                    onError(Exception("No local model loaded and no internet. Load a model in Settings."))
                }
            }

            TaskComplexity.COMPLEX -> {
                if (isOnline) {
                    // Cloud preferred — but fire local gap-fill in parallel
                    if (localReady && onGapFill != null) {
                        scope.launch(Dispatchers.IO) {
                            val ack = LocalInferenceClient.quickResponse(userMessage)
                            if (ack != null) {
                                withContext(Dispatchers.Main) { onGapFill(ack) }
                            }
                        }
                    }
                    routeCloud(userMessage, conversationHistory, memoryContext, context, scope, onToken, onComplete, onError)
                } else if (localReady) {
                    // Offline — downgrade to local with warning
                    Log.w(TAG, "Complex task routed locally (offline)")
                    onToken("(Offline mode — response quality may vary)\n\n")
                    routeLocal(userMessage, conversationHistory, memoryContext, 512, scope, onToken, onComplete, onError)
                } else {
                    onError(Exception("I need internet for this one and no local model is loaded."))
                }
            }

            TaskComplexity.CLOUD_ONLY -> {
                if (isOnline) {
                    routeCloud(userMessage, conversationHistory, memoryContext, context, scope, onToken, onComplete, onError)
                } else {
                    onError(Exception("I need internet for that — can't do web searches or live data offline."))
                }
            }
        }
    }

    /**
     * Route an automation message (device actions).
     *
     * Online: Uses CloudLLMService with full tool calling (all tiers).
     * Offline: Uses LocalInferenceClient with offline tools only (Tier 1/2).
     */
    suspend fun routeAutomation(
        userMessage: String,
        context: Context,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onToolCall: (String) -> Unit,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val isOnline = CloudConfig.isOnline(context)

        if (isOnline) {
            // Full cloud automation with all tools
            Log.i(TAG, "Routing automation to cloud (all tools available)")
            try {
                val toolDefs = ToolRegistry.getToolDefinitionsForLLM()
                CloudLLMService.processWithTools(
                    userMessage = userMessage,
                    toolDefinitions = toolDefs,
                    context = context,
                    conversationHistory = conversationHistory,
                    onToolCall = { name, args ->
                        onToolCall(name)
                        val tool = ToolRegistry.getTool(name)
                        tool?.executor?.invoke(context, args)
                            ?: com.nova.companion.tools.ToolResult(false, "Unknown tool: $name")
                    },
                    onResponse = onResponse,
                    onError = onError
                )
            } catch (e: Exception) {
                Log.e(TAG, "Cloud automation failed", e)
                // Try local fallback for offline-capable tools
                if (LocalInferenceClient.isReady()) {
                    Log.i(TAG, "Falling back to local automation")
                    routeLocalAutomation(userMessage, context, onToolCall, onResponse, onError)
                } else {
                    onError("Automation failed: ${e.message}")
                }
            }
        } else {
            // Offline — local model with restricted tool set
            if (LocalInferenceClient.isReady()) {
                routeLocalAutomation(userMessage, context, onToolCall, onResponse, onError)
            } else {
                onError("I'm offline and no local model is loaded. Can't run automation right now.")
            }
        }
    }

    // ── Classification Logic ─────────────────────────────────────────

    /**
     * Classify the complexity of a user message.
     *
     * Uses a fast keyword-based approach (no LLM needed):
     * 1. Check for INSTANT patterns (greetings, yes/no)
     * 2. Check for CLOUD_ONLY patterns (web search, live data)
     * 3. Check for COMPLEX patterns (analysis, coding, detailed)
     * 4. Default to SIMPLE (casual chat)
     */
    fun classifyComplexity(message: String): TaskComplexity {
        val lower = message.lowercase().trim()
        val wordCount = lower.split("\\s+".toRegex()).size

        // Very short messages are almost always INSTANT
        if (wordCount <= 3 && INSTANT_PATTERNS.any { lower.contains(it) }) {
            return TaskComplexity.INSTANT
        }

        // Check if automation intent (handled separately, but classify as COMPLEX minimum)
        if (NovaRouter.classifyIntent(message) == NovaMode.AUTOMATION) {
            // Automation with online tools → CLOUD_ONLY if it needs web
            if (CLOUD_ONLY_PATTERNS.any { lower.contains(it) }) {
                return TaskComplexity.CLOUD_ONLY
            }
            // Automation with offline-capable tools → SIMPLE (local can handle)
            return TaskComplexity.SIMPLE
        }

        // Cloud-only patterns (live data, web search)
        if (CLOUD_ONLY_PATTERNS.any { lower.contains(it) }) {
            return TaskComplexity.CLOUD_ONLY
        }

        // Complex patterns (analysis, coding, detailed)
        if (COMPLEX_PATTERNS.any { lower.contains(it) }) {
            return TaskComplexity.COMPLEX
        }

        // Long messages tend to need more capable models
        if (wordCount > 30) {
            return TaskComplexity.COMPLEX
        }

        // Default: casual chat → SIMPLE (local model handles great)
        return TaskComplexity.SIMPLE
    }

    // ── Local Routing ────────────────────────────────────────────────

    private suspend fun routeLocal(
        userMessage: String,
        conversationHistory: List<Pair<String, String>>,
        memoryContext: String,
        maxTokens: Int,
        scope: CoroutineScope,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (!LocalInferenceClient.isReady()) {
            onError(Exception("Local model not loaded"))
            return
        }

        // Temporarily adjust max tokens for this request
        val savedMaxTokens = NovaInference.maxTokens
        NovaInference.maxTokens = maxTokens

        try {
            val job = LocalInferenceClient.chatCompletionStreaming(
                userMessage = userMessage,
                conversationHistory = conversationHistory,
                injectedContext = memoryContext,
                scope = scope,
                onToken = onToken,
                onComplete = onComplete,
                onError = { msg -> onError(Exception(msg)) }
            )
            job?.join()
        } finally {
            NovaInference.maxTokens = savedMaxTokens
        }
    }

    // ── Cloud Routing ────────────────────────────────────────────────

    private suspend fun routeCloud(
        userMessage: String,
        conversationHistory: List<Pair<String, String>>,
        memoryContext: String,
        context: Context,
        scope: CoroutineScope,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val historyString = buildHistoryString(conversationHistory)

        try {
            InferenceRouter.route(
                userMessage = userMessage,
                context = context,
                conversationHistory = historyString,
                memoryContext = memoryContext,
                onToken = onToken,
                onComplete = onComplete,
                onError = onError
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cloud routing failed, trying local fallback", e)
            // Fall back to local if available
            if (LocalInferenceClient.isReady()) {
                routeLocal(userMessage, conversationHistory, memoryContext, 256, scope, onToken, onComplete, onError)
            } else {
                onError(e)
            }
        }
    }

    // ── Local Automation ─────────────────────────────────────────────

    private suspend fun routeLocalAutomation(
        userMessage: String,
        context: Context,
        onToolCall: (String) -> Unit,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "Running local automation with offline tools")
        LocalInferenceClient.localToolCompletion(
            userMessage = userMessage,
            context = context,
            offlineToolNames = OFFLINE_TOOLS,
            onToolCall = onToolCall,
            onResponse = onResponse,
            onError = onError
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun buildHistoryString(history: List<Pair<String, String>>): String {
        if (history.isEmpty()) return ""
        return buildString {
            for ((role, content) in history) {
                val label = if (role == "user") "User" else "Assistant"
                append("### $label:\n$content\n")
            }
        }
    }
}
