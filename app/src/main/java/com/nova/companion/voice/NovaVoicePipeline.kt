package com.nova.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nova.companion.accessibility.ScreenContext
import com.nova.companion.cloud.CloudConfig
import com.nova.companion.cloud.CloudLLMService
import com.nova.companion.data.NovaDatabase
import com.nova.companion.overlay.AuraOverlayService
import com.nova.companion.ui.aura.AuraState
import com.nova.companion.inference.HybridInferenceRouter
import com.nova.companion.inference.LocalInferenceClient
import com.nova.companion.inference.OfflineCapabilityManager
import com.nova.companion.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Nova Voice Pipeline: Premium voice interaction orchestrator.
 *
 * Flow: Wake word → LISTENING (AudioRecord + Whisper STT) → THINKING (CloudLLM + tools) → SPEAKING (ElevenLabs TTS) → IDLE
 *
 * Architecture:
 * - STT: AudioRecord (instant mic capture, ~50ms startup) + OpenAI Whisper API (high accuracy)
 * - Brain: CloudLLMService.processWithTools() (existing — GPT-4o + all Nova tools)
 * - TTS: ElevenLabs REST streaming API (premium voice, low latency)
 *
 * Why AudioRecord + Whisper instead of Android SpeechRecognizer:
 * SpeechRecognizer takes ~1.1 seconds to initialize, during which audio is lost.
 * AudioRecord starts in ~50ms, capturing speech from the very first moment.
 * Whisper provides higher accuracy than on-device recognition.
 */
object NovaVoicePipeline {

    private const val TAG = "NovaVoicePipeline"

    // Audio capture settings — 16kHz mono PCM, Whisper's preferred format
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // Silence detection thresholds
    private const val SILENCE_DURATION_MS = 1500        // 1.5s of silence = user done speaking
    private const val MIN_SPEECH_DURATION_MS = 300      // Must have at least 300ms of speech
    private const val MAX_RECORDING_DURATION_MS = 12000 // Max 12 seconds recording
    private const val CALIBRATION_MS = 250              // 250ms ambient noise calibration
    private const val CHIME_IGNORE_MS = 150             // Brief ignore window for vibration motor noise
    private const val SPEECH_RMS_MULTIPLIER = 2.5       // Speech = ambient RMS * this multiplier
    private const val MIN_SPEECH_RMS = 300              // Absolute minimum RMS to count as speech
    private const val NO_SPEECH_TIMEOUT_MS = 5000       // Give up if no speech for 5 seconds

    // Multi-turn follow-up settings
    private const val FOLLOW_UP_SILENCE_MS = 1200       // Shorter silence for follow-up turns
    private const val FOLLOW_UP_NO_SPEECH_MS = 4000     // 4s to start speaking in follow-up
    private const val MAX_TURNS = 5                      // Max back-and-forth turns per session

    // ── Pipeline States ──────────────────────────────────────────
    enum class PipelineState {
        IDLE,       // Not active
        LISTENING,  // Recording user speech
        THINKING,   // LLM is processing (may include tool calls)
        SPEAKING,   // TTS is playing response audio
        ERROR       // Something went wrong
    }

