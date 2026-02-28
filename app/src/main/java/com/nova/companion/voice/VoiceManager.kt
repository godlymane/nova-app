package com.nova.companion.voice

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Orchestrates the complete voice conversation pipeline:
 *   Mic → Whisper STT → LLM → Piper TTS → Speaker
 *
 * Manages voice state machine and provides a unified API for the UI layer.
 */
class VoiceManager {

    companion object {
        private const val TAG = "VoiceManager"

        // Default model paths (user copies these to device storage)
        private val WHISPER_MODEL_NAMES = listOf(
            "ggml-tiny.bin",
            "ggml-tiny.en.bin",
            "ggml-base.bin",
            "ggml-base.en.bin"
        )
        private val PIPER_MODEL_NAMES = listOf(
            "en_US-amy-medium.onnx",
            "en_US-lessac-medium.onnx",
            "en_US-amy-low.onnx",
            "en_US-lessac-low.onnx"
        )
    }

    // ── Voice state machine ───────────────────────────────────────
    enum class VoiceState {
        IDLE,           // Voice mode active but not doing anything
        LISTENING,      // Recording from mic (show pulse animation)
        TRANSCRIBING,   // Whisper is processing audio (show dots)
        THINKING,       // LLM is generating response
        SPEAKING,       // Piper is playing speech (show wave animation)
        ERROR           // Something went wrong
    }

    val stt = WhisperSTT()
    val tts = PiperTTS()

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _isVoiceModeEnabled = MutableStateFlow(false)
    val isVoiceModeEnabled: StateFlow<Boolean> = _isVoiceModeEnabled.asStateFlow()

    private val _voiceModelsLoaded = MutableStateFlow(false)
    val voiceModelsLoaded: StateFlow<Boolean> = _voiceModelsLoaded.asStateFlow()

    // Error message for UI
    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    // Expose sub-component states for UI
    val micAmplitude: StateFlow<Float> = stt.recorder.amplitudeLevel
    val speakerAmplitude: StateFlow<Float> = tts.playbackAmplitude
    val partialTranscription: StateFlow<String> = stt.partialText
    val isRecording: StateFlow<Boolean> = stt.recorder.isRecording
    val isSpeaking: StateFlow<Boolean> = tts.isSpeaking

    /**
     * Initialize voice models (Whisper + Piper).
     * Searches common storage locations for model files.
     */
    suspend fun initializeVoiceModels(): Boolean = withContext(Dispatchers.IO) {
        _voiceError.value = null

        // Find Whisper model
        val whisperModel = findModelFile(WHISPER_MODEL_NAMES)
        if (whisperModel == null) {
            _voiceError.value = "Whisper model not found. Copy ggml-tiny.bin to Downloads/"
            Log.e(TAG, "Whisper model not found")
            return@withContext false
        }

        // Find Piper model + config
        val piperModel = findModelFile(PIPER_MODEL_NAMES)
        if (piperModel == null) {
            _voiceError.value = "Piper voice model not found. Copy en_US-amy-medium.onnx to Downloads/"
            Log.e(TAG, "Piper model not found")
            return@withContext false
        }

        val piperConfig = File(piperModel.absolutePath.replace(".onnx", ".onnx.json"))
        if (!piperConfig.exists()) {
            _voiceError.value = "Piper config not found: ${piperConfig.name}"
            Log.e(TAG, "Piper config not found: ${piperConfig.absolutePath}")
            return@withContext false
        }

        // Load Whisper
        Log.i(TAG, "Loading Whisper: ${whisperModel.absolutePath}")
        val whisperLoaded = stt.loadModel(whisperModel.absolutePath)
        if (!whisperLoaded) {
            _voiceError.value = "Failed to load Whisper model"
            return@withContext false
        }

        // Load Piper
        Log.i(TAG, "Loading Piper: ${piperModel.absolutePath}")
        val piperLoaded = tts.loadModel(piperModel.absolutePath, piperConfig.absolutePath)
        if (!piperLoaded) {
            _voiceError.value = "Failed to load Piper voice model"
            return@withContext false
        }

        _voiceModelsLoaded.value = true
        Log.i(TAG, "Voice models loaded successfully")
        true
    }

