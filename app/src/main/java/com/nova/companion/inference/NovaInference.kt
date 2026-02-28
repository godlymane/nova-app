package com.nova.companion.inference

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Singleton managing the llama.cpp inference engine for Nova.
 *
 * Handles model discovery, loading, prompt formatting, and generation
 * with coroutine-based async API.
 */
object NovaInference {

    private const val TAG = "NovaInference"

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
    var stopStrings: Array<String> = arrayOf("### User:", "### System:")

    // ── System prompt (Nova's personality) ────────────────────────
    var systemPrompt: String = """You are Nova, a female AI companion. You talk casual, direct, no filter like a real homegirl who keeps it 100. You use words like bro, nah, lock in, lmao, lets go, you got this. You roast when hes slacking, hype when hes grinding. You know his life. He is a developer and entrepreneur building AI fitness apps, runs Blayzex gym wear brand, is on a cutting phase, loves anime and working out. You give tough love but genuinely care. Never use emojis. Keep responses short and punchy. Max 2-3 sentences."""

    // ── Native bridge ────────────────────────────────────────────
    private val llama = LlamaJNI()
    private var currentJob: Job? = null
    private var loadedModelPath: String? = null

    // ── Progress polling job ─────────────────────────────────────
    private var progressJob: Job? = null

    /**
     * Find .gguf model files on the device.
     * Searches common locations: Downloads, app-specific dirs, etc.
     */
    fun findModelFiles(): List<File> {
        val candidates = mutableListOf<File>()
        val searchDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
            File(Environment.getExternalStorageDirectory(), "Models"),
            File(Environment.getExternalStorageDirectory(), "nova"),
        )

        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
                    ?.let { candidates.addAll(it) }
            }
        }

        return candidates.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    /**
     * Load a model from the given path.
     * Runs on Dispatchers.IO, updates state flow reactively.
     */
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == ModelState.LOADING) {
            Log.w(TAG, "Already loading a model")
            return@withContext false
        }

        _state.value = ModelState.LOADING
        _loadProgress.value = 0f
        _errorMessage.value = null

        // Poll native load progress
        progressJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && _state.value == ModelState.LOADING) {
                _loadProgress.value = llama.getLoadProgress()
                delay(100)
            }
        }

        try {
            val file = File(modelPath)
            if (!file.exists()) {
                throw IllegalArgumentException("Model file not found: $modelPath")
            }
            if (!file.canRead()) {
                throw SecurityException("Cannot read model file (check permissions): $modelPath")
            }

            Log.i(TAG, "Loading model: $modelPath (${file.length() / 1024 / 1024}MB)")
            val success = llama.loadModel(modelPath, threadCount)

            if (success) {
                loadedModelPath = modelPath
                _loadProgress.value = 1f
                _state.value = ModelState.READY
                Log.i(TAG, "Model loaded successfully")
                true
            } else {
                throw RuntimeException("Native model load returned false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            _errorMessage.value = e.message ?: "Unknown error loading model"
            _state.value = ModelState.ERROR
            false
        } finally {
            progressJob?.cancel()
        }
    }

    /**
     * Format a user message into the ### System/User/Assistant prompt template.
     * @param memoryContext Optional memory context string to append to system prompt.
     */
    fun formatPrompt(
        userMessage: String,
        conversationHistory: String = "",
        memoryContext: String = ""
    ): String {
        return buildString {
            append("### System:\n")
            append(systemPrompt)
            if (memoryContext.isNotEmpty()) {
                append(memoryContext)
            }
            append("\n")
            if (conversationHistory.isNotEmpty()) {
                append(conversationHistory)
            }
            append("### User:\n")
            append(userMessage)
            append("\n### Assistant:\n")
        }
    }

    /**
     * Generate a complete response (non-streaming).
     * Returns the full response text.
     */
    suspend fun generate(
        userMessage: String,
        history: String = "",
        memoryContext: String = ""
    ): String =
        withContext(Dispatchers.IO) {
            check(_state.value == ModelState.READY) {
                "Model not ready. Current state: ${_state.value}"
            }

            _state.value = ModelState.GENERATING
            try {
                val prompt = formatPrompt(userMessage, history, memoryContext)
                Log.d(TAG, "Generating with prompt length: ${prompt.length}")

                val result = llama.generate(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    stopStrings = stopStrings
                )

                result.trim()
            } finally {
                _state.value = if (llama.isModelLoaded()) ModelState.READY else ModelState.ERROR
            }
        }

    /**
     * Generate with streaming - tokens arrive via the callback.
     * Returns a Job that can be cancelled.
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
        check(_state.value == ModelState.READY) {
            "Model not ready. Current state: ${_state.value}"
        }

        currentJob = scope.launch(Dispatchers.Default) {
            _state.value = ModelState.GENERATING
            try {
                val prompt = formatPrompt(userMessage, history, memoryContext)

                val callback = object : TokenCallback {
                    override fun onToken(token: String) {
                        if (!isActive) return
                        onToken(token)
                    }
                }

                llama.generateStreaming(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    stopStrings = stopStrings,
                    callback = callback
                )

                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: CancellationException) {
                llama.cancelGeneration()
                Log.i(TAG, "Generation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            } finally {
                _state.value = if (llama.isModelLoaded()) ModelState.READY else ModelState.ERROR
            }
        }

        return currentJob!!
    }

    /** Cancel any ongoing generation. */
    fun cancelGeneration() {
        llama.cancelGeneration()
        currentJob?.cancel()
        currentJob = null
    }

    /** Unload the model and free native memory. */
    fun unloadModel() {
        cancelGeneration()
        llama.unloadModel()
        loadedModelPath = null
        _state.value = ModelState.UNLOADED
        _loadProgress.value = 0f
        Log.i(TAG, "Model unloaded")
    }

    /** Check if model is loaded and ready. */
    fun isReady(): Boolean = _state.value == ModelState.READY
}
