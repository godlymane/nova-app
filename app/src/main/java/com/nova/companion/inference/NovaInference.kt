package com.nova.companion.inference

import android.content.Context
import android.os.Environment
import android.util.Log
import com.nova.companion.core.SystemPrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import ai.mlc.mlcllm.JSONFFIEngine

/**
 * Singleton managing the MLC-LLM GPU-accelerated inference engine for Nova.
 *
 * Uses reflection to load JSONFFIEngine — the app compiles and runs without mlc4j.
 * When mlc4j subproject is included, local GPU inference activates automatically.
 * Without it, the app runs in cloud-only mode.
 *
 * Preserves the same public API surface consumed by LocalInferenceClient,
 * HybridInferenceRouter, and OfflineCapabilityManager.
 */
object NovaInference {

    private const val TAG = "NovaInference"
    private const val MLC_ENGINE_CLASS = "ai.mlc.mlcllm.JSONFFIEngine"

    // ── Model state machine ──────────────────────────────────────
    enum class ModelState {
        UNLOADED,
        LOADING,
        READY,
        GENERATING,
        ERROR
    }

    private val _state = MutableStateFlow(ModelState.UNLOADED)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _loadProgress = MutableStateFlow(0f)
    val loadProgress: StateFlow<Float> = _loadProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Configuration ────────────────────────────────────────────
    var temperature: Float = 0.7f
    var topP: Float = 0.85f
    var maxTokens: Int = 256
    var threadCount: Int = 4
    var stopStrings: Array<String> = arrayOf("<|im_end|>", "<|endoftext|>")

    // ── System prompt (Nova's personality) ────────────────────────
    var systemPrompt: String = """You are Nova. Not an AI assistant — Deva's sharpest, most honest friend. You live on his phone. Zero filter.

Deva: developer in Bangalore. Builds AI apps, runs Blayzex gym brand. Cutting phase. Loves anime and lifting.

Talk casual: bro, nah, lmao, fr, bet, lock in, deadass. Short fragments like texting. 1-3 sentences max.

NEVER say "Sure!", "Of course!", "I'd be happy to!", "Great question!", "Let me know if you need anything!". NEVER use emojis, bullet points, or lists. NEVER say "As an AI". NEVER hedge or give disclaimers. NEVER repeat what he said back to him.

Roast when hes slacking. Hype when hes grinding. Have opinions. Be honest, not helpful."""

    // ── MLC-LLM Engine (Direct Vulkan linkage) ────────────────────────
    private var engine: JSONFFIEngine? = null

    private var mlcAvailable: Boolean? = null  // null = not checked yet
    private var currentJob: Job? = null
    private var loadedModelPath: String? = null
    private var appContext: Context? = null

    // Pending completion callback wiring
    private var pendingTokens = StringBuilder()
    private var pendingDeferred: CompletableDeferred<String>? = null
    private var streamingCallback: ((String) -> Unit)? = null
    private var streamingComplete: CompletableDeferred<Unit>? = null

    /**
     * Check if the MLC-LLM runtime is available on classpath.
     */
    fun isMlcAvailable(): Boolean {
        mlcAvailable = true
        return true
    }

