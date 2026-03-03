package com.nova.companion.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nova.companion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * DEPRECATED: ElevenLabs Conversational AI WebSocket implementation.
 *
 * This service is currently NOT used by any active code path.
 * All voice interactions are routed through [NovaVoicePipeline] which uses:
 *   Wake word → AudioRecord → Whisper STT → CloudLLM (with tools) → ElevenLabsTTS (REST)
 *
 * The WebSocket listener and audio pipeline below are fully implemented but
 * untested in production. Kept as scaffolding for future real-time conversational
 * voice mode (single WebSocket: Mic → STT → LLM → TTS → Speaker).
 *
 * TODO: Integrate with NovaVoicePipeline as an alternative high-speed voice path.
 *
 * Original capabilities (implemented but inactive):
 * - WebSocket connection to ElevenLabs Conversational AI endpoint
 * - Microphone capture with 16kHz 16-bit mono PCM
 * - Audio streaming to WebSocket as base64-encoded chunks
 * - Audio playback from WebSocket via AudioTrack
 * - Transcription updates (user & agent)
 * - Tool call handling and results
 * - Connection state management with auto-reconnect
 * - Pre-buffering between wake word and WebSocket connect
 */
object ElevenLabsVoiceService {

    private const val TAG = "ElevenLabsVoice"
    private const val WEBSOCKET_URL = "wss://api.elevenlabs.io/v1/convai/conversation"

    // Audio configuration
    private const val SAMPLE_RATE = 16000
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO

    // Audio buffer sizes
    private const val AUDIO_CHUNK_SIZE = 1024 // Samples per chunk
    private const val PLAYBACK_BUFFER_SIZE = 4096

    // Connection timing
    private const val HANDSHAKE_TIMEOUT_MS = 5000L
    private const val PING_INTERVAL_MS = 20000L

    // Reconnect configuration
    private const val MAX_RECONNECT_ATTEMPTS = 3
    private const val RECONNECT_DELAY_MS = 2000L

    // ── Connection state machine ───────────────────────────────────────
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    // ── Mutable state flows ────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _userTranscription = MutableStateFlow("")
    val userTranscription: StateFlow<String> = _userTranscription.asStateFlow()

    private val _agentTranscription = MutableStateFlow("")
    val agentTranscription: StateFlow<String> = _agentTranscription.asStateFlow()

    private val _isAgentSpeaking = MutableStateFlow(false)
    val isAgentSpeaking: StateFlow<Boolean> = _isAgentSpeaking.asStateFlow()

    private val _isUserSpeaking = MutableStateFlow(false)
    val isUserSpeaking: StateFlow<Boolean> = _isUserSpeaking.asStateFlow()

    // ── Event flows for callbacks ──────────────────────────────────────
    private val _agentMessageEvent = MutableSharedFlow<String>()
    val agentMessageEvent: SharedFlow<String> = _agentMessageEvent.asSharedFlow()

    private val _userMessageEvent = MutableSharedFlow<String>()
    val userMessageEvent: SharedFlow<String> = _userMessageEvent.asSharedFlow()

    private val _errorEvent = MutableSharedFlow<String>()
    val errorEvent: SharedFlow<String> = _errorEvent.asSharedFlow()

    private val _statusChangeEvent = MutableSharedFlow<String>()
    val statusChangeEvent: SharedFlow<String> = _statusChangeEvent.asSharedFlow()

    // ── Client tool call callback ──────────────────────────────────────
    private var onClientToolCallHandler: ((toolName: String, toolCallId: String, parameters: Map<String, Any>) -> Unit)? = null

    /**
     * Register a handler for client tool calls from the ElevenLabs agent.
     * The handler receives the tool name, call ID, and parameters map.
     * The handler must call sendToolResult() when execution is complete.
     */
    fun setOnClientToolCall(handler: (toolName: String, toolCallId: String, parameters: Map<String, Any>) -> Unit) {
        onClientToolCallHandler = handler
    }

    // ── Internal state ─────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    private var audioRecord: android.media.AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var handshakeCompleted = false
    private var reconnectAttempts = 0
    private val gson = Gson()

    // System prompt override — injected via conversation_initiation_client_data on connect
    private var pendingSystemPromptOverride: String? = null

