package com.nova.companion.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ElevenLabs Conversational AI Voice Service
 *
 * Manages the full conversational AI pipeline via WebSocket:
 * Mic → STT → LLM → TTS → Speaker (all on single WebSocket connection)
 *
 * Handles:
 * - WebSocket connection to ElevenLabs Conversational AI endpoint
 * - Microphone capture with 16kHz 16-bit mono PCM
 * - Audio streaming to WebSocket as base64-encoded chunks
 * - Audio playback from WebSocket via AudioTrack
 * - Transcription updates (user & agent)
 * - Tool call handling and results
 * - Connection state management
 * - Graceful disconnection and error recovery
 */
object ElevenLabsVoiceService {

    private const val TAG = "ElevenLabsVoice"
    private const val AGENT_ID = "agent_1001kjg9ge5cem"
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
    private var handshakeCompleted = false
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
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
     * Connect to ElevenLabs Conversational AI WebSocket.
     * Initializes microphone recording and audio playback.
     *
     * @return true if connection initiated successfully
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
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
            val wsUrl = "$WEBSOCKET_URL?agent_id=$AGENT_ID"
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer ${BuildConfig.ELEVENLABS_API_KEY}")
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
                emitStatusChange("Connected to ElevenLabs Conversational AI")

                // Start microphone recording
                startMicrophoneCapture()

                // Start ping/pong for keepalive
                startPingKeepAlive()

                true
            } else {
                _connectionState.value = ConnectionState.ERROR
                emitError("Handshake timeout")
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

        recordingJob?.cancel()
        playbackJob?.cancel()
        pingJob?.cancel()

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
                MediaRecorder.AudioSource.MIC,
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

            Log.i(TAG, "AudioRecord initialized: $SAMPLE_RATE Hz, buffer=$bufferSize")
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
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT_CONFIG)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            Log.i(TAG, "AudioTrack initialized: $SAMPLE_RATE Hz, buffer=$bufferSize")
            true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack initialization error", e)
            false
        }
    }

    private fun stopAudioRecord() {
        try {
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
                Log.i(TAG, "Microphone recording started")

                val buffer = ShortArray(AUDIO_CHUNK_SIZE)
                _isUserSpeaking.value = true

                while (_connectionState.value == ConnectionState.CONNECTED) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                    if (readCount > 0) {
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
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord read error: $readCount")
                        break
                    }

                    delay(10) // Non-blocking
                }

                _isUserSpeaking.value = false
                Log.i(TAG, "Microphone recording stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Microphone capture error", e)
                emitError("Microphone capture error: ${e.message}")
            }
        }
    }

    private fun startPlaybackAudio(base64Audio: String) {
        if (playbackJob?.isActive == true) return

        playbackJob = coroutineScope.launch {
            try {
                val audioBytes = Base64.getDecoder().decode(base64Audio)
                audioTrack?.write(audioBytes, 0, audioBytes.size, AudioTrack.WRITE_BLOCKING)
                audioTrack?.play()

                _isAgentSpeaking.value = true
                Log.d(TAG, "Playback started: ${audioBytes.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                emitError("Playback error: ${e.message}")
            }
        }
    }

    private fun startPingKeepAlive() {
        if (pingJob?.isActive == true) return

        pingJob = coroutineScope.launch {
            while (_connectionState.value == ConnectionState.CONNECTED) {
                try {
                    delay(PING_INTERVAL_MS)
                    val ping = JsonObject().apply {
                        addProperty("type", "ping")
                    }
                    webSocket?.send(ping.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Ping error", e)
                }
            }
        }
    }

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
            _connectionState.value = ConnectionState.DISCONNECTED
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
            disconnect()
        }
    }

    private fun handleAudioMessage(json: JsonObject) {
        try {
            val audioEvent = json.getAsJsonObject("audio_event")
            val base64Audio = audioEvent.get("audio_base_64").asString
            val eventId = audioEvent.get("event_id")?.asString

            if (base64Audio.isNotBlank()) {
                startPlaybackAudio(base64Audio)
                Log.d(TAG, "Audio message received: event_id=$eventId")
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
            val toolEvent = json.getAsJsonObject("client_tool_call_event")
            val toolName = toolEvent.get("tool_name").asString
            val toolCallId = toolEvent.get("tool_call_id").asString
            val parametersJson = toolEvent.getAsJsonObject("parameters")

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
            Log.e(TAG, "Error handling tool call", e)
        }
    }
}