    // ── State Flows ──────────────────────────────────────────────
    private val _state = MutableStateFlow(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _currentTool = MutableStateFlow<String?>(null)
    val currentTool: StateFlow<String?> = _currentTool.asStateFlow()

    /** Audio amplitude (0..1) from TTS playback, for aura SPEAKING visualization */
    val speakingAmplitude: StateFlow<Float> = ElevenLabsTTS.amplitude

    private val _userMessageEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessageEvent: SharedFlow<String> = _userMessageEvent.asSharedFlow()

    private val _assistantMessageEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val assistantMessageEvent: SharedFlow<String> = _assistantMessageEvent.asSharedFlow()

    // ── Internal ─────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var pipelineJob: Job? = null
    private var recordingJob: Job? = null
    private var scope: CoroutineScope? = null
    private var appContext: Context? = null

    @Volatile
    private var isRecording = false

    @Volatile
    private var turnCount = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Start a voice interaction session.
     *
     * Immediately starts AudioRecord to capture speech (~50ms startup).
     * Plays a chime when recording begins, detects silence, then sends audio to Whisper.
     */
    fun start(context: Context, coroutineScope: CoroutineScope) {
        if (_state.value == PipelineState.LISTENING || _state.value == PipelineState.THINKING) {
            Log.w(TAG, "Pipeline already active: ${_state.value}")
            return
        }

        // Barge-in: if Nova is speaking, interrupt and start listening
        if (_state.value == PipelineState.SPEAKING) {
            Log.i(TAG, "Barge-in! Interrupting speech to start listening")
            ElevenLabsTTS.stop()
            pipelineJob?.cancel()
            pipelineJob = null
        }

        appContext = context.applicationContext
        scope = coroutineScope
        turnCount = 0

        Log.i(TAG, "Starting voice pipeline (AudioRecord + Whisper)")
        _state.value = PipelineState.LISTENING
        pushAuraState(PipelineState.LISTENING)
        WakeWordService.updateStatus("Listening to you...")
        _partialText.value = ""
        _recognizedText.value = ""
        _currentTool.value = null

        // Start recording on IO thread — AudioRecord is fast, no main thread needed
        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            recordAndTranscribe(context)
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping voice pipeline")
        isRecording = false
        turnCount = 0
        pipelineJob?.cancel()
        pipelineJob = null
        recordingJob?.cancel()
        recordingJob = null

        ElevenLabsTTS.stop()
        releaseAudioRecord()

        _state.value = PipelineState.IDLE
        pushAuraState(PipelineState.IDLE)
        _partialText.value = ""
        _currentTool.value = null
        WakeWordService.updateStatus("Listening for 'Hey Nova'...")
    }

    fun isActive(): Boolean = _state.value != PipelineState.IDLE

    /**
     * Push current pipeline state directly to the overlay service.
     * This is the primary path — ensures the Dynamic Island reflects voice state
     * even when ChatViewModel isn't alive (background wake-word sessions).
     */
    private fun pushAuraState(pipelineState: PipelineState) {
        val aura = when (pipelineState) {
            PipelineState.LISTENING -> AuraState.LISTENING
            PipelineState.THINKING -> AuraState.THINKING
            PipelineState.SPEAKING -> AuraState.SPEAKING
            PipelineState.IDLE, PipelineState.ERROR -> AuraState.DORMANT
        }
        AuraOverlayService.updateAuraState(aura)
    }

    // ── Offline Voice Pipeline ──────────────────────────────────

    /**
     * Start an offline voice session: Android STT → llama.cpp → Android TTS.
     * Used when device is offline but a local model is loaded.
     */
    fun startOffline(context: Context, coroutineScope: CoroutineScope) {
        if (_state.value != PipelineState.IDLE && _state.value != PipelineState.ERROR) {
            Log.w(TAG, "Pipeline already active for offline: ${_state.value}")
            return
        }

        appContext = context.applicationContext
        scope = coroutineScope
        turnCount = 0

        Log.i(TAG, "Starting OFFLINE voice pipeline (Android STT → llama.cpp → Android TTS)")

        // Initialize offline TTS
        OfflineTTS.init(context)

        pipelineJob = coroutineScope.launch {
            _state.value = PipelineState.LISTENING
            pushAuraState(PipelineState.LISTENING)
            WakeWordService.updateStatus("Listening (offline)...")
            _partialText.value = "Listening..."

            // Check if local model is ready
            if (com.nova.companion.inference.NovaInference.state.value !=
                com.nova.companion.inference.NovaInference.ModelState.READY
            ) {
                Log.w(TAG, "Offline: no local model loaded")
                _partialText.value = "No offline model loaded"
                scope?.launch { _assistantMessageEvent.emit("I'm offline and no local model is loaded. Load a model in Settings first.") }
                delay(2000)
                endSession()
                return@launch
            }

            // Step 1: Android STT (must run on Main for SpeechRecognizer)
            val transcription = withContext(Dispatchers.Main) {
                OfflineSTT.recognize(context)
            }

            if (transcription.isNullOrBlank()) {
                Log.i(TAG, "Offline: no speech detected")
                endSession()
                return@launch
            }

            Log.i(TAG, "Offline transcription: \"$transcription\"")
            _recognizedText.value = transcription
            _partialText.value = transcription
            scope?.launch { _userMessageEvent.emit(transcription) }

            // Step 2: Local LLM
            _state.value = PipelineState.THINKING
            pushAuraState(PipelineState.THINKING)
            WakeWordService.updateStatus("Thinking (offline)...")
            _partialText.value = "Thinking..."

            try {
                val response = com.nova.companion.inference.NovaInference.generate(
                    userMessage = transcription
                )

                if (response.isBlank()) {
                    scope?.launch { _assistantMessageEvent.emit("Sorry, I couldn't generate a response offline.") }
                    endSession()
                    return@launch
                }

                Log.i(TAG, "Offline LLM response: ${response.take(80)}...")
                scope?.launch { _assistantMessageEvent.emit(response) }

                // Step 3: Android TTS
                _state.value = PipelineState.SPEAKING
                pushAuraState(PipelineState.SPEAKING)
                WakeWordService.updateStatus("Speaking (offline)...")

                OfflineTTS.speak(response)
                endSession()
            } catch (e: Exception) {
                Log.e(TAG, "Offline pipeline error", e)
                scope?.launch { _assistantMessageEvent.emit("Offline processing failed: ${e.message}") }
                endSession()
            }
        }
    }

    // ── AudioRecord + Whisper STT ────────────────────────────────

    private suspend fun recordAndTranscribe(
        context: Context,
        silenceDurationMs: Int = SILENCE_DURATION_MS,
        noSpeechTimeoutMs: Int = NO_SPEECH_TIMEOUT_MS,
        playChime: Boolean = true
    ) {
        // Check mic permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            _state.value = PipelineState.ERROR
            pushAuraState(PipelineState.ERROR)
            WakeWordService.resumeListening()
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $minBufSize")
            _state.value = PipelineState.ERROR
            pushAuraState(PipelineState.ERROR)
            WakeWordService.resumeListening()
            return
        }

        val bufferSize = maxOf(minBufSize * 2, SAMPLE_RATE * 2) // At least 1 second buffer

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                recorder.release()
                _state.value = PipelineState.ERROR
                pushAuraState(PipelineState.ERROR)
                WakeWordService.resumeListening()
                return
            }