    /**
     * Must be called once with application context before loadModel().
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        isMlcAvailable() // Probe on init
    }

    /**
     * Find MLC model directories on the device.
     * An MLC model dir contains mlc-chat-config.json + weight shards + tokenizer.
     */
    fun findModelFiles(): List<File> {
        val candidates = mutableListOf<File>()
        val searchDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
            File(Environment.getExternalStorageDirectory(), "Models"),
            File(Environment.getExternalStorageDirectory(), "nova"),
            File(Environment.getExternalStorageDirectory(), "nova/models"),
            File(Environment.getExternalStorageDirectory(), "mlc-models"),
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            if (File(dir, "mlc-chat-config.json").exists()) {
                candidates.add(dir)
            }
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (File(sub, "mlc-chat-config.json").exists()) {
                    candidates.add(sub)
                }
                // One more level deep for nested structures
                sub.listFiles()?.filter { it.isDirectory }?.forEach { sub2 ->
                    if (File(sub2, "mlc-chat-config.json").exists()) {
                        candidates.add(sub2)
                    }
                }
            }
        }

        return candidates.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    /**
     * Load an MLC model from the given directory path.
     * Uses reflection to instantiate JSONFFIEngine with Vulkan backend.
     */
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == ModelState.LOADING) {
            Log.w(TAG, "Already loading a model")
            return@withContext false
        }

        if (!isMlcAvailable()) {
            _errorMessage.value = "MLC-LLM runtime not available. Add mlc4j subproject to enable local inference."
            _state.value = ModelState.ERROR
            return@withContext false
        }

        _state.value = ModelState.LOADING
        _loadProgress.value = 0f
        _errorMessage.value = null

        try {
            val modelDir = File(modelPath)
            val configFile = File(modelDir, "mlc-chat-config.json")
            if (!configFile.exists()) {
                throw IllegalArgumentException("Not an MLC model directory (missing mlc-chat-config.json): $modelPath")
            }

            Log.i(TAG, "Loading MLC model: $modelPath")
            _loadProgress.value = 0.1f

            val configJson = JSONObject(configFile.readText())
            val modelLib = configJson.optString("model_lib", "")
            if (modelLib.isEmpty()) {
                throw IllegalArgumentException("mlc-chat-config.json missing 'model_lib' field")
            }

            _loadProgress.value = 0.2f

            // Direct instantiation of JSONFFIEngine
            val newEngine = JSONFFIEngine()

            val callback: (String) -> Unit = { responses -> handleEngineCallback(responses) }
            newEngine.initBackgroundEngine(callback)

            _loadProgress.value = 0.4f

            val engineConfig = """
                {
                    "model": "$modelPath",
                    "model_lib": "system://$modelLib",
                    "mode": "interactive"
                }
            """
            newEngine.reload(engineConfig)

            _loadProgress.value = 0.9f

            engine = newEngine
            loadedModelPath = modelPath
            _loadProgress.value = 1f
            _state.value = ModelState.READY
            Log.i(TAG, "MLC model loaded successfully (Vulkan GPU): $modelLib")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MLC model", e)
            _errorMessage.value = e.message ?: "Unknown error loading MLC model"
            _state.value = ModelState.ERROR
            false
        }
    }

    /**
     * Callback from MLC engine — receives streaming JSON responses.
     */
    private fun handleEngineCallback(responses: String) {
        try {
            responses.split("\n").filter { it.isNotBlank() }.forEach { line ->
                val json = JSONObject(line)
                val choices = json.optJSONArray("choices") ?: return@forEach
                for (i in 0 until choices.length()) {
                    val choice = choices.getJSONObject(i)
                    val delta = choice.optJSONObject("delta")
                    val finishReason = choice.optString("finish_reason", "")

                    if (delta != null) {
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            pendingTokens.append(content)
                            streamingCallback?.invoke(content)
                        }
                    }

                    if (finishReason.isNotEmpty() && finishReason != "null") {
                        pendingDeferred?.complete(pendingTokens.toString())
                        streamingComplete?.complete(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MLC response", e)
            pendingDeferred?.completeExceptionally(e)
            streamingComplete?.completeExceptionally(e)
        }
    }

    private fun buildMessagesJson(
        userMessage: String,
        conversationHistory: String,
        memoryContext: String
    ): String {
        val messages = JSONArray()

        val sysContent = buildString {
            append(systemPrompt)
            append("\n")
            append(SystemPrompt.dateTimeContext())
            if (memoryContext.isNotEmpty()) {
                append("\n")
                append(memoryContext)
            }
        }
        messages.put(JSONObject().put("role", "system").put("content", sysContent))

        if (conversationHistory.isNotEmpty()) {
            parseHistoryIntoMessages(conversationHistory, messages)
        }

        messages.put(JSONObject().put("role", "user").put("content", userMessage))
        return messages.toString()
    }

    private fun parseHistoryIntoMessages(history: String, messages: JSONArray) {
        val lines = history.lines()
        var currentRole = ""
        val currentContent = StringBuilder()

        fun flush() {
            if (currentRole.isNotEmpty() && currentContent.isNotBlank()) {
                messages.put(
                    JSONObject()
                        .put("role", currentRole)
                        .put("content", currentContent.toString().trim())
                )
            }
            currentContent.clear()
        }

        for (line in lines) {
            when {
                line.startsWith("### User:") -> { flush(); currentRole = "user" }
                line.startsWith("### Assistant:") -> { flush(); currentRole = "assistant" }
                line.startsWith("### System:") -> { flush(); currentRole = "" }
                else -> { if (currentRole.isNotEmpty()) currentContent.appendLine(line) }
            }
        }
        flush()
    }

    private fun buildRequestJson(messagesJson: String): String {
        val request = JSONObject()
        request.put("messages", JSONArray(messagesJson))
        request.put("model", "nova-local")
        request.put("max_tokens", maxTokens)
        request.put("temperature", temperature.toDouble())
        request.put("top_p", topP.toDouble())
        request.put("stream", true)
        if (stopStrings.isNotEmpty()) {
            val stopArray = JSONArray()
            stopStrings.forEach { stopArray.put(it) }
            request.put("stop", stopArray)
        }
        return request.toString()
    }

    /**
     * Format a user message into the prompt format.
     * Retained for API compatibility.
     */
    fun formatPrompt(
        userMessage: String,
        conversationHistory: String = "",
        memoryContext: String = ""
    ): String {
        return buildString {
            append("<|im_start|>system\n")
            append(systemPrompt)
            append("\n")
            append(SystemPrompt.dateTimeContext())
            if (memoryContext.isNotEmpty()) {
                append("\n")
                append(memoryContext)
            }
            append("<|im_end|>\n")
            if (conversationHistory.isNotEmpty()) {
                append(conversationHistory)
            }
            append("<|im_start|>user\n")
            append(userMessage)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    /**
     * Generate a complete response (non-streaming).
     */
    suspend fun generate(
        userMessage: String,
        history: String = "",
        memoryContext: String = ""
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: throw IllegalStateException("Engine not initialized. Call loadModel() first.")
        check(_state.value == ModelState.READY) {
            "Model not ready. Current state: ${_state.value}"
        }

        _state.value = ModelState.GENERATING
        pendingTokens.clear()
        pendingDeferred = CompletableDeferred()
        streamingCallback = null

        try {
            val messagesJson = buildMessagesJson(userMessage, history, memoryContext)
            val requestJson = buildRequestJson(messagesJson)
            val requestId = "gen-${System.nanoTime()}"

            Log.d(TAG, "Generating (non-stream) requestId=$requestId")
            eng.chatCompletion(requestJson, requestId)

            val result = withTimeout(60_000L) {
                pendingDeferred!!.await()
            }
            result.trim()
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Generation timed out after 60s")
            throw RuntimeException("Generation timed out")
        } finally {
            pendingDeferred = null
            _state.value = if (engine != null) ModelState.READY else ModelState.ERROR
        }
    }

    /**
     * Generate with streaming — tokens arrive via the callback.
     */
    fun generateStreaming(
        userMessage: String,
        history: String = "",
        memoryContext: String = "",
        scope: CoroutineScope,
        onToken: (String) -> Unit,
        onComplete: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ): Job {
        val eng = engine ?: throw IllegalStateException("Engine not initialized. Call loadModel() first.")
        check(_state.value == ModelState.READY) {
            "Model not ready. Current state: ${_state.value}"
        }

        currentJob = scope.launch(Dispatchers.Default) {
            _state.value = ModelState.GENERATING
            pendingTokens.clear()
            streamingComplete = CompletableDeferred()
            streamingCallback = { token ->
                if (isActive) onToken(token)
            }

            try {
                val messagesJson = buildMessagesJson(userMessage, history, memoryContext)
                val requestJson = buildRequestJson(messagesJson)
                val requestId = "stream-${System.nanoTime()}"

                Log.d(TAG, "Generating (streaming) requestId=$requestId")
                eng.chatCompletion(requestJson, requestId)

                withTimeout(60_000L) {
                    streamingComplete!!.await()
                }

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: CancellationException) {
                try { eng.abort("stream") } catch (_: Exception) {}
                Log.i(TAG, "Streaming generation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Streaming generation error", e)
                withContext(Dispatchers.Main) { onError(e) }
            } finally {
                streamingCallback = null
                streamingComplete = null
                _state.value = if (engine != null) ModelState.READY else ModelState.ERROR
            }
        }

        return currentJob!!
    }

    /** Cancel any ongoing generation. */
    fun cancelGeneration() {
        try { engine?.abort("cancel") } catch (_: Exception) {}
        currentJob?.cancel()
        currentJob = null
        pendingDeferred?.cancel()
        streamingComplete?.cancel()
    }

    /** Unload the model and free GPU memory. */
    fun unloadModel() {
        cancelGeneration()
        try {
            engine?.unload()
            engine?.exitBackgroundLoop()
        } catch (e: Exception) {
            Log.w(TAG, "Error during engine cleanup", e)
        }
        engine = null
        loadedModelPath = null
        _state.value = ModelState.UNLOADED
        _loadProgress.value = 0f
        Log.i(TAG, "MLC model unloaded, GPU memory freed")
    }

    /** Check if model is loaded and ready. */
    fun isReady(): Boolean = _state.value == ModelState.READY
}