    // Client tool definitions — sent to ElevenLabs agent so it knows what tools to call
    private var pendingClientTools: List<ClientToolDef>? = null

    // Persisted copies for reconnect — not cleared after use
    private var lastSystemPrompt: String? = null
    private var lastClientTools: List<ClientToolDef>? = null

    /**
     * Lightweight definition of a client tool to send to ElevenLabs agent.
     */
    data class ClientToolDef(
        val name: String,
        val description: String,
        val parameters: Map<String, ToolParamDef>
    )

    data class ToolParamDef(
        val type: String,
        val description: String,
        val required: Boolean = true
    )

    // Audio playback queue
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isPlaybackRunning = false

    // Pre-buffer: captures mic audio between wake word detection and WebSocket connection
    // so the user's command spoken right after "Hey Nova" is not lost
    private val preBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var preBufferJob: Job? = null
    private var preBufferAudioRecord: android.media.AudioRecord? = null

    // Echo cancellation
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    // Shared audio session — AudioRecord + AudioTrack MUST share the same session for AEC to work
    private var sharedAudioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    // Minimum RMS energy threshold to send mic audio — filters ambient noise
    // NOTE: 150 was too aggressive and filtered out quiet/normal speech.
    // Lowered to 50 — still blocks dead silence (~10-30 RMS) but passes all speech.
    private const val MIC_ENERGY_THRESHOLD = 50

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // WebSocket-level ping/pong (not application-level)
        .build()

