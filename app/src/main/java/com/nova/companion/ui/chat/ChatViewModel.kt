package com.nova.companion.ui.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.CloudLLMService
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.core.NovaMode
import com.nova.companion.core.NovaRouter
import com.nova.companion.data.MessageEntity
import com.nova.companion.data.NovaDatabase
import com.nova.companion.inference.NovaInference
import com.nova.companion.inference.NovaInference.ModelState
import com.nova.companion.memory.MemoryManager
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.voice.ElevenLabsVoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessage(
    val id: Long = 0,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val routeTag: String? = null
)

data class SettingsState(
    val modelPath: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 256,
    val availableModels: List<File> = emptyList()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val appContext: Context = application.applicationContext
    private val database = NovaDatabase.getInstance(application)
    private val messageDao = database.messageDao()
    private val prefs = application.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)

    // ── Memory System ──────────────────────────────────────────
    val memoryManager = MemoryManager(database)

    // ── Router ─────────────────────────────────────────────────
    val router = NovaRouter

    // ── Tool Registry ──────────────────────────────────────────
    init {
        ToolRegistry.initialize(appContext)
    }

    // ── ElevenLabs Voice Service ───────────────────────────────
    private val elevenLabsVoice = ElevenLabsVoiceService

    // Model state from inference engine
    val modelState: StateFlow<ModelState> = NovaInference.state
    val loadProgress: StateFlow<Float> = NovaInference.loadProgress
    val errorMessage: StateFlow<String?> = NovaInference.errorMessage

    // Chat messages
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Streaming text for current generation
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    // Is currently generating
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Settings
    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    // Current mode from router
    val currentMode: StateFlow<NovaMode> = router.currentMode
    val isVoiceActive: StateFlow<Boolean> = router.isVoiceActive

    // ElevenLabs state
    val elevenLabsConnectionState = elevenLabsVoice.connectionState
    val elevenLabsAgentText = elevenLabsVoice.agentTranscription
    val elevenLabsUserText = elevenLabsVoice.userTranscription
    val isElevenLabsSpeaking = elevenLabsVoice.isAgentSpeaking

    private var generationJob: Job? = null

    init {
        loadSettings()
        loadMessagesFromDb()
        scanForModels()

        // On app open: generate daily summary for yesterday & run memory decay
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryManager.generateDailySummary()
                memoryManager.runDecay()
                Log.d(TAG, "Startup memory tasks complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error in startup memory tasks", e)
            }
        }

        // Listen for ElevenLabs agent messages and add to chat
        viewModelScope.launch {
            elevenLabsVoice.agentMessageEvent.collect { agentText ->
                if (agentText.isNotBlank()) {
                    messageDao.insertMessage(
                        MessageEntity(role = "assistant", content = agentText)
                    )
                }
            }
        }

        // Listen for ElevenLabs user transcriptions and add to chat
        viewModelScope.launch {
            elevenLabsVoice.userMessageEvent.collect { userText ->
                if (userText.isNotBlank()) {
                    messageDao.insertMessage(
                        MessageEntity(role = "user", content = userText)
                    )
                }
            }
        }

        // Set up ElevenLabs tool call handler
        elevenLabsVoice.setOnClientToolCall { toolName, toolCallId, parameters ->
            viewModelScope.launch {
                val tool = ToolRegistry.getTool(toolName)
                if (tool != null) {
                    try {
                        val result = tool.executor(appContext, parameters)
                        elevenLabsVoice.sendToolResult(
                            toolCallId = toolCallId,
                            result = result.message,
                            isError = !result.success
                        )
                    } catch (e: Exception) {
                        elevenLabsVoice.sendToolResult(
                            toolCallId = toolCallId,
                            result = "Tool execution failed: ${e.message}",
                            isError = true
                        )
                    }
                } else {
                    elevenLabsVoice.sendToolResult(
                        toolCallId = toolCallId,
                        result = "Unknown tool: $toolName",
                        isError = true
                    )
                }
            }
        }
    }

    private fun loadSettings() {
        _settings.update {
            it.copy(
                modelPath = prefs.getString("model_path", "") ?: "",
                temperature = prefs.getFloat("temperature", 0.7f),
                maxTokens = prefs.getInt("max_tokens", 256)
            )
        }
        NovaInference.temperature = _settings.value.temperature
        NovaInference.maxTokens = _settings.value.maxTokens
    }

    private fun loadMessagesFromDb() {
        viewModelScope.launch {
            messageDao.getAllMessages().collect { entities ->
                _messages.value = entities.map { entity ->
                    ChatMessage(
                        id = entity.id,
                        content = entity.content,
                        isUser = entity.role == "user",
                        timestamp = entity.timestamp
                    )
                }
            }
        }
    }

    fun scanForModels() {
        val models = NovaInference.findModelFiles()
        _settings.update { it.copy(availableModels = models) }
    }

    fun loadModel(path: String? = null) {
        val modelPath = path ?: _settings.value.modelPath.ifEmpty {
            _settings.value.availableModels.firstOrNull()?.absolutePath
        }
        if (modelPath == null) return

        viewModelScope.launch {
            val success = NovaInference.loadModel(modelPath)
            if (success) {
                _settings.update { it.copy(modelPath = modelPath) }
                prefs.edit().putString("model_path", modelPath).apply()
            }
        }
    }

    // ── Main message send — routes via NovaRouter ──────────────

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        val route = router.routeTextMessage(trimmed, appContext)
        Log.i(TAG, "Message routed to: $route")

        when (route) {
            NovaMode.TEXT_LOCAL -> sendLocalMessage(trimmed)
            NovaMode.TEXT_CLOUD -> sendCloudMessage(trimmed)
            NovaMode.AUTOMATION -> sendAutomationMessage(trimmed)
            NovaMode.VOICE_ELEVEN, NovaMode.VOICE_LOCAL -> sendLocalMessage(trimmed)
        }
    }

    private fun sendLocalMessage(text: String) {
        if (NovaInference.state.value != ModelState.READY) {
            if (CloudConfig.isOnline(appContext) && CloudConfig.hasOpenAiKey()) {
                sendCloudMessage(text)
                return
            }
            return
        }

        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = text))

            _isGenerating.value = true
            _streamingText.value = ""

            val history = buildConversationHistory()
            val memoryContext = try {
                memoryManager.injectContext(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error building memory context", e)
                ""
            }

            generationJob = NovaInference.generateStreaming(
                userMessage = text,
                history = history,
                memoryContext = memoryContext,
                scope = viewModelScope,
                onToken = { token -> _streamingText.update { it + token } },
                onComplete = {
                    val finalText = _streamingText.value.trim()
                    if (finalText.isNotEmpty()) {
                        viewModelScope.launch {
                            messageDao.insertMessage(
                                MessageEntity(role = "assistant", content = finalText)
                            )
                        }
                        viewModelScope.launch(Dispatchers.IO) {
                            try { memoryManager.processConversation(text, finalText) }
                            catch (e: Exception) { Log.e(TAG, "Error processing memories", e) }
                        }
                    }
                    _streamingText.value = ""
                    _isGenerating.value = false
                },
                onError = { error ->
                    viewModelScope.launch {
                        messageDao.insertMessage(
                            MessageEntity(role = "assistant", content = "Yo something broke: ${error.message}")
                        )
                    }
                    _streamingText.value = ""
                    _isGenerating.value = false
                }
            )
        }
    }

    private fun sendCloudMessage(text: String) {
        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = text))
            _isGenerating.value = true
            _streamingText.value = ""

            try {
                val response = OpenAIClient.chatCompletion(
                    userMessage = text, context = appContext
                )
                if (response != null) {
                    messageDao.insertMessage(MessageEntity(role = "assistant", content = response))
                    viewModelScope.launch(Dispatchers.IO) {
                        try { memoryManager.processConversation(text, response) }
                        catch (e: Exception) { Log.e(TAG, "Error processing memories", e) }
                    }
                } else {
                    _isGenerating.value = false
                    sendLocalMessage(text)
                    return@launch
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cloud message failed", e)
                _isGenerating.value = false
                sendLocalMessage(text)
                return@launch
            }
            _isGenerating.value = false
        }
    }

    private fun sendAutomationMessage(text: String) {
        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = text))
            _isGenerating.value = true
            _streamingText.value = "Running automation..."

            try {
                val toolDefs = ToolRegistry.getToolDefinitionsForLLM()
                CloudLLMService.processWithTools(
                    userMessage = text,
                    toolDefinitions = toolDefs,
                    context = appContext,
                    onToolCall = { toolName, params ->
                        val tool = ToolRegistry.getTool(toolName)
                        if (tool != null) {
                            tool.executor(appContext, params)
                        } else {
                            com.nova.companion.tools.ToolResult(false, "Unknown tool: $toolName")
                        }
                    },
                    onResponse = { response ->
                        viewModelScope.launch {
                            messageDao.insertMessage(
                                MessageEntity(role = "assistant", content = response)
                            )
                            _streamingText.value = ""
                            _isGenerating.value = false
                        }
                    },
                    onError = { error ->
                        viewModelScope.launch {
                            messageDao.insertMessage(
                                MessageEntity(role = "assistant", content = "Automation failed bro: $error")
                            )
                            _streamingText.value = ""
                            _isGenerating.value = false
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Automation error", e)
                messageDao.insertMessage(
                    MessageEntity(role = "assistant", content = "Couldn't run that automation: ${e.message}")
                )
                _streamingText.value = ""
                _isGenerating.value = false
            }
        }
    }

    // ── Voice mode controls ────────────────────────────────────

    fun toggleVoiceMode() {
        if (router.isVoiceActive.value) {
            stopVoiceMode()
        } else {
            if (CloudConfig.isOnline(appContext) && CloudConfig.hasElevenLabsKey()) {
                startElevenLabsVoice()
            } else {
                router.switchToVoiceMode(appContext)
            }
        }
    }

    private fun startElevenLabsVoice() {
        router.switchToVoiceMode(appContext)
        viewModelScope.launch {
            try {
                elevenLabsVoice.connect()
            } catch (e: Exception) {
                Log.e(TAG, "ElevenLabs connection failed", e)
                router.setMode(NovaMode.VOICE_LOCAL)
            }
        }
    }

    fun stopVoiceMode() {
        if (currentMode.value == NovaMode.VOICE_ELEVEN) {
            elevenLabsVoice.disconnect()
        }
        router.switchToTextMode()
    }

    // ── Existing controls ──────────────────────────────────────

    private suspend fun buildConversationHistory(): String {
        val recent = messageDao.getRecentMessages(6).reversed()
        return recent.joinToString("\n") { msg ->
            val role = if (msg.role == "user") "### User:" else "### Assistant:"
            "$role\n${msg.content}"
        }
    }

    fun cancelGeneration() {
        NovaInference.cancelGeneration()
        generationJob?.cancel()
        _isGenerating.value = false
        _streamingText.value = ""
    }

    fun updateTemperature(value: Float) {
        _settings.update { it.copy(temperature = value) }
        NovaInference.temperature = value
        prefs.edit().putFloat("temperature", value).apply()
    }

    fun updateMaxTokens(value: Int) {
        _settings.update { it.copy(maxTokens = value) }
        NovaInference.maxTokens = value
        prefs.edit().putInt("max_tokens", value).apply()
    }

    fun clearConversation() {
        viewModelScope.launch { messageDao.deleteAllMessages() }
    }

    fun unloadModel() {
        NovaInference.unloadModel()
    }

    override fun onCleared() {
        super.onCleared()
        cancelGeneration()
        elevenLabsVoice.release()
    }
}
