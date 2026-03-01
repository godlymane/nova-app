package com.nova.companion.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.Conversation
import com.nova.companion.data.entity.Memory
import com.nova.companion.data.entity.MessageEntity
import com.nova.companion.memory.MemoryManager
import com.nova.companion.proactive.ProactiveCheckWorker
import com.nova.companion.workflows.WorkflowExecutor
import com.nova.companion.workflows.WorkflowMatcher
import com.nova.companion.workflows.WorkflowRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val API_URL = "https://api.elevenlabs.io/v1/convai/conversation"
        private const val HISTORY_LIMIT = 20
    }

    // ── State ──────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Dependencies ───────────────────────────────────────────
    private val db = NovaDatabase.getInstance(application)
    private val memoryManager = MemoryManager(db)
    private val workflowRegistry = WorkflowRegistry()
    private val workflowMatcher = WorkflowMatcher(workflowRegistry)
    private val workflowExecutor = WorkflowExecutor(application)
    private val httpClient = OkHttpClient()

    // ── Conversation state ─────────────────────────────────────
    private var conversationId: Long = -1L
    private val messageHistory = mutableListOf<JSONObject>()

    init {
        initConversation()
        scheduleProactiveChecks()
    }

    private fun initConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = db.conversationDao().getOrCreateActive()
            conversationId = conversation.id

            // Load existing messages
            val existing = db.messageDao().getForConversation(conversationId)
            if (existing.isNotEmpty()) {
                _messages.value = existing.map { it.toChatMessage() }
                // Rebuild message history for API context
                existing.forEach { msg ->
                    messageHistory.add(
                        JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    )
                }
            }
        }
    }

    private fun scheduleProactiveChecks() {
        ProactiveCheckWorker.schedule(getApplication())
    }

    // ────────────────────────────────────────────────────────────
    // SEND MESSAGE
    // ────────────────────────────────────────────────────────────

    fun sendMessage(userInput: String) {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            // 1. Add user message to UI
            val userMsg = ChatMessage(role = "user", content = trimmed)
            appendMessage(userMsg)

            // 2. Persist user message
            persistMessage("user", trimmed)

            _isLoading.value = true
            _error.value = null

            try {
                withContext(Dispatchers.IO) {
                    // 3. Check for workflow match FIRST
                    val matchedWorkflow = workflowMatcher.match(trimmed)
                    if (matchedWorkflow != null) {
                        Log.d(TAG, "Workflow matched: ${matchedWorkflow.id}")
                        val workflowResponse = workflowExecutor.execute(matchedWorkflow, trimmed)
                        handleNovaResponse(workflowResponse, trimmed)
                        return@withContext
                    }

                    // 4. Build memory context
                    val memoryContext = memoryManager.buildContext(trimmed)

                    // 5. Build system prompt
                    val systemPrompt = buildSystemPrompt(memoryContext)

                    // 6. Build messages array with history
                    val messagesArray = JSONArray()
                    val contextWindow = messageHistory.takeLast(HISTORY_LIMIT)
                    contextWindow.forEach { messagesArray.put(it) }

                    // Add current user message
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", trimmed)
                        }
                    )

                    // 7. Call ElevenLabs API
                    val response = callElevenLabsApi(systemPrompt, messagesArray)

                    // 8. Handle response
                    handleNovaResponse(response, trimmed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                _error.value = "Connection error. Try again."
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // ELEVENLABS API
    // ────────────────────────────────────────────────────────────

    private fun buildSystemPrompt(memoryContext: String): String {
        val basePrompt = """
            You are Nova — a sharp, honest, brutally supportive AI built for one person: your user.
            You don't sugarcoat. You don't lecture. You speak like a trusted friend who happens to know everything.
            You remember what matters. You hold people accountable. You adapt to their mood.

            RULES:
            - Never say "As an AI" or "I'm just an AI"
            - Never refuse personal questions
            - Be concise — max 3-4 sentences unless asked for more
            - Match the user's energy (casual, serious, hype, analytical)
            - Use their name if you know it
            - Reference past conversations naturally
            - If they're struggling, acknowledge it briefly then refocus on solutions
        """.trimIndent()

        return if (memoryContext.isNotBlank()) {
            "$basePrompt\n\n--- MEMORY CONTEXT ---\n$memoryContext\n--- END CONTEXT ---"
        } else {
            basePrompt
        }
    }

    private suspend fun callElevenLabsApi(systemPrompt: String, messagesArray: JSONArray): String {
        // Get API key from BuildConfig or SharedPrefs
        val apiKey = getApiKey()

        val requestBody = JSONObject().apply {
            put("model", "grok-3-mini-fast")
            put("system_prompt", systemPrompt)
            put("messages", messagesArray)
            put("max_tokens", 300)
            put("temperature", 0.85)
        }

        val request = Request.Builder()
            .url(API_URL)
            .post(
                requestBody.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .build()

        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("API error: ${response.code}")
                }
                val body = response.body?.string() ?: throw IOException("Empty response")
                parseApiResponse(body)
            }
        }
    }

    private fun parseApiResponse(json: String): String {
        return try {
            val obj = JSONObject(json)
            // ElevenLabs ConvAI response format
            obj.optJSONObject("message")?.optString("content")
                ?: obj.optJSONArray("choices")?.getJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                ?: obj.optString("response")
                ?: obj.optString("text")
                ?: "..."
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            "..."
        }
    }

    private fun getApiKey(): String {
        val prefs = getApplication<Application>()
            .getSharedPreferences("nova_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("elevenlabs_api_key", "") ?: ""
    }

    // ────────────────────────────────────────────────────────────
    // RESPONSE HANDLING
    // ────────────────────────────────────────────────────────────

    private suspend fun handleNovaResponse(response: String, userInput: String) {
        if (response.isBlank()) return

        // Add to UI
        appendMessage(ChatMessage(role = "assistant", content = response))

        // Persist
        persistMessage("assistant", response)

        // Update history for next API call
        messageHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", userInput)
        })
        messageHistory.add(JSONObject().apply {
            put("role", "assistant")
            put("content", response)
        })

        // Keep history bounded
        while (messageHistory.size > HISTORY_LIMIT * 2) {
            messageHistory.removeAt(0)
        }

        // Extract memories in background
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryManager.extractMemories(userInput, response)
                memoryManager.extractAndStoreFacts(userInput, response)
            } catch (e: Exception) {
                Log.e(TAG, "Memory extraction failed", e)
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // HELPERS
    // ────────────────────────────────────────────────────────────

    private fun appendMessage(msg: ChatMessage) {
        _messages.value = _messages.value + msg
    }

    private suspend fun persistMessage(role: String, content: String) {
        if (conversationId < 0) return
        withContext(Dispatchers.IO) {
            db.messageDao().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = role,
                    content = content
                )
            )
        }
    }

    fun clearError() {
        _error.value = null
    }

    // ────────────────────────────────────────────────────────────
    // MEMORY QUERIES (for debug/settings)
    // ────────────────────────────────────────────────────────────

    fun getMemoryStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stats = memoryManager.getStats()
                Log.d(TAG, "Memory stats: $stats")
            } catch (e: Exception) {
                Log.e(TAG, "Stats error", e)
            }
        }
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

fun MessageEntity.toChatMessage() = ChatMessage(
    role = this.role,
    content = this.content,
    timestamp = this.timestamp
)