    private val coroutineScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )

    // Callbacks
    private var onAgentMessage: (String) -> Unit = {}
    private var onUserMessage: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var onStatusChange: (String) -> Unit = {}

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Start buffering mic audio IMMEDIATELY after wake word detection.
     *
     * Called by WakeWordService right when "Hey Nova" is detected, BEFORE
     * the ElevenLabs WebSocket is connected. This captures the user's command
     * spoken right after the wake word so it isn't lost during the ~1-2s
     * connection handshake.
     *
     * The buffered audio is flushed to the WebSocket once connect() completes.
     */
    fun startPreBuffering(context: android.content.Context) {
        if (preBufferJob?.isActive == true) return
        preBuffer.clear()

        preBufferJob = coroutineScope.launch {
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "PreBuffer: mic permission not granted")
                    return@launch
                }

                val bufferSize = android.media.AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
                )
                if (bufferSize <= 0) {
                    Log.e(TAG, "PreBuffer: invalid buffer size")
                    return@launch
                }

                val recorder = android.media.AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                    bufferSize * 2
                )

                if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "PreBuffer: AudioRecord init failed")
                    recorder.release()
                    return@launch
                }

                preBufferAudioRecord = recorder
                recorder.startRecording()
                Log.i(TAG, "PreBuffer: started capturing mic audio")

                val buffer = ShortArray(AUDIO_CHUNK_SIZE)
                // Buffer for up to 5 seconds (safety limit)
                val maxChunks = (SAMPLE_RATE * 5) / AUDIO_CHUNK_SIZE

                var chunkCount = 0
                while (chunkCount < maxChunks && preBufferJob?.isActive == true) {
                    val readCount = recorder.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        // Convert to PCM bytes (same format connect() sends)
                        val pcmBytes = ByteArray(readCount * 2)
                        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().put(buffer, 0, readCount)
                        preBuffer.add(pcmBytes)
                        chunkCount++
                    } else if (readCount < 0) {
                        break
                    }
                    delay(5)
                }
            } catch (e: CancellationException) {
                // Normal — stopped by connect() taking over
            } catch (e: Exception) {
                Log.e(TAG, "PreBuffer error", e)
            }
        }
    }

    /**
     * Stop pre-buffering and release the temporary AudioRecord.
     * Called internally when connect() takes over mic capture.
     */
    private fun stopPreBuffering() {
        preBufferJob?.cancel()
        preBufferJob = null
        try {
            preBufferAudioRecord?.let {
                if (it.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping pre-buffer recorder", e)
        }
        preBufferAudioRecord = null
    }

    /**
     * Connect to ElevenLabs Conversational AI WebSocket.
     * Initializes microphone recording and audio playback.
     *
     * @param systemPromptOverride Optional context injected via conversation_initiation_client_data.
     *   Sent immediately on connection open — does NOT trigger a spoken response.
     * @param clientTools Optional list of client tool definitions to register with the agent.
     * @return true if connection initiated successfully
     */
    suspend fun connect(
        systemPromptOverride: String? = null,
        clientTools: List<ClientToolDef>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        pendingSystemPromptOverride = systemPromptOverride
        pendingClientTools = clientTools
        // Persist for reconnect
        if (systemPromptOverride != null) lastSystemPrompt = systemPromptOverride
        if (clientTools != null) lastClientTools = clientTools
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING
        ) {
            Log.w(TAG, "Already connected or connecting")
            return@withContext false
        }

        _connectionState.value = ConnectionState.CONNECTING
        emitStatusChange("Connecting to ElevenLabs Conversational AI...")

        return@withContext try {
            // Initialize audio components
            if (!initializeAudioRecord()) {
                _connectionState.value = ConnectionState.ERROR
                emitError("Failed to initialize microphone")
                return@withContext false
            }

            if (!initializeAudioTrack()) {
                _connectionState.value = ConnectionState.ERROR
                emitError("Failed to initialize audio playback")
                return@withContext false
            }

            // Connect WebSocket
            val wsUrl = "$WEBSOCKET_URL?agent_id=${BuildConfig.ELEVENLABS_AGENT_ID}"
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY.trim())
                .build()

            webSocket = httpClient.newWebSocket(request, createWebSocketListener())
            Log.i(TAG, "WebSocket connection initiated to $wsUrl")

            // Wait for handshake
            val handshakeSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
                handshakeContinuation = continuation
                // Timeout: if handshake not completed in time, resume with false
                coroutineScope.launch {
                    delay(HANDSHAKE_TIMEOUT_MS)
                    if (continuation.isActive && !handshakeCompleted) {
                        continuation.resume(false)
                    }
                }
            }

            if (handshakeSuccess) {
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                emitStatusChange("Connected — listening")
                Log.i(TAG, "✓ WebSocket handshake complete — connection ESTABLISHED")

                // Stop pre-buffer recording (releases the temporary AudioRecord)
                stopPreBuffering()

                // Start microphone recording on the main AudioRecord
                startMicrophoneCapture()
                Log.i(TAG, "✓ Main mic capture started")

                // Flush any pre-buffered audio captured between wake word and now
                val bufferedChunks = preBuffer.size
                if (bufferedChunks > 0) {
                    Log.i(TAG, "Flushing $bufferedChunks pre-buffered audio chunks to WebSocket")
                    var flushed = 0
                    while (preBuffer.isNotEmpty()) {
                        val pcmBytes = preBuffer.poll() ?: break
                        val base64Audio = Base64.getEncoder().encodeToString(pcmBytes)
                        val message = JsonObject().apply {
                            addProperty("user_audio_chunk", base64Audio)
                        }
                        webSocket?.send(message.toString())
                        flushed++
                    }
                    Log.i(TAG, "✓ Flushed $flushed pre-buffered chunks — user's command should be heard")
                } else {
                    Log.w(TAG, "⚠ No pre-buffered audio chunks — user may need to repeat command")
                }

                true
            } else {
                _connectionState.value = ConnectionState.ERROR
                Log.e(TAG, "✗ Handshake TIMEOUT after ${HANDSHAKE_TIMEOUT_MS}ms — WebSocket never confirmed")
                emitError("Connection timed out — try again")
                disconnect()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            _connectionState.value = ConnectionState.ERROR
            emitError("Connection failed: ${e.message}")
            false
        }
    }

    /**
     * Disconnect from ElevenLabs Conversational AI.
     * Cleans up all resources.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from ElevenLabs Conversational AI")

        stopPreBuffering()
        preBuffer.clear()

        reconnectJob?.cancel()
        reconnectAttempts = 0
        recordingJob?.cancel()
        playbackJob?.cancel()
        pingJob?.cancel()

        // Clear persisted context on intentional disconnect
        lastSystemPrompt = null
        lastClientTools = null

        audioQueue.clear()
        isPlaybackRunning = false

        stopAudioRecord()
        stopAudioTrack()

        webSocket?.close(1000, "Client disconnect")
        webSocket = null

        _connectionState.value = ConnectionState.DISCONNECTED
        handshakeCompleted = false
        emitStatusChange("Disconnected from ElevenLabs Conversational AI")
    }

    /**
     * Register callback for agent messages.
     */
    fun setOnAgentMessage(callback: (String) -> Unit) {
        onAgentMessage = callback
    }

    /**
     * Register callback for user messages.
     */
    fun setOnUserMessage(callback: (String) -> Unit) {
        onUserMessage = callback
    }

    /**
     * Register callback for errors.
     */
    fun setOnError(callback: (String) -> Unit) {
        onError = callback
    }

    /**
     * Register callback for status changes.
     */
    fun setOnStatusChange(callback: (String) -> Unit) {
        onStatusChange = callback
    }

    /**
     * Send conversation_initiation_client_data to register client tools with the agent.
     * The system prompt is configured in the ElevenLabs dashboard (prompt override
     * is disabled in agent config, sending it causes code 1008 disconnection).
     * Must be called immediately after onOpen, before audio streaming starts.
     */
    private fun sendInitiationOverride(systemPrompt: String) {
        val agentConfig = JsonObject()

        // NOTE: Prompt override is disabled in the ElevenLabs agent config.
        // Do NOT send prompt override — it causes WebSocket close with code 1008:
        // "Override for field 'prompt' is not allowed by config."
        // The system prompt must be configured on the ElevenLabs dashboard.

        // Add client tools if available
        val tools = pendingClientTools
        if (!tools.isNullOrEmpty()) {
            val toolsArray = com.google.gson.JsonArray()
            for (tool in tools) {
                val toolJson = JsonObject().apply {
                    addProperty("type", "client")
                    addProperty("name", tool.name)
                    addProperty("description", tool.description)
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        val props = JsonObject()
                        val reqArr = com.google.gson.JsonArray()
                        tool.parameters.forEach { (pName, pDef) ->
                            props.add(pName, JsonObject().apply {
                                addProperty("type", pDef.type)
                                addProperty("description", pDef.description)
                            })
                            if (pDef.required) reqArr.add(pName)
                        }
                        add("properties", props)
                        add("required", reqArr)
                    })
                }
                toolsArray.add(toolJson)
            }
            agentConfig.add("tools", toolsArray)
            Log.i(TAG, "Registered ${tools.size} client tools with ElevenLabs agent")
        }

        // Only send initiation data if we have tools to register
        if (agentConfig.size() > 0) {
            val message = JsonObject().apply {
                addProperty("type", "conversation_initiation_client_data")
                add("conversation_config_override", JsonObject().apply {
                    add("agent", agentConfig)
                })
            }
            webSocket?.send(message.toString())
            Log.i(TAG, "Initiation data sent (tools only, no prompt override)")
        }

        // Clear pending (one-shot) but keep lastClientTools for reconnect
        pendingClientTools = null
    }

    /**
     * Send interruption signal to agent.
     */
    fun interrupt() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Not connected, cannot interrupt")
            return
        }

        val message = JsonObject().apply {
            addProperty("type", "interruption")
        }

        webSocket?.send(message.toString())
        Log.d(TAG, "Interruption sent")
    }

    /**
     * Send tool call result back to agent.
     *
     * @param toolCallId The tool call ID from agent
     * @param result The result of the tool execution
     * @param isError Whether the tool call resulted in an error
     */
    fun sendToolResult(toolCallId: String, result: String, isError: Boolean = false) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Not connected, cannot send tool result")
            return
        }

        val message = JsonObject().apply {
            addProperty("type", "client_tool_result")
            addProperty("tool_call_id", toolCallId)
            addProperty("result", result)
            addProperty("is_error", isError)
        }

        webSocket?.send(message.toString())
        Log.d(TAG, "Tool result sent for call $toolCallId")
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        reconnectJob?.cancel()
        disconnect()
        coroutineScope.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }

    // ── Private methods ────────────────────────────────────────────────

    private fun initializeAudioRecord(): Boolean {
        return try {
            val bufferSize = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            )

            if (bufferSize == android.media.AudioRecord.ERROR ||
                bufferSize == android.media.AudioRecord.ERROR_BAD_VALUE
            ) {
                Log.e(TAG, "Invalid AudioRecord buffer size: $bufferSize")
                return false
            }

            audioRecord = android.media.AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Enables AEC on hardware
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            // Grab session ID so AudioTrack can share it — critical for AEC
            sharedAudioSessionId = audioRecord!!.audioSessionId

            // Enable Acoustic Echo Canceler to prevent Nova hearing herself
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(sharedAudioSessionId)
                acousticEchoCanceler?.enabled = true
                Log.i(TAG, "AcousticEchoCanceler enabled (session=$sharedAudioSessionId)")
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sharedAudioSessionId)
                noiseSuppressor?.enabled = true
            }

            Log.i(TAG, "AudioRecord initialized: $SAMPLE_RATE Hz, buffer=$bufferSize, session=$sharedAudioSessionId, AEC=${AcousticEchoCanceler.isAvailable()}")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord initialization error", e)
            false
        }
    }

    private fun initializeAudioTrack(): Boolean {
        return try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_OUT_CONFIG, AUDIO_FORMAT
            )

            if (bufferSize == AudioTrack.ERROR || bufferSize == AudioTrack.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid AudioTrack buffer size: $bufferSize")
                return false
            }

            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // Pairs with VOICE_COMMUNICATION source for AEC
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT_CONFIG)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                sharedAudioSessionId // MUST share session with AudioRecord for AEC to cancel playback from mic
            )

            Log.i(TAG, "AudioTrack initialized: $SAMPLE_RATE Hz, buffer=$bufferSize, session=$sharedAudioSessionId (shared with AudioRecord)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack initialization error", e)
            false
        }
    }

    private fun stopAudioRecord() {
        try {
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
            audioRecord?.let {
                if (it.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
    }

    private fun stopAudioTrack() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null
    }

    private fun startMicrophoneCapture() {
        if (recordingJob?.isActive == true) return

        recordingJob = coroutineScope.launch {
            try {
                audioRecord?.startRecording()
                Log.i(TAG, "Microphone recording started (threshold=$MIC_ENERGY_THRESHOLD)")

                val buffer = ShortArray(AUDIO_CHUNK_SIZE)
                _isUserSpeaking.value = true
                var sentChunks = 0
                var skippedChunks = 0

                while (_connectionState.value == ConnectionState.CONNECTED) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                    if (readCount > 0) {
                        // Calculate RMS energy — skip chunk if below noise floor
                        var sumSquares = 0L
                        for (i in 0 until readCount) {
                            sumSquares += buffer[i].toLong() * buffer[i].toLong()
                        }
                        val rms = Math.sqrt(sumSquares.toDouble() / readCount).toInt()

                        if (rms < MIC_ENERGY_THRESHOLD) {
                            skippedChunks++
                            // Log every 100 skipped chunks so we can see if everything is being filtered
                            if (skippedChunks % 100 == 0) {
                                Log.d(TAG, "Mic: skipped $skippedChunks chunks (last rms=$rms), sent $sentChunks")
                            }
                            delay(10)
                            continue
                        }
                        sentChunks++

                        // Convert 16-bit PCM to base64
                        val pcmBytes = ByteArray(readCount * 2)
                        val byteBuffer = ByteBuffer.wrap(pcmBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()

                        for (i in 0 until readCount) {
                            byteBuffer.put(i, buffer[i])
                        }

                        val base64Audio = Base64.getEncoder().encodeToString(pcmBytes)

                        // Send audio chunk to WebSocket
                        val message = JsonObject().apply {
                            addProperty("user_audio_chunk", base64Audio)
                        }

                        webSocket?.send(message.toString())
                        // Log first few sends and then every 50th for diagnostics
                        if (sentChunks <= 3 || sentChunks % 50 == 0) {
                            Log.d(TAG, "Mic: sent chunk #$sentChunks (rms=$rms)")
                        }
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord read error: $readCount")
                        break
                    }

                    delay(10) // Non-blocking
                }

                _isUserSpeaking.value = false
                Log.i(TAG, "Microphone recording stopped (sent=$sentChunks, skipped=$skippedChunks)")
            } catch (e: Exception) {
                Log.e(TAG, "Microphone capture error", e)
                emitError("Microphone capture error: ${e.message}")
            }
        }
    }

    /**
     * Queue audio chunk for playback. Chunks are played sequentially.
     */
    private fun queueAudioPlayback(base64Audio: String) {
        try {
            val audioBytes = Base64.getDecoder().decode(base64Audio)
            audioQueue.add(audioBytes)

            // Start the playback loop if not already running
            if (!isPlaybackRunning) {
                startPlaybackLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error queuing audio", e)
        }
    }

    private fun startPlaybackLoop() {
        if (playbackJob?.isActive == true) return
        isPlaybackRunning = true

        playbackJob = coroutineScope.launch {
            try {
                audioTrack?.play()
                _isAgentSpeaking.value = true

                var emptyPollCount = 0
                while (_connectionState.value == ConnectionState.CONNECTED || audioQueue.isNotEmpty()) {
                    val chunk = audioQueue.poll()
                    if (chunk != null) {
                        audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        emptyPollCount = 0 // Reset on data received
                    } else {
                        emptyPollCount++
                        delay(50) // Wait longer between checks to tolerate network jitter
                        // Only break after sustained silence (500ms) AND connection is no longer active
                        if (audioQueue.isEmpty() && (
                                _connectionState.value != ConnectionState.CONNECTED || emptyPollCount > 10
                            )) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback loop error", e)
            } finally {
                _isAgentSpeaking.value = false
                isPlaybackRunning = false
                Log.d(TAG, "Playback loop ended")
            }
        }
    }

    private fun startPingKeepAlive() {
        // ElevenLabs server sends its own keepalive pings — we just need to stay alive.
        // Do NOT send application-level {"type":"ping"} — ElevenLabs rejects unknown messages
        // with code 1008 "Invalid message received".
        // OkHttp handles WebSocket-level ping/pong automatically.
        Log.d(TAG, "Keepalive: relying on server pings + OkHttp WebSocket ping/pong")
    }

    // ── Reconnect logic ──────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached, giving up")
            _connectionState.value = ConnectionState.ERROR
            emitError("Connection lost. Say 'Hey Nova' to reconnect.")
            reconnectAttempts = 0
            return
        }

        reconnectJob?.cancel()
        reconnectJob = coroutineScope.launch {
            reconnectAttempts++
            Log.i(TAG, "Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${RECONNECT_DELAY_MS}ms")
            delay(RECONNECT_DELAY_MS)

            // Clean up old connection
            recordingJob?.cancel()
            playbackJob?.cancel()
            pingJob?.cancel()
            audioQueue.clear()
            isPlaybackRunning = false
            stopAudioRecord()
            stopAudioTrack()
            webSocket = null
            handshakeCompleted = false

            // Reconnect — re-inject the same system prompt + tools from last session
            val success = connect(lastSystemPrompt, lastClientTools)
            if (success) {
                reconnectAttempts = 0
                Log.i(TAG, "Reconnected successfully with system prompt + tools")
            }
        }
    }

    // ── Event emitters ────────────────────────────────────────────────

    private fun emitAgentMessage(text: String) {
        onAgentMessage(text)
        coroutineScope.launch {
            _agentMessageEvent.emit(text)
        }
    }

    private fun emitUserMessage(text: String) {
        onUserMessage(text)
        coroutineScope.launch {
            _userMessageEvent.emit(text)
        }
    }

    private fun emitError(message: String) {
        onError(message)
        coroutineScope.launch {
            _errorEvent.emit(message)
        }
    }

    private fun emitStatusChange(status: String) {
        onStatusChange(status)
        coroutineScope.launch {
            _statusChangeEvent.emit(status)
        }
    }

    @Volatile
    private var handshakeContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    // ── WebSocket listener ─────────────────────────────────────────────

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened")
            // Inject system prompt + client tools BEFORE handshake completes and audio starts
            val prompt = pendingSystemPromptOverride
            if (!prompt.isNullOrBlank() || !pendingClientTools.isNullOrEmpty()) {
                sendInitiationOverride(prompt ?: "")
            }
            pendingSystemPromptOverride = null
            handshakeCompleted = true
            handshakeContinuation?.let { cont ->
                if (cont.isActive) {
                    cont.resume(true)
                }
            }
            handshakeContinuation = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val jsonElement = gson.fromJson(text, JsonElement::class.java)
                if (!jsonElement.isJsonObject) return

                val json = jsonElement.asJsonObject
                val type = json.get("type")?.asString ?: return

                when (type) {
                    "audio" -> handleAudioMessage(json)
                    "agent_response" -> handleAgentResponseMessage(json)
                    "user_transcript" -> handleUserTranscriptMessage(json)
                    "client_tool_call" -> handleToolCallMessage(json)
                    "conversation_initiation_metadata" -> {
                        val convId = json.getAsJsonObject("conversation_initiation_metadata_event")
                            ?.get("conversation_id")?.asString
                        Log.i(TAG, "Conversation started: id=$convId")
                    }
                    "interruption" -> {
                        // Agent was interrupted by user speech — clear audio queue
                        audioQueue.clear()
                        Log.d(TAG, "Agent interrupted — audio queue cleared")
                    }
                    "ping" -> {
                        // Server keepalive ping — do NOT respond, ElevenLabs rejects pong as invalid
                    }
                    "pong" -> {
                        // Pong response
                    }
                    else -> Log.d(TAG, "Unknown message type: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message parsing error", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: code=$code reason=$reason")
            // If server closed unexpectedly, try reconnect
            if (code != 1000) {
                _connectionState.value = ConnectionState.ERROR
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            emitStatusChange("Connection closed: $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            _connectionState.value = ConnectionState.ERROR
            emitError("WebSocket error: ${t.message}")
            // Don't call disconnect() immediately — try to reconnect
            scheduleReconnect()
        }
    }

    private fun handleAudioMessage(json: JsonObject) {
        try {
            val audioEvent = json.getAsJsonObject("audio_event")
            val base64Audio = audioEvent.get("audio_base_64").asString
            val eventId = audioEvent.get("event_id")?.asString

            if (base64Audio.isNotBlank()) {
                queueAudioPlayback(base64Audio)
                Log.d(TAG, "Audio chunk queued: event_id=$eventId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling audio message", e)
        }
    }

    private fun handleAgentResponseMessage(json: JsonObject) {
        try {
            val agentEvent = json.getAsJsonObject("agent_response_event")
            val responseText = agentEvent.get("agent_response").asString

            if (responseText.isNotBlank()) {
                _agentTranscription.value = responseText
                emitAgentMessage(responseText)
                Log.d(TAG, "Agent response: $responseText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling agent response", e)
        }
    }

    private fun handleUserTranscriptMessage(json: JsonObject) {
        try {
            val userEvent = json.getAsJsonObject("user_transcription_event")
            val userText = userEvent.get("user_transcript").asString

            if (userText.isNotBlank()) {
                _userTranscription.value = userText
                emitUserMessage(userText)
                Log.d(TAG, "User transcript: $userText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling user transcript", e)
        }
    }

    private fun handleToolCallMessage(json: JsonObject) {
        try {
            // ElevenLabs Conversational AI sends tool call data at the TOP LEVEL
            // of the JSON message, NOT nested in a sub-object.
            // Format: {"type":"client_tool_call","tool_name":"...","tool_call_id":"...","parameters":{...}}
            // Fallback to nested "client_tool_call" key for forward-compatibility.
            val nested = json.getAsJsonObject("client_tool_call")

            val toolName = json.get("tool_name")?.asString
                ?: nested?.get("tool_name")?.asString
                ?: throw IllegalStateException("Missing tool_name in client_tool_call")

            val toolCallId = json.get("tool_call_id")?.asString
                ?: nested?.get("tool_call_id")?.asString
                ?: throw IllegalStateException("Missing tool_call_id in client_tool_call")

            val parametersJson = json.getAsJsonObject("parameters")
                ?: nested?.getAsJsonObject("parameters")

            // Convert parameters to Map<String, Any>
            val params = mutableMapOf<String, Any>()
            parametersJson?.entrySet()?.forEach { (key, value) ->
                params[key] = when {
                    value.isJsonPrimitive -> {
                        val prim = value.asJsonPrimitive
                        when {
                            prim.isBoolean -> prim.asBoolean
                            prim.isNumber -> prim.asNumber
                            else -> prim.asString
                        }
                    }
                    else -> value.toString()
                }
            }

            Log.i(TAG, "Tool call received: $toolName (id=$toolCallId) params=$params")
            emitStatusChange("Agent requesting tool: $toolName")

            // Invoke the registered handler
            onClientToolCallHandler?.invoke(toolName, toolCallId, params)
                ?: run {
                    Log.w(TAG, "No tool call handler registered, sending error result")
                    sendToolResult(toolCallId, "No tool handler registered", isError = true)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling tool call: ${e.message}", e)
        }
    }
}
