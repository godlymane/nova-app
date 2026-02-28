package com.nova.companion.voice

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * High-level Speech-to-Text engine using whisper.cpp.
 *
 * Manages the AudioRecorder and WhisperJNI to provide a simple API:
 *   startListening() → records audio → auto-stops on silence → transcribes → returns text
 *
 * All processing is local. No internet required.
 */
class WhisperSTT {

    companion object {
        private const val TAG = "WhisperSTT"
    }

    private val whisper = WhisperJNI()
    val recorder = AudioRecorder()

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    // Partial transcription text (updated live during transcription)
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    // Final transcription result
    private val _transcriptionResult = MutableSharedFlow<String>()
    val transcriptionResult: SharedFlow<String> = _transcriptionResult.asSharedFlow()

    // Error events
    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    /**
     * Load the Whisper model.
     * @param modelPath Path to whisper model file (e.g., ggml-tiny.bin).
     * @return true if loaded successfully.
     */
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading Whisper model: $modelPath")
            val success = whisper.initContext(modelPath)
            _isModelLoaded.value = success
            if (success) {
                Log.i(TAG, "Whisper model loaded (version: ${whisper.getVersion()})")
            } else {
                Log.e(TAG, "Failed to load Whisper model")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Whisper model", e)
            _isModelLoaded.value = false
            false
        }
    }

    /**
     * Start listening: begin recording from microphone.
     * Will auto-stop after 3s of silence (VAD) or when stopListening() is called.
     * After stopping, automatically transcribes the audio.
     *
     * @param scope CoroutineScope to launch transcription in.
     * @return false if model not loaded or recording fails.
     */
    fun startListening(scope: CoroutineScope): Boolean {
        if (!_isModelLoaded.value) {
            Log.e(TAG, "Whisper model not loaded")
            return false
        }

        _partialText.value = ""

        // Listen for VAD auto-stop → auto-transcribe
        scope.launch {
            recorder.recordingComplete.collect { samples ->
                transcribeAudio(samples)
            }
        }

        val started = recorder.startRecording()
        if (!started) {
            Log.e(TAG, "Failed to start recording")
            scope.launch { _error.emit("Failed to start microphone recording") }
        }
        return started
    }

    /**
     * Stop listening manually and transcribe the recorded audio.
     * @param scope CoroutineScope to run transcription in.
     */
    fun stopListening(scope: CoroutineScope) {
        val samples = recorder.stopRecording()
        if (samples != null && samples.isNotEmpty()) {
            scope.launch {
                transcribeAudio(samples)
            }
        } else {
            scope.launch {
                _transcriptionResult.emit("")
            }
        }
    }

    /**
     * Transcribe audio samples using Whisper.
     * Updates partialText during processing and emits final result.
     */
    private suspend fun transcribeAudio(samples: FloatArray) {
        if (samples.isEmpty()) {
            _transcriptionResult.emit("")
            return
        }

        _isTranscribing.value = true
        _partialText.value = ""

        try {
            withContext(Dispatchers.Default) {
                val durationSec = samples.size.toFloat() / AudioRecorder.SAMPLE_RATE
                Log.i(TAG, "Transcribing ${durationSec}s of audio (${samples.size} samples)")

                val callback = object : WhisperSegmentCallback {
                    override fun onSegment(startMs: Long, endMs: Long, text: String) {
                        val current = _partialText.value
                        _partialText.value = (current + text).trim()
                        Log.d(TAG, "Segment [${startMs}-${endMs}ms]: $text")
                    }
                }

                val result = whisper.transcribeWithCallback(
                    samples = samples,
                    numSamples = samples.size,
                    language = "en",
                    callback = callback
                )

                val trimmed = result.trim()
                Log.i(TAG, "Transcription complete: \"$trimmed\"")

                _partialText.value = trimmed
                _transcriptionResult.emit(trimmed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            _error.emit("Transcription failed: ${e.message}")
        } finally {
            _isTranscribing.value = false
        }
    }

    /**
     * Check if Whisper is ready for transcription.
     */
    fun isReady(): Boolean = _isModelLoaded.value && !_isTranscribing.value

    /**
     * Release all resources.
     */
    fun release() {
        recorder.release()
        if (_isModelLoaded.value) {
            whisper.freeContext()
            _isModelLoaded.value = false
        }
    }
}