    /**
     * Toggle voice mode on/off.
     * When turning on, initializes voice models if not already loaded.
     */
    suspend fun toggleVoiceMode(): Boolean {
        if (_isVoiceModeEnabled.value) {
            // Turning off
            _isVoiceModeEnabled.value = false
            _voiceState.value = VoiceState.IDLE
            stopAll()
            return false
        } else {
            // Turning on - load models if needed
            if (!_voiceModelsLoaded.value) {
                val loaded = initializeVoiceModels()
                if (!loaded) return false
            }
            _isVoiceModeEnabled.value = true
            _voiceState.value = VoiceState.IDLE
            return true
        }
    }

    /**
     * Start listening for voice input.
     * Call from mic button press/hold.
     */
    fun startListening(scope: CoroutineScope): Boolean {
        if (!_voiceModelsLoaded.value) return false
        if (_voiceState.value == VoiceState.SPEAKING) {
            // Interrupt Nova while speaking
            tts.stop()
        }

        _voiceState.value = VoiceState.LISTENING
        _voiceError.value = null
        return stt.startListening(scope)
    }

    /**
     * Stop listening and begin transcription.
     * Call from mic button release.
     *
     * @param scope CoroutineScope for transcription.
     * @param onTranscribed Callback with transcribed text.
     */
    fun stopListeningAndTranscribe(
        scope: CoroutineScope,
        onTranscribed: (String) -> Unit
    ) {
        _voiceState.value = VoiceState.TRANSCRIBING

        // Collect the transcription result
        scope.launch {
            stt.transcriptionResult
                .take(1) // Only take one result
                .collect { text ->
                    if (text.isNotBlank()) {
                        onTranscribed(text)
                    } else {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
        }

        stt.stopListening(scope)
    }

    /**
     * Set state to THINKING (LLM is generating).
     */
    fun setThinking() {
        _voiceState.value = VoiceState.THINKING
    }

    /**
     * Speak Nova's response text.
     * @param text The response text to speak.
     * @param scope CoroutineScope for playback.
     */
    fun speakResponse(text: String, scope: CoroutineScope) {
        _voiceState.value = VoiceState.SPEAKING

        // Listen for speech completion
        scope.launch {
            tts.speechComplete
                .take(1)
                .collect {
                    _voiceState.value = VoiceState.IDLE
                }
        }

        tts.speak(text, scope)
    }

    /**
     * Interrupt current speech.
     */
    fun interruptSpeech() {
        if (_voiceState.value == VoiceState.SPEAKING) {
            tts.stop()
            _voiceState.value = VoiceState.IDLE
        }
    }

    /**
     * Stop all voice activities.
     */
    fun stopAll() {
        stt.recorder.stopRecording()
        tts.stop()
        _voiceState.value = if (_isVoiceModeEnabled.value) VoiceState.IDLE else VoiceState.IDLE
    }

    /**
     * Search common device storage locations for a model file.
     */
    private fun findModelFile(modelNames: List<String>): File? {
        val searchDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
            File(Environment.getExternalStorageDirectory(), "Models"),
            File(Environment.getExternalStorageDirectory(), "nova"),
            File(Environment.getExternalStorageDirectory(), "nova/models"),
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            for (name in modelNames) {
                val file = File(dir, name)
                if (file.exists() && file.canRead()) {
                    Log.i(TAG, "Found model: ${file.absolutePath}")
                    return file
                }
            }
        }
        return null
    }

    /**
     * Release all voice resources.
     */
    fun release() {
        stopAll()
        stt.release()
        tts.release()
        _voiceModelsLoaded.value = false
        _isVoiceModeEnabled.value = false
    }
}