            audioRecord = recorder
            recorder.startRecording()
            isRecording = true

            val startTime = System.currentTimeMillis()
            Log.i(TAG, ">>> AudioRecord started — beginning ambient calibration")

            // ── Phase 1: Calibrate ambient noise (~250ms) ──────────────
            val readBuffer = ShortArray(1024)
            var calibrationSum = 0L
            var calibrationSamples = 0
            val calibrationEnd = startTime + CALIBRATION_MS

            while (System.currentTimeMillis() < calibrationEnd && isRecording) {
                val shortsRead = recorder.read(readBuffer, 0, readBuffer.size)
                if (shortsRead <= 0) continue
                for (i in 0 until shortsRead) {
                    val sample = readBuffer[i].toLong()
                    calibrationSum += sample * sample
                }
                calibrationSamples += shortsRead
            }

            val ambientRms = if (calibrationSamples > 0) {
                Math.sqrt(calibrationSum.toDouble() / calibrationSamples).toInt()
            } else 200 // safe fallback

            val speechThreshold = maxOf(
                (ambientRms * SPEECH_RMS_MULTIPLIER).toInt(),
                MIN_SPEECH_RMS
            )
            Log.i(TAG, ">>> Ambient RMS=$ambientRms, speech threshold=$speechThreshold (${SPEECH_RMS_MULTIPLIER}x ambient, min $MIN_SPEECH_RMS)")

