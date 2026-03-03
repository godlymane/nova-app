package com.nova.companion.ui.chat

import android.app.Application
import android.content.Context
import com.nova.companion.inference.HybridInferenceRouter
import com.nova.companion.inference.OfflineCapabilityManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.core.NovaMode
import com.nova.companion.core.NovaRouter
import com.nova.companion.data.MessageEntity
import com.nova.companion.data.NovaDatabase
import com.nova.companion.inference.NovaInference
import com.nova.companion.inference.NovaInference.ModelState
import com.nova.companion.memory.MemoryManager
import com.nova.companion.overlay.AuraOverlayService
import com.nova.companion.accessibility.ScreenContext
import com.nova.companion.brain.context.ContextInjector
import com.nova.companion.brain.emotion.NovaEmotionEngine
import com.nova.companion.data.entity.LearnedRoutine
import com.nova.companion.routines.RecordedStep
import com.nova.companion.routines.RoutineRecorder
import com.nova.companion.overlay.bubble.TaskBubbleService
import com.nova.companion.overlay.bubble.TaskProgressManager
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.ui.aura.AuraState
import com.nova.companion.voice.NovaVoicePipeline
import com.nova.companion.voice.WakeWordService
import com.nova.companion.widget.NovaWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val memoryManager = MemoryManager(database, appContext)

    // ── Router ────────────────────────────────────────────────
    val router = NovaRouter

    // ── Tool Registry + Emotion Engine ─────────────────────────
    init {
        ToolRegistry.initialize(appContext)
        NovaEmotionEngine.initialize(appContext)
        OfflineCapabilityManager.initialize(appContext)
    }

    // ── Voice Pipeline ────────────────────────────────────────
    private val voicePipeline = NovaVoicePipeline

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

    // Voice pipeline state
    val voicePipelineState = voicePipeline.state
    val voicePartialText = voicePipeline.partialText
    val voiceCurrentTool = voicePipeline.currentTool

    // ── Wake word state ───────────────────────────────────────
    private val _wakeWordTriggered = MutableStateFlow(false)
    val wakeWordTriggered: StateFlow<Boolean> = _wakeWordTriggered.asStateFlow()

    // ── Automation step status (shows current tool being executed) ────
    private val _automationStatus = MutableStateFlow<String?>(null)
    val automationStatus: StateFlow<String?> = _automationStatus.asStateFlow()

    // ── Teach-by-demonstration state ─────────────────────────
    val isTeaching: StateFlow<Boolean> = RoutineRecorder.isRecording
    private var pendingSteps: List<RecordedStep>? = null
    private var awaitingSmartFieldConfirm = false
    private val gson = Gson()

    // ── Network + capability state (via OfflineCapabilityManager) ─
    val isOnline: StateFlow<Boolean> = OfflineCapabilityManager.isOnline
    val capabilityLevel: StateFlow<OfflineCapabilityManager.CapabilityLevel> =
        OfflineCapabilityManager.capabilityLevel

    // ── Aura state ──────────────────────────────────────────
    // Derived from voice pipeline state for zero-delay visual feedback:
    //   IDLE → DORMANT, LISTENING → LISTENING, THINKING → THINKING, SPEAKING → SPEAKING
    //   Wake word flash → LISTENING, text generation → THINKING
    val auraState: StateFlow<AuraState> = combine(
        _wakeWordTriggered,
        _isGenerating,
        voicePipeline.state
    ) { wakeWord, generating, pipelineState ->
        when {
            // Voice pipeline active → map directly
            pipelineState == NovaVoicePipeline.PipelineState.SPEAKING -> AuraState.SPEAKING
            pipelineState == NovaVoicePipeline.PipelineState.LISTENING -> AuraState.LISTENING
            pipelineState == NovaVoicePipeline.PipelineState.THINKING -> AuraState.THINKING
            // Wake word just fired → LISTENING flare
            wakeWord -> AuraState.LISTENING
            // Text chat generating → THINKING
            generating -> AuraState.THINKING
            else -> AuraState.DORMANT
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AuraState.DORMANT
    )

    /** Audio amplitude from TTS for SPEAKING aura visualization */
    val speakingAmplitude: StateFlow<Float> = voicePipeline.speakingAmplitude

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

        // Listen for voice pipeline user messages and add to chat
        var lastVoiceUserMessage: String? = null
        viewModelScope.launch {
            voicePipeline.userMessageEvent.collect { userText ->
                if (userText.isNotBlank()) {
                    lastVoiceUserMessage = userText
                    messageDao.insertMessage(
                        MessageEntity(role = "user", content = userText)
                    )
                }
            }
        }

        // Listen for voice pipeline assistant responses and add to chat + extract memories
        viewModelScope.launch {
            voicePipeline.assistantMessageEvent.collect { assistantText ->
                if (assistantText.isNotBlank()) {
                    messageDao.insertMessage(
                        MessageEntity(role = "assistant", content = assistantText)
                    )
                    // Feed voice conversations into long-term memory
                    lastVoiceUserMessage?.let { userMsg ->
                        viewModelScope.launch(Dispatchers.IO) {
                            try { memoryManager.processConversation(userMsg, assistantText) }
                            catch (e: Exception) { Log.e(TAG, "Voice memory extraction failed", e) }
                        }
                        lastVoiceUserMessage = null
                    }
                    // Update home screen widget with latest response
                    viewModelScope.launch(Dispatchers.IO) {
                        NovaWidget.saveState(appContext, response = assistantText)
                        NovaWidget.updateWidget(appContext)
                    }
                }
            }
        }

        // Listen for wake word events from WakeWordService
        viewModelScope.launch {
            WakeWordService.wakeWordEvent.collect {
                onWakeWordDetected()
            }
        }

        // Forward aura state to overlay service + widget
        viewModelScope.launch {
            auraState.collect { state ->
                AuraOverlayService.updateAuraState(state)
                viewModelScope.launch(Dispatchers.IO) {
                    NovaWidget.saveState(appContext, auraState = state.name)
                    NovaWidget.updateWidget(appContext)
                }
            }
        }

        // Forward TTS amplitude to overlay for SPEAKING visualization
        viewModelScope.launch {
            speakingAmplitude.collect { amp ->
                AuraOverlayService.updateAmplitude(amp)
            }
        }

        // Wire overlay interactive controls
        AuraOverlayService.onTapToListen = {
            viewModelScope.launch { onWakeWordDetected() }
        }
        AuraOverlayService.onDoubleTapToStop = {
            viewModelScope.launch {
                voicePipeline.stop()
                generationJob?.cancel()
                _isGenerating.value = false
            }
        }

        // Resume wake word listening when voice pipeline returns to IDLE
        viewModelScope.launch {
            voicePipeline.state.collect { pipelineState ->
                if (pipelineState == NovaVoicePipeline.PipelineState.IDLE) {
                    // Pipeline handles resume internally, but also switch router back
                    if (router.isVoiceActive.value) {
                        router.switchToTextMode()
                    }
                }
            }
        }
    }

    /**
     * Called by WakeWordService (or wherever wake word detection is integrated)
     * when the wake word is detected. Triggers:
     *  1. Aura SURGE for 2 seconds.
     *  2. Medium haptic buzz (100 ms).
     *  3. Automatic ElevenLabs voice session start.
     */
    fun onWakeWordDetected() {
        // WakeWordService already vibrates — no duplicate here

        viewModelScope.launch {
            _wakeWordTriggered.value = true

            // Switch to voice mode IMMEDIATELY so user sees the voice overlay
            router.switchToVoiceMode(appContext)

            // Start the voice pipeline — OfflineCapabilityManager determines the path
            val online = OfflineCapabilityManager.isOnline.value
            val capability = OfflineCapabilityManager.capabilityLevel.value
            Log.i(TAG, ">>> Wake word received! online=$online, capability=$capability")

            // Brief delay for Porcupine to release its AudioRecord.
            delay(200)

            if (online) {
                Log.i(TAG, ">>> Starting online voice pipeline")
                voicePipeline.start(appContext, viewModelScope)
            } else if (OfflineCapabilityManager.isVoiceAvailable()) {
                Log.i(TAG, ">>> Offline mode — starting offline voice pipeline (local model)")
                voicePipeline.startOffline(appContext, viewModelScope)
            } else {
                Log.w(TAG, ">>> Voice not available — no internet and no local model")
                // Notify OfflineCapabilityManager state and switch back
                router.switchToTextMode()
            }

            // Hold SURGE for 2 seconds, then clear the wake word flag
            delay(2_000)
            _wakeWordTriggered.value = false
        }
    }

    private fun triggerWakeWordVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(
                    VibrationEffect.createOneShot(100L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(100L, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    // ── Settings ────────────────────────────────────────────

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
                OfflineCapabilityManager.updateLocalModelState()
            }
        }
    }

    // ── Main message send — routes via NovaRouter ──────────────

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        // ── Teach-by-demonstration intercept ────────────────────
        // If we're waiting for smart field confirmation
        if (awaitingSmartFieldConfirm && pendingSteps != null) {
            handleSmartFieldResponse(trimmed)
            return
        }
        // Check for teach stop intent while recording
        if (RoutineRecorder.isRecording.value && router.isTeachStopIntent(trimmed)) {
            handleTeachStop(trimmed)
            return
        }
        // Check for teach start intent
        if (router.isTeachIntent(trimmed)) {
            handleTeachStart(trimmed)
            return
        }

        // If voice pipeline is active and user TYPED a message, switch back to text mode.
        // The typed message should go through normal text inference, not the voice pipeline.
        if (router.isVoiceActive.value) {
            Log.i(TAG, "User typed during voice session — switching to text mode")
            voicePipeline.stop()
            router.switchToTextMode()
        }

        val route = router.routeTextMessage(trimmed, appContext)
        Log.i(TAG, "Message routed to: $route")

        when (route) {
            NovaMode.VOICE_ELEVEN, NovaMode.VOICE_LOCAL -> {
                // Voice pipeline handles its own inference + TTS.
                // Just log — the pipeline emits user/assistant messages via SharedFlows
                // which are collected in init{} and saved to DB automatically.
                Log.d(TAG, "Voice mode route — skipping text inference, voice pipeline handles response")
            }
            NovaMode.LIVE_DATA -> sendLiveDataMessage(trimmed)
            NovaMode.AUTOMATION -> sendAutomationMessage(trimmed)
            else -> sendHybridMessage(trimmed)
        }
    }

    // ── Teach-by-demonstration handlers ───────────────────────

    private fun handleTeachStart(userMsg: String) {
        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = userMsg))

            // Extract a routine name from the message
            val name = extractRoutineName(userMsg)
            RoutineRecorder.startRecording(name)

            // Show floating bubble so user sees status when Nova is minimized
            TaskProgressManager.startTask("teach_$name", "Recording: $name")
            TaskProgressManager.updateProgress("teach_$name", 0, "Watching your actions...")
            try { TaskBubbleService.start(appContext) } catch (e: Exception) {
                Log.w(TAG, "Could not start bubble overlay", e)
            }

            messageDao.insertMessage(
                MessageEntity(
                    role = "assistant",
                    content = "Alright, I'm watching. Go ahead and do your thing — I'll record every step. Tell me \"done\" when you're finished."
                )
            )
            Log.i(TAG, "Teach mode started: $name")
        }
    }

    private fun handleTeachStop(userMsg: String) {
        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = userMsg))

            val steps = RoutineRecorder.stopRecording()
            val taskId = "teach_${RoutineRecorder.getRoutineName()}"

            if (steps.isEmpty()) {
                TaskProgressManager.failTask(taskId, "No actions captured")
                try { TaskBubbleService.stop(appContext) } catch (_: Exception) {}
                messageDao.insertMessage(
                    MessageEntity(
                        role = "assistant",
                        content = "Hmm I didn't catch any actions. Make sure accessibility is on and try again."
                    )
                )
                return@launch
            }

            pendingSteps = steps

            // Update bubble: recording done
            TaskProgressManager.completeTask(taskId, "Captured ${steps.size} steps")

            // Summarize what we recorded
            val summary = buildStepSummary(steps)
            val hasTextInputs = steps.any { it.action == "type" && it.typedText.isNotBlank() }

            val response = buildString {
                append("Got it, I recorded ${steps.size} steps:\n\n")
                append(summary)
                if (hasTextInputs) {
                    append("\n\nI noticed you typed some text. Should I write fresh content each time I replay this (like a new caption), or use the exact same text? Say \"fresh\" or \"same\".")
                    awaitingSmartFieldConfirm = true
                } else {
                    append("\n\nSaving this routine as \"${RoutineRecorder.getRoutineName()}\". You can run it anytime by asking me to do it.")
                    saveRoutine(steps, smartFields = false)
                }
            }

            messageDao.insertMessage(MessageEntity(role = "assistant", content = response))
        }
    }

    private fun handleSmartFieldResponse(userMsg: String) {
        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = userMsg))

            val lower = userMsg.lowercase()
            val makeSmart = lower.contains("fresh") || lower.contains("new") ||
                    lower.contains("generate") || lower.contains("different")

            val steps = pendingSteps ?: run {
                awaitingSmartFieldConfirm = false
                return@launch
            }

            saveRoutine(steps, smartFields = makeSmart)

            val msg = if (makeSmart) {
                "Nice, I'll generate fresh text each time. Routine \"${RoutineRecorder.getRoutineName()}\" saved. Just ask me to do it whenever."
            } else {
                "Cool, I'll use the same text. Routine \"${RoutineRecorder.getRoutineName()}\" saved. Just ask me to do it whenever."
            }

            messageDao.insertMessage(MessageEntity(role = "assistant", content = msg))
            pendingSteps = null
            awaitingSmartFieldConfirm = false
        }
    }

    private fun saveRoutine(steps: List<RecordedStep>, smartFields: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val finalSteps = if (smartFields) {
                    steps.map { step ->
                        if (step.action == "type" && step.typedText.isNotBlank()) {
                            step.copy(
                                isSmartField = true,
                                smartFieldHint = "Write similar content to: ${step.typedText.take(50)}"
                            )
                        } else step
                    }
                } else steps

                val routineName = RoutineRecorder.getRoutineName()
                val primaryApp = finalSteps.firstOrNull { it.packageName.isNotBlank() }?.packageName ?: ""
                val triggerPhrases = generateTriggerPhrases(routineName)

                val routine = LearnedRoutine(
                    name = routineName,
                    triggerPhrases = gson.toJson(triggerPhrases),
                    steps = gson.toJson(finalSteps),
                    appPackage = primaryApp
                )

                database.learnedRoutineDao().insert(routine)
                Log.i(TAG, "Saved learned routine: $routineName (${finalSteps.size} steps)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save routine", e)
            }
        }
    }

    private fun extractRoutineName(message: String): String {
        val lower = message.lowercase()
        // Try to extract what comes after "how to"
        val howToMatch = Regex("how to (.+?)(?:\\.|$)").find(lower)
        if (howToMatch != null) return howToMatch.groupValues[1].trim()

        // Try to extract after "teach you to"
        val teachMatch = Regex("teach you (?:to |how to )?(.+?)(?:\\.|$)").find(lower)
        if (teachMatch != null) return teachMatch.groupValues[1].trim()

        // Try after "show you how to"
        val showMatch = Regex("show you how to (.+?)(?:\\.|$)").find(lower)
        if (showMatch != null) return showMatch.groupValues[1].trim()

        return "custom routine"
    }

    private fun generateTriggerPhrases(name: String): List<String> {
        val phrases = mutableListOf(name)
        // Add variations: "do X", "run X", just "X"
        if (!name.startsWith("do ")) phrases.add("do $name")
        if (!name.startsWith("run ")) phrases.add("run $name")
        return phrases
    }

    private fun buildStepSummary(steps: List<RecordedStep>): String {
        return steps.mapIndexed { i, step ->
            val desc = when (step.action) {
                "open_app" -> "Open ${step.packageName.substringAfterLast(".")}"
                "tap" -> "Tap on \"${(step.targetText.ifBlank { step.targetDesc }).take(30)}\""
                "type" -> "Type \"${step.typedText.take(30)}${if (step.typedText.length > 30) "..." else ""}\""
                "scroll" -> "Scroll ${step.scrollDirection}"
                "back" -> "Press back"
                "wait" -> "Wait"
                else -> step.action
            }
            "${i + 1}. $desc"
        }.joinToString("\n")
    }

    /**
     * Unified message handler — routes through HybridInferenceRouter which
     * classifies task complexity and picks local SLM vs cloud LLM automatically.
     * Also fires local gap-fill for complex tasks (quick ack while cloud processes).
     */
    private fun sendHybridMessage(text: String) {
        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = text))
            _isGenerating.value = true
            _streamingText.value = ""

            val history = buildCloudHistory()
            val memoryContext = try {
                ContextInjector.buildInjectedContext(text, memoryManager, appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Context injection failed", e)
                ""
            }

            HybridInferenceRouter.route(
                userMessage = text,
                context = appContext,
                conversationHistory = history,
                memoryContext = memoryContext,
                scope = viewModelScope,
                onToken = { token -> _streamingText.update { it + token } },
                onComplete = { fullResponse ->
                    val finalText = fullResponse.ifBlank { _streamingText.value }.trim()
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
                        viewModelScope.launch(Dispatchers.IO) {
                            try { NovaEmotionEngine.updateFromConversation(text, finalText) }
                            catch (e: Exception) { Log.e(TAG, "Emotion update failed", e) }
                        }
                        viewModelScope.launch(Dispatchers.IO) {
                            NovaWidget.saveState(appContext, response = finalText)
                            NovaWidget.updateWidget(appContext)
                        }
                    }
                    _streamingText.value = ""
                    _isGenerating.value = false
                },
                onError = { error ->
                    viewModelScope.launch {
                        messageDao.insertMessage(
                            MessageEntity(
                                role = "assistant",
                                content = "Yo something broke: ${error.message}"
                            )
                        )
                    }
                    _streamingText.value = ""
                    _isGenerating.value = false
                },
                onGapFill = { ack ->
                    // Show quick local acknowledgment while cloud processes
                    _streamingText.value = ack
                }
            )
        }
    }

    /** @deprecated Use sendHybridMessage — kept only as fallback redirect for sendLiveDataMessage */
    private fun sendCloudMessage(text: String) = sendHybridMessage(text)

    private fun sendLiveDataMessage(text: String) {
        viewModelScope.launch {
            messageDao.insertMessage(MessageEntity(role = "user", content = text))
            _isGenerating.value = true
            _streamingText.value = ""

            try {
                // Phase 7: Try NegotiationEngine first (free APIs > web scraping > paid)
                val negotiationResult = com.nova.companion.negotiation.NegotiationEngine.resolve(
                    userMessage = text,
                    context = appContext,
                    onProgress = { status ->
                        _streamingText.value = status
                    }
                )

                if (negotiationResult != null) {
                    Log.d(TAG, "NegotiationEngine resolved live data query")
                    messageDao.insertMessage(MessageEntity(role = "assistant", content = negotiationResult))
                    _streamingText.value = ""
                    _isGenerating.value = false
                    // Feed into memory for long-term learning
                    viewModelScope.launch(Dispatchers.IO) {
                        try { memoryManager.processConversation(text, negotiationResult) }
                        catch (e: Exception) { Log.e(TAG, "Memory extraction failed", e) }
                    }
                    return@launch
                }

                // Fallback: OpenAI web search
                Log.d(TAG, "NegotiationEngine returned null — falling back to OpenAI web search")
                val response = OpenAIClient.webSearch(
                    userMessage = text, context = appContext
                )
                if (response != null) {
                    messageDao.insertMessage(MessageEntity(role = "assistant", content = response))
                } else {
                    // web search failed — fall back to regular cloud
                    _isGenerating.value = false
                    sendCloudMessage(text)
                    return@launch
                }
            } catch (e: Exception) {
                Log.e(TAG, "Live data query failed", e)
                _isGenerating.value = false
                sendCloudMessage(text)
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

            // Show floating bubble so user sees progress when Nova is minimized
            val taskId = "auto_${System.currentTimeMillis()}"
            TaskProgressManager.startTask(taskId, "Automation")
            TaskProgressManager.updateProgress(taskId, 0, "Planning...")
            try { TaskBubbleService.start(appContext) } catch (e: Exception) {
                Log.w(TAG, "Could not start bubble overlay", e)
            }
            var toolCallCount = 0

            try {
                val history = buildCloudHistory()

                // Inject screen context so the LLM knows what app/screen the user is on
                val screenSummary = ScreenContext.getSummary()
                val enrichedMessage = if (screenSummary.isNotBlank()) {
                    "$text\n\n[Screen context: $screenSummary]"
                } else text

                // HybridInferenceRouter handles cloud vs local tool execution automatically
                HybridInferenceRouter.routeAutomation(
                    userMessage = enrichedMessage,
                    context = appContext,
                    conversationHistory = history,
                    onToolCall = { toolName ->
                        toolCallCount++
                        _automationStatus.value = toolName
                        TaskProgressManager.updateProgress(
                            taskId,
                            (toolCallCount * 15).coerceAtMost(90),
                            "Running: $toolName"
                        )
                    },
                    onResponse = { response ->
                        viewModelScope.launch {
                            messageDao.insertMessage(
                                MessageEntity(role = "assistant", content = response)
                            )
                            TaskProgressManager.completeTask(taskId, "Done")
                            _automationStatus.value = null
                            _streamingText.value = ""
                            _isGenerating.value = false
                        }
                    },
                    onError = { error ->
                        viewModelScope.launch {
                            messageDao.insertMessage(
                                MessageEntity(
                                    role = "assistant",
                                    content = "Automation failed bro: $error"
                                )
                            )
                            TaskProgressManager.failTask(taskId, "Failed: $error")
                            _automationStatus.value = null
                            _streamingText.value = ""
                            _isGenerating.value = false
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Automation error", e)
                TaskProgressManager.failTask(taskId, "Error: ${e.message}")
                messageDao.insertMessage(
                    MessageEntity(
                        role = "assistant",
                        content = "Couldn't run that automation: ${e.message}"
                    )
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
            if (OfflineCapabilityManager.isOnline.value) {
                startVoicePipeline()
            } else if (OfflineCapabilityManager.isVoiceAvailable()) {
                // Offline but local model loaded — start offline voice
                router.switchToVoiceMode(appContext)
                viewModelScope.launch {
                    delay(200)
                    voicePipeline.startOffline(appContext, viewModelScope)
                }
            } else {
                router.switchToVoiceMode(appContext)
            }
        }
    }

    private fun startVoicePipeline() {
        router.switchToVoiceMode(appContext)
        viewModelScope.launch {
            delay(200) // Brief delay for Porcupine to release AudioRecord
            voicePipeline.start(appContext, viewModelScope)
        }
    }

    fun stopVoiceMode() {
        voicePipeline.stop()
        router.switchToTextMode()
    }

    // ── Conversation helpers ─────────────────────────────────

    /** Build history as role/content pairs — used by hybrid router and cloud APIs */
    private suspend fun buildCloudHistory(): List<Pair<String, String>> {
        return messageDao.getRecentMessages(10).reversed().map { msg ->
            Pair(msg.role, msg.content)
        }
    }

    // ── Generation controls ──────────────────────────────────

    fun cancelGeneration() {
        NovaInference.cancelGeneration()
        generationJob?.cancel()
        _isGenerating.value = false
        _streamingText.value = ""
    }

    // ── Settings controls ────────────────────────────────────

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
        OfflineCapabilityManager.updateLocalModelState()
    }

    // ── Lifecycle ────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        cancelGeneration()
        voicePipeline.release()
        OfflineCapabilityManager.shutdown()
    }
}
