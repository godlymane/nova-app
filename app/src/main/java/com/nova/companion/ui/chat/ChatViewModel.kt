package com.nova.companion.ui.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nova.companion.brain.NovaPersonality
import com.nova.companion.brain.context.ContextEngine
import com.nova.companion.brain.memory.BrainMemoryManager
import com.nova.companion.brain.proactive.ProactiveEngine
import com.nova.companion.brain.proactive.ProactiveMessage
import com.nova.companion.data.local.ConversationDao
import com.nova.companion.data.local.Message
import com.nova.companion.data.local.NovaDatabase
import com.nova.companion.network.GeminiApiService
import com.nova.companion.network.RetrofitClient
import com.nova.companion.voice.ElevenLabsVoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val PROACTIVE_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    // ─────────────────────────────────────────────
    // Dependencies
    // ─────────────────────────────────────────────

    private val context: Context get() = getApplication<Application>().applicationContext
    private val db = NovaDatabase.getDatabase(context)
    private val conversationDao: ConversationDao = db.conversationDao()
    private val apiService: GeminiApiService = RetrofitClient.geminiApiService

    // Brain components
    private val brainMemory = BrainMemoryManager(context)
    private val proactiveEngine = ProactiveEngine(context, brainMemory)

    // ─────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────

    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> = _messages

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _proactiveMessage = MutableLiveData<ProactiveMessage?>(null)
    val proactiveMessage: LiveData<ProactiveMessage?> = _proactiveMessage

    private var proactiveCheckJob: Job? = null

    // ─────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────

    init {
        loadMessages()
        startProactiveEngine()
        brainMemory.cleanup() // Clean old memories on start
    }

    // ─────────────────────────────────────────────
    // Message handling
    // ─────────────────────────────────────────────

    fun sendMessage(userMessage: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val snapshot = ContextEngine.currentContext.value

            // Record user message in brain memory
            brainMemory.recordMessage("user", userMessage, "text", snapshot)

            val userMsg = Message(
                content = userMessage,
                role = "user",
                timestamp = System.currentTimeMillis()
            )

            saveMessage(userMsg)
            updateMessagesList()

            try {
                val historicalMessages = withContext(Dispatchers.IO) {
                    conversationDao.getRecentMessages(20)
                }

                // Build context-aware prompt
                val contextAddendum = NovaPersonality.buildQuickContext(snapshot)

                val response = withContext(Dispatchers.IO) {
                    apiService.sendMessage(
                        messages = historicalMessages,
                        systemContext = contextAddendum
                    )
                }

                val assistantMsg = Message(
                    content = response,
                    role = "assistant",
                    timestamp = System.currentTimeMillis()
                )

                saveMessage(assistantMsg)

                // Record assistant response in brain memory
                brainMemory.recordMessage("assistant", response, "text", snapshot)

                updateMessagesList()

            } catch (e: HttpException) {
                Log.e(TAG, "HTTP error sending message", e)
                _error.value = "Network error: ${e.code()}"
            } catch (e: IOException) {
                Log.e(TAG, "IO error sending message", e)
                _error.value = "Connection error. Please check your network."
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                _error.value = "Something went wrong. Please try again."
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Called when a voice session starts.
     * Sends brain context to ElevenLabs agent.
     */
    fun onVoiceSessionStart(voiceService: ElevenLabsVoiceService) {
        viewModelScope.launch {
            try {
                val snapshot = ContextEngine.currentContext.value
                ContextEngine.forceCollect(context) // Fresh context for voice

                val contextualPrompt = NovaPersonality.buildContextualAddendum(
                    snapshot = snapshot,
                    memoryManager = brainMemory
                )

                voiceService.sendContextualUpdate(contextualPrompt)
                Log.d(TAG, "Brain context sent to ElevenLabs (${contextualPrompt.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send brain context to voice", e)
            }
        }
    }

    /**
     * Record a voice conversation turn.
     */
    fun recordVoiceTurn(role: String, content: String) {
        val snapshot = ContextEngine.currentContext.value
        brainMemory.recordMessage(role, content, "voice", snapshot)
    }

    // ─────────────────────────────────────────────
    // Proactive engine
    // ─────────────────────────────────────────────

    private fun startProactiveEngine() {
        proactiveCheckJob = viewModelScope.launch {
            // Observe context changes
            ContextEngine.currentContext.collectLatest { snapshot ->
                while (isActive) {
                    val message = proactiveEngine.evaluate(snapshot)
                    if (message != null) {
                        _proactiveMessage.postValue(message)
                    }
                    delay(PROACTIVE_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    fun clearProactiveMessage() {
        _proactiveMessage.value = null
    }

    // ─────────────────────────────────────────────
    // DB helpers
    // ─────────────────────────────────────────────

    private fun loadMessages() {
        viewModelScope.launch {
            updateMessagesList()
        }
    }

    private suspend fun saveMessage(message: Message) {
        withContext(Dispatchers.IO) {
            conversationDao.insertMessage(message)
        }
    }

    private suspend fun updateMessagesList() {
        val msgs = withContext(Dispatchers.IO) {
            conversationDao.getRecentMessages(100)
        }
        _messages.postValue(msgs)
    }

    // ─────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        proactiveCheckJob?.cancel()
    }
}