            // ── Phase 2: Play ready chime (skip on follow-up turns) ────
            val chimeTime = System.currentTimeMillis()
            if (playChime) {
                withContext(Dispatchers.Main) {
                    playReadyChime()
                }
                Log.i(TAG, ">>> Chime played at ${chimeTime - startTime}ms — ignoring mic for ${CHIME_IGNORE_MS}ms")
            } else {
                Log.i(TAG, ">>> Follow-up turn — skipping chime")
            }

            _partialText.value = "Listening..."

            // ── Phase 3: Record with smart silence detection ───────────
            val audioData = ByteArrayOutputStream()
            var silenceStartMs = 0L
            var hasSpeech = false
            var speechStartMs = 0L
            val chimeIgnoreUntil = if (playChime) chimeTime + CHIME_IGNORE_MS else chimeTime

            while (isRecording) {
                val now = System.currentTimeMillis()
                val elapsed = now - startTime

                // Max duration safety
                if (elapsed > MAX_RECORDING_DURATION_MS) {
                    Log.i(TAG, "Max recording duration reached (${MAX_RECORDING_DURATION_MS}ms)")
                    break
                }

                // No-speech timeout: if user never speaks, stop waiting
                if (!hasSpeech && (now - chimeIgnoreUntil) > noSpeechTimeoutMs && now > chimeIgnoreUntil) {
                    Log.i(TAG, ">>> No speech detected for ${noSpeechTimeoutMs}ms after chime — giving up")
                    break
                }

                val shortsRead = recorder.read(readBuffer, 0, readBuffer.size)
                if (shortsRead <= 0) continue

                // Always capture all audio (including chime period) for Whisper
                val byteBuffer = ByteBuffer.allocate(shortsRead * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until shortsRead) {
                    byteBuffer.putShort(readBuffer[i])
                }
                audioData.write(byteBuffer.array())

                // Skip speech/silence detection during chime bleed period
                if (now < chimeIgnoreUntil) continue

                // Calculate RMS
                var sumSquares = 0L
                for (i in 0 until shortsRead) {
                    val sample = readBuffer[i].toLong()
                    sumSquares += sample * sample
                }
                val rms = Math.sqrt(sumSquares.toDouble() / shortsRead).toInt()

                if (rms > speechThreshold) {
                    // Speech detected
                    if (!hasSpeech) {
                        hasSpeech = true
                        speechStartMs = now
                        Log.i(TAG, ">>> Speech DETECTED at ${elapsed}ms (rms=$rms, threshold=$speechThreshold)")
                        _partialText.value = "Hearing you..."
                    }
                    silenceStartMs = 0L // reset silence counter
                } else if (hasSpeech) {
                    // Silence after speech — user may be done
                    if (silenceStartMs == 0L) {
                        silenceStartMs = now
                    }
                    val silenceDuration = now - silenceStartMs
                    if (silenceDuration >= silenceDurationMs) {
                        val speechDuration = now - speechStartMs
                        if (speechDuration >= MIN_SPEECH_DURATION_MS) {
                            Log.i(TAG, ">>> Silence detected after ${speechDuration}ms of speech (silent ${silenceDuration}ms, rms=$rms) — done")
                            break
                        }
                    }
                }
            }

            isRecording = false
            recorder.stop()
            recorder.release()
            audioRecord = null

            val totalBytes = audioData.size()
            val durationMs = (totalBytes.toLong() * 1000) / (SAMPLE_RATE * 2) // 2 bytes per sample
            Log.i(TAG, "Recording complete: ${totalBytes / 1024}KB, ~${durationMs}ms, hasSpeech=$hasSpeech")

