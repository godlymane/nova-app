package com.nova.companion.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nova.companion.data.NovaDatabase
import com.nova.companion.inference.InferenceRouter
import com.nova.companion.inference.NovaInference
import com.nova.companion.inference.NovaInference.ModelState
import com.nova.companion.memory.MemoryManager
import com.nova.companion.voice.ActiveVoiceManagerHolder
import com.nova.companion.voice.VoiceManager
import com.nova.companion.voice.VoiceManager.VoiceState
import com.nova.companion.voice.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val id: Long = System.nanoTime()
)

class NovaViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "NovaViewModel"
    }

    val modelState: StateFlow<ModelState> = NovaInference.state
    val loadProgress: StateFlow<Float> = NovaInference.loadProgress
    val errorMessage: StateFlow<String?> = NovaInference.errorMessage

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelFiles = MutableStateFlow<List<File>>(emptyList())
    val modelFiles: StateFlow<List<File>> = _modelFiles.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private var generationJob: Job? = null

    // ── Memory System ──────────────────────────────────────────
    private val db = NovaDatabase.getInstance(app)
    val memoryManager = MemoryManager(db)

    // ── Voice System ─────────────────────────────────────────────
    val voiceManager = VoiceManager()

    val voiceState: StateFlow<VoiceState> = voiceManager.voiceState
    val isVoiceModeEnabled: StateFlow<Boolean> = voiceManager.isVoiceModeEnabled
    val voiceModelsLoaded: StateFlow<Boolean> = voiceManager.voiceModelsLoaded
    val micAmplitude: StateFlow<Float> = voiceManager.micAmplitude
    val speakerAmplitude: StateFlow<Float> = voiceManager.speakerAmplitude
    val partialTranscription: StateFlow<String> = voiceManager.partialTranscription
    val isRecording: StateFlow<Boolean> = voiceManager.isRecording
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking
    val voiceError: StateFlow<String?> = voiceManager.voiceError

    private val _isVoiceLoading = MutableStateFlow(false)
    val isVoiceLoading: StateFlow<Boolean> = _isVoiceLoading.asStateFlow()

    // ── Wake word service state ─────────────────────────────────
    private val _isWakeWordServiceRunning = MutableStateFlow(false)
    val isWakeWordServiceRunning: StateFlow<Boolean> = _isWakeWordServiceRunning.asStateFlow()

    init {
        scanForModels()

        // Register the VoiceManager with the global holder so WakeWordService can
        // trigger it directly without a broadcast round-trip.
        ActiveVoiceManagerHolder.voiceManager = voiceManager

        // On app open: generate daily summary for yesterday & run decay
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryManager.generateDailySummary()
                memoryManager.runDecay()
                Log.d(TAG, "Startup memory tasks complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error in startup memory tasks", e)
            }
        }
    }

    fun scanForModels() {
        _modelFiles.value = NovaInference.findModelFiles()
    }

    /**
     * Try loading from a specific path, or auto-detect in Downloads.
     */
    fun loadModel(path: String? = null) {
        val modelPath = path ?: run {
            val files = NovaInference.findModelFiles()
            _modelFiles.value = files
            files.firstOrNull()?.absolutePath
        }

        if (modelPath == null) {
            _messages.value = _messages.value + ChatMessage(
                text = "No .gguf model file found. Copy Nanbeige4.1-3B.Q4_K_M.gguf to your Downloads folder and try again.",
                isUser = false
            )
            return
        }

        viewModelScope.launch {
            val success = NovaInference.loadModel(modelPath)
            if (success) {
                _messages.value = _messages.value + ChatMessage(
                    text = "Model loaded. Lets go bro whats good",
                    isUser = false
                )
            }
        }
    }

    /**
     * Send a user message through InferenceRouter (cloud → local fallback).
     *
     * InferenceRouter.route() handles:
     *  - Online: cloud LLM (OpenAI / Gemini / Claude) with streaming + Nova system prompt
     *  - Offline or cloud failure: local NovaInference (llama.cpp) fallback
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (modelState.value != ModelState.READY) return

        // Add user message
        _messages.value = _messages.value + ChatMessage(text = text, isUser = true)

        // Build conversation history from recent messages
        val history = buildConversationHistory()

        // Start streaming generation with memory context
        _streamingText.value = ""
        val streamingMsg = ChatMessage(text = "", isUser = false, isStreaming = true)
        _messages.value = _messages.value + streamingMsg

        val userText = text
        val appContext = getApplication<Application>().applicationContext

        viewModelScope.launch {
            // Inject memory context before generation (runs on IO)
            val memoryContext = try {
                memoryManager.injectContext(userText)
            } catch (e: Exception) {
                Log.e(TAG, "Error building memory context", e)
                ""
            }

            // ── Route through InferenceRouter (cloud → local) ────────
            generationJob = viewModelScope.launch {
                try {
                    InferenceRouter.route(
                        userMessage = userText,
                        context = appContext,
                        conversationHistory = history,
                        memoryContext = memoryContext,
                        onToken = { token ->
                            _streamingText.value += token
                            val current = _messages.value.toMutableList()
                            if (current.isNotEmpty() && current.last().isStreaming) {
                                current[current.lastIndex] = ChatMessage(
                                    text = _streamingText.value,
                                    isUser = false,
                                    isStreaming = true
                                )
                                _messages.value = current
                            }
                        },
                        onComplete = { _ ->
                            // Finalize the message (full text accumulated via onToken)
                            val finalText = _streamingText.value.trim()
                            val current = _messages.value.toMutableList()
                            if (current.isNotEmpty() && current.last().isStreaming) {
                                current[current.lastIndex] = ChatMessage(
                                    text = finalText,
                                    isUser = false,
                                    isStreaming = false
                                )
                                _messages.value = current
                            }
                            _streamingText.value = ""

                            // If voice mode: speak the response via Piper TTS
                            if (isVoiceModeEnabled.value && finalText.isNotBlank()) {
                                voiceManager.speakResponse(finalText, viewModelScope)
                            }

                            // Post-response: store conversation + extract memories
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    memoryManager.processConversation(userText, finalText)
                                    Log.d(TAG, "Memory processing complete for message")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing memories", e)
                                }
                            }
                        },
                        onError = { error ->
                            Log.e(TAG, "InferenceRouter error: ${error.message}", error)
                            val current = _messages.value.toMutableList()
                            if (current.isNotEmpty() && current.last().isStreaming) {
                                current[current.lastIndex] = ChatMessage(
                                    text = "Error: ${error.message}",
                                    isUser = false,
                                    isStreaming = false
                                )
                                _messages.value = current
                            }
                            _streamingText.value = ""
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in sendMessage coroutine", e)
                }
            }
        }
    }

    fun cancelGeneration() {
        NovaInference.cancelGeneration()
        generationJob?.cancel()
        generationJob = null
        // Finalize any in-progress streaming message
        val current = _messages.value.toMutableList()
        if (current.isNotEmpty() && current.last().isStreaming) {
            current[current.lastIndex] = ChatMessage(
                text = _streamingText.value.trim().ifBlank { "[cancelled]" },
                isUser = false,
                isStreaming = false
            )
            _messages.value = current
        }
        _streamingText.value = ""
    }

    /**
     * Build ### User: / ### Assistant: history from recent messages.
     * Keep last 6 messages to fit context window.
     */
    private fun buildConversationHistory(): String {
        val recent = _messages.value
            .filter { !it.isStreaming }
            .takeLast(6)

        if (recent.isEmpty()) return ""

        return buildString {
            for (msg in recent) {
                if (msg.isUser) {
                    append("### User:\n${msg.text}\n")
                } else {
                    append("### Assistant:\n${msg.text}\n")
                }
            }
        }
    }

    fun unloadModel() {
        NovaInference.unloadModel()
        _messages.value = emptyList()
    }

    // ── Voice controls ────────────────────────────────────────────

    /**
     * Toggle voice mode on/off.
     * Loads voice models (Whisper + Piper) on first enable.
     */
    fun toggleVoiceMode() {
        viewModelScope.launch {
            _isVoiceLoading.value = true
            try {
                voiceManager.toggleVoiceMode()
            } finally {
                _isVoiceLoading.value = false
            }
        }
    }

    /**
     * Start voice recording (mic button pressed).
     */
    fun startVoiceRecording() {
        if (modelState.value != ModelState.READY) return

        // If Nova is speaking, interrupt her
        if (voiceState.value == VoiceState.SPEAKING) {
            voiceManager.interruptSpeech()
        }

        voiceManager.startListening(viewModelScope)
    }

    /**
     * Stop recording and process the voice input.
     * Flow: stop recording → transcribe → send as message → LLM → TTS
     */
    fun stopVoiceRecording() {
        voiceManager.stopListeningAndTranscribe(
            scope = viewModelScope,
            onTranscribed = { text ->
                voiceManager.setThinking()
                sendMessage(text)
            }
        )
    }

    /**
     * Replay audio for a specific Nova message.
     */
    fun replayMessageAudio(message: ChatMessage) {
        if (!message.isUser && voiceManager.voiceModelsLoaded.value) {
            voiceManager.tts.speak(message.text, viewModelScope)
        }
    }

    /**
     * Interrupt Nova while she's speaking.
     */
    fun interruptSpeech() {
        voiceManager.interruptSpeech()
    }

    // ── Wake word service controls ────────────────────────────────

    /**
     * Start the wake word service as a foreground service.
     * Requires FOREGROUND_SERVICE and FOREGROUND_SERVICE_MICROPHONE permissions.
     * Should be called after microphone permission is granted.
     */
    fun startWakeWordService() {
        try {
            val context = getApplication<Application>().applicationContext
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _isWakeWordServiceRunning.value = true
            Log.i(TAG, "WakeWordService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WakeWordService", e)
        }
    }

    /**
     * Stop the wake word service.
     */
    fun stopWakeWordService() {
        try {
            val context = getApplication<Application>().applicationContext
            context.stopService(Intent(context, WakeWordService::class.java))
            _isWakeWordServiceRunning.value = false
            Log.i(TAG, "WakeWordService stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop WakeWordService", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        NovaInference.cancelGeneration()
        voiceManager.release()
        // Unregister VoiceManager from global holder
        ActiveVoiceManagerHolder.voiceManager = null
    }
}