            if (!hasSpeech || totalBytes < SAMPLE_RATE) { // Less than 0.5s of audio
                Log.i(TAG, "No speech detected in recording — ending session")
                endSession()
                return
            }

            // Convert PCM to WAV for Whisper API
            _partialText.value = "Processing..."
            _state.value = PipelineState.THINKING
            pushAuraState(PipelineState.THINKING)
            WakeWordService.updateStatus("Processing your request...")

            val wavData = pcmToWav(audioData.toByteArray())
            Log.i(TAG, "Sending ${wavData.size / 1024}KB WAV to Whisper API")

            // Transcribe with Whisper
            val transcription = transcribeWithWhisper(wavData)

            if (transcription.isNullOrBlank()) {
                Log.w(TAG, "Whisper returned empty transcription")
                endSession()
                return
            }

            Log.i(TAG, ">>> Whisper transcription: \"$transcription\"")
            _recognizedText.value = transcription
            _partialText.value = transcription

            // Emit user message event (for chat history)
            scope?.launch { _userMessageEvent.emit(transcription) }

            // Move to THINKING phase
            withContext(Dispatchers.Main) {
                processWithLLM(transcription)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Recording/transcription error", e)
            isRecording = false
            releaseAudioRecord()
            _state.value = PipelineState.ERROR
            pushAuraState(PipelineState.ERROR)
            scope?.launch {
                delay(500)
                WakeWordService.resumeListening()
                delay(2000)
                _state.value = PipelineState.IDLE
                pushAuraState(PipelineState.IDLE)
            }
        }
    }

    /**
     * Transcribe audio using OpenAI Whisper API.
     *
     * POST /v1/audio/transcriptions
     * multipart/form-data: file (WAV), model ("whisper-1")
     */
    private fun transcribeWithWhisper(wavData: ByteArray): String? {
        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "OpenAI API key not configured — cannot use Whisper")
            return null
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "en")
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "Whisper API error ${response.code}: $errorBody")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        return try {
            JSONObject(responseBody).getString("text").trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Whisper response: $responseBody", e)
            null
        }
    }

    /**
     * Convert raw PCM data to WAV format (adds 44-byte WAV header).
     */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8 // sampleRate * channels * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF header
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            // fmt subchunk
            put("fmt ".toByteArray())
            putInt(16)          // Subchunk1Size (16 for PCM)
            putShort(1)         // AudioFormat (1 = PCM)
            putShort(1)         // NumChannels (mono)
            putInt(SAMPLE_RATE) // SampleRate
            putInt(byteRate)    // ByteRate
            putShort(2)         // BlockAlign (channels * bitsPerSample / 8)
            putShort(16)        // BitsPerSample
            // data subchunk
            put("data".toByteArray())
            putInt(pcmData.size)
        }

        return header.array() + pcmData
    }

    // ── LLM: CloudLLMService + Tool Execution ────────────────────

    /**
     * Process transcribed text through the LLM.
     *
     * Gap-Fill Strategy (zero-latency local-first):
     * If a local model is loaded AND cloud is being used, fire both in parallel:
     * 1. Local SLM generates a quick acknowledgment (~200ms, 32 tokens)
     *    → Spoken immediately so user hears "On it bro" within 200-300ms
     * 2. Cloud LLM processes the full request (~2-4 seconds)
     *    → Once cloud response arrives, speak the full answer
     *
     * This eliminates the perceived "dead air" between wake word and response.
     */
    private fun processWithLLM(userText: String) {
        val ctx = appContext ?: run {
            _state.value = PipelineState.ERROR
            return
        }

        _state.value = PipelineState.THINKING
        pushAuraState(PipelineState.THINKING)
        WakeWordService.updateStatus("Thinking...")

        pipelineJob = scope?.launch(Dispatchers.IO) {
            try {
                // ── Gap-fill: fire local quick response in parallel ────
                // Only if local model is loaded and cloud will be used
                val localReady = LocalInferenceClient.isReady()
                val gapFillJob = if (localReady) {
                    async {
                        try {
                            val quickAck = LocalInferenceClient.quickResponse(userText)
                            if (!quickAck.isNullOrBlank()) {
                                Log.i(TAG, "Gap-fill ack: \"$quickAck\"")
                                withContext(Dispatchers.Main) {
                                    _partialText.value = quickAck
                                    // Speak the quick ack immediately for zero-latency feel
                                    speakGapFill(quickAck)
                                }
                            }
                            Unit
                        } catch (e: Exception) {
                            Log.w(TAG, "Gap-fill failed (non-critical): ${e.message}")
                        }
                    }
                } else null

                // ── Main processing: screen context + voice history ────
                val screenDeferred = async { ScreenContext.getSummary() }
                val historyDeferred = async { buildVoiceHistory(ctx) }
                val toolDefs = ToolRegistry.getToolDefinitionsForLLM()
                val screenSummary = screenDeferred.await()
                val history = historyDeferred.await()

                val enrichedMessage = if (screenSummary.isNotBlank()) {
                    "$userText\n\n[Screen context: $screenSummary]"
                } else userText

                CloudLLMService.processWithTools(
                    userMessage = enrichedMessage,
                    toolDefinitions = toolDefs,
                    context = ctx,
                    conversationHistory = history,
                    onToolCall = { toolName, params ->
                        // Cancel gap-fill TTS if it's still playing — full response incoming
                        gapFillJob?.cancel()
                        ElevenLabsTTS.stop()

                        _currentTool.value = toolName
                        WakeWordService.updateStatus("Running: $toolName")
                        Log.d(TAG, "Executing tool: $toolName")

                        val tool = ToolRegistry.getTool(toolName)
                        if (tool != null) {
                            try {
                                val result = withTimeoutOrNull(30_000L) {
                                    tool.executor(ctx, params)
                                }
                                result ?: com.nova.companion.tools.ToolResult(
                                    false, "Tool '$toolName' timed out after 30s"
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Tool $toolName failed", e)
                                com.nova.companion.tools.ToolResult(
                                    false, "Tool '$toolName' failed: ${e.message}"
                                )
                            }
                        } else {
                            com.nova.companion.tools.ToolResult(false, "Unknown tool: $toolName")
                        }
                    },
                    onResponse = { response ->
                        // Cancel gap-fill if still speaking
                        gapFillJob?.cancel()

                        _currentTool.value = null
                        Log.i(TAG, "LLM response: ${response.take(80)}...")
                        scope?.launch { _assistantMessageEvent.emit(response) }
                        speakResponse(response)
                    },
                    onError = { error ->
                        Log.e(TAG, "LLM error: $error")
                        gapFillJob?.cancel()
                        _currentTool.value = null
                        _state.value = PipelineState.ERROR
                        pushAuraState(PipelineState.ERROR)
                        scope?.launch {
                            _assistantMessageEvent.emit("Sorry, something went wrong: $error")
                            delay(500)
                            WakeWordService.resumeListening()
                            delay(2000)
                            _state.value = PipelineState.IDLE
                            pushAuraState(PipelineState.IDLE)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline processing error", e)
                _state.value = PipelineState.ERROR
                pushAuraState(PipelineState.ERROR)
                scope?.launch {
                    delay(500)
                    WakeWordService.resumeListening()
                    delay(2000)
                    _state.value = PipelineState.IDLE
                    pushAuraState(PipelineState.IDLE)
                }
            }
        }
    }

    /**
     * Speak a gap-fill acknowledgment using offline TTS.
     * This is intentionally NOT ElevenLabs — we use OfflineTTS for instant playback
     * (no network roundtrip) so the user hears "On it bro" in ~200ms.
     *
     * If ElevenLabs is available, the full response will interrupt this.
     */
    private fun speakGapFill(text: String) {
        scope?.launch {
            try {
                val ctx = appContext ?: return@launch
                OfflineTTS.init(ctx)
                OfflineTTS.speak(text)
            } catch (e: Exception) {
                Log.w(TAG, "Gap-fill TTS failed (non-critical)", e)
            }
        }
    }

    // ── TTS: ElevenLabs Premium Voice ────────────────────────────

    private fun speakResponse(text: String) {
        _state.value = PipelineState.SPEAKING
        pushAuraState(PipelineState.SPEAKING)
        WakeWordService.updateStatus("Nova is speaking...")

        // Forward TTS amplitude to overlay for audio-reactive aura
        val ampJob = scope?.launch {
            ElevenLabsTTS.amplitude.collect { amp ->
                AuraOverlayService.updateAmplitude(amp)
            }
        }

        scope?.launch {
            ElevenLabsTTS.speak(
                text = text,
                onStart = { Log.d(TAG, "TTS playback started") },
                onComplete = {
                    Log.d(TAG, "TTS playback complete — turn $turnCount")
                    ampJob?.cancel()
                    AuraOverlayService.updateAmplitude(0f)
                    startFollowUpListening()
                },
                onError = { error ->
                    Log.e(TAG, "TTS error: $error")
                    ampJob?.cancel()
                    AuraOverlayService.updateAmplitude(0f)
                    endSession()
                }
            )
        }
    }

    /**
     * After Nova speaks, auto-listen for a follow-up question.
     * Uses shorter timeouts and skips the vibration chime.
     * If no speech detected within 4s, ends the session gracefully.
     */
    private fun startFollowUpListening() {
        val ctx = appContext ?: run { endSession(); return }

        turnCount++
        if (turnCount >= MAX_TURNS) {
            Log.i(TAG, "Max turns ($MAX_TURNS) reached — ending session")
            endSession()
            return
        }

        Log.i(TAG, "Follow-up listening — turn $turnCount/$MAX_TURNS")
        _state.value = PipelineState.LISTENING
        pushAuraState(PipelineState.LISTENING)
        WakeWordService.updateStatus("Listening...")
        _partialText.value = ""
        _recognizedText.value = ""
        _currentTool.value = null

        recordingJob = scope?.launch(Dispatchers.IO) {
            recordAndTranscribe(
                context = ctx,
                silenceDurationMs = FOLLOW_UP_SILENCE_MS,
                noSpeechTimeoutMs = FOLLOW_UP_NO_SPEECH_MS,
                playChime = false
            )
        }
    }

    /**
     * Cleanly end a multi-turn voice session.
     */
    private fun endSession() {
        Log.i(TAG, "Ending voice session (completed $turnCount turns)")
        turnCount = 0
        _state.value = PipelineState.IDLE
        pushAuraState(PipelineState.IDLE)
        _partialText.value = ""
        _currentTool.value = null
        WakeWordService.resumeListening()
        WakeWordService.updateStatus("Listening for 'Hey Nova'...")
    }

    // ── History Builder ──────────────────────────────────────────

    private suspend fun buildVoiceHistory(context: Context): List<Pair<String, String>> {
        return try {
            val db = NovaDatabase.getInstance(context)
            val messages = db.messageDao().getRecentMessages(10).reversed()
            messages.map { Pair(it.role, it.content) }
        } catch (e: Exception) {
            Log.e(TAG, "Error building voice history", e)
            emptyList()
        }
    }

    // ── Ready Signal (vibration — no audio = no mic interference) ──

    private fun playReadyChime() {
        try {
            val ctx = appContext ?: return
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // Double-tick: 40ms buzz, 60ms pause, 40ms buzz — "ready" feel
            val pattern = longArrayOf(0, 40, 60, 40)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            Log.d(TAG, "Ready vibration played (double-tick)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play ready signal", e)
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────

    private fun releaseAudioRecord() {
        try {
            audioRecord?.let { recorder ->
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    recorder.stop()
                }
                recorder.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    fun release() {
        stop()
        scope = null
        appContext = null
    }
}
