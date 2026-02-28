package com.nova.companion.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Handles microphone recording for Whisper STT.
 *
 * Records 16kHz mono 16-bit PCM audio from the device microphone.
 * Includes Voice Activity Detection (VAD) to auto-stop after silence.
 * Emits amplitude levels for UI visualization.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000 // Whisper expects 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // VAD configuration
        private const val VAD_SILENCE_THRESHOLD = 300f  // RMS amplitude below this = silence
        private const val VAD_SILENCE_DURATION_MS = 3000L // Stop after 3s of silence
        private const val VAD_MIN_SPEECH_MS = 500L // Minimum speech before VAD can trigger

        // Max recording duration (safety limit)
        private const val MAX_RECORDING_MS = 30000L // 30 seconds max
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordingScope: CoroutineScope? = null

    // Accumulated audio samples (normalized float -1.0 to 1.0)
    private val audioSamples = mutableListOf<Float>()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Emits current amplitude level (0.0 - 1.0) for mic animation
    private val _amplitudeLevel = MutableStateFlow(0f)
    val amplitudeLevel: StateFlow<Float> = _amplitudeLevel.asStateFlow()

    // Emits when recording completes (with audio data)
    private val _recordingComplete = MutableSharedFlow<FloatArray>()
    val recordingComplete: SharedFlow<FloatArray> = _recordingComplete.asSharedFlow()

    // Emits when VAD triggers auto-stop
    private val _vadTriggered = MutableSharedFlow<Unit>()
    val vadTriggered: SharedFlow<Unit> = _vadTriggered.asSharedFlow()

    /**
     * Start recording from microphone.
     * Records until stopRecording() is called or VAD detects prolonged silence.
     * @return false if mic permission not granted or AudioRecord init fails.
     */
    fun startRecording(): Boolean {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2 // Double buffer for safety
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            return false
        }

        audioSamples.clear()
        _isRecording.value = true
        _amplitudeLevel.value = 0f

        recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        recordingJob = recordingScope?.launch {
            recordLoop(bufferSize)
        }

        audioRecord?.startRecording()
        Log.i(TAG, "Recording started (16kHz mono)")
        return true
    }

    /**
     * Stop recording and return accumulated audio samples.
     * @return FloatArray of normalized audio samples, or null if not recording.
     */
    fun stopRecording(): FloatArray? {
        if (!_isRecording.value) return null

        _isRecording.value = false
        audioRecord?.stop()
        recordingJob?.cancel()
        recordingScope?.cancel()

        audioRecord?.release()
        audioRecord = null

        _amplitudeLevel.value = 0f

        val result = if (audioSamples.isNotEmpty()) {
            audioSamples.toFloatArray()
        } else null

        Log.i(TAG, "Recording stopped. Samples: ${result?.size ?: 0} (${(result?.size ?: 0) / SAMPLE_RATE}s)")
        return result
    }

    /**
     * Main recording loop. Reads from AudioRecord, accumulates samples,
     * computes amplitude for UI, and runs VAD.
     */
    private suspend fun recordLoop(bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        var silenceStartMs = 0L
        var hasSpeechStarted = false
        val recordingStartMs = System.currentTimeMillis()

        while (_isRecording.value) {
            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1

            if (readCount <= 0) {
                delay(10)
                continue
            }

            // Convert 16-bit PCM to normalized float and accumulate
            val floatChunk = FloatArray(readCount) { i ->
                buffer[i].toFloat() / Short.MAX_VALUE
            }
            synchronized(audioSamples) {
                audioSamples.addAll(floatChunk.toList())
            }

            // Compute RMS amplitude for UI visualization
            val rms = computeRMS(buffer, readCount)
            val normalizedAmplitude = (rms / 8000f).coerceIn(0f, 1f) // Normalize to 0-1
            _amplitudeLevel.value = normalizedAmplitude

            // Voice Activity Detection
            val elapsedMs = System.currentTimeMillis() - recordingStartMs

            if (rms > VAD_SILENCE_THRESHOLD) {
                hasSpeechStarted = true
                silenceStartMs = 0L
            } else if (hasSpeechStarted && elapsedMs > VAD_MIN_SPEECH_MS) {
                if (silenceStartMs == 0L) {
                    silenceStartMs = System.currentTimeMillis()
                }
                val silenceDuration = System.currentTimeMillis() - silenceStartMs
                if (silenceDuration >= VAD_SILENCE_DURATION_MS) {
                    Log.i(TAG, "VAD: ${VAD_SILENCE_DURATION_MS}ms silence detected, auto-stopping")
                    _isRecording.value = false
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                    _amplitudeLevel.value = 0f

                    val result = synchronized(audioSamples) {
                        audioSamples.toFloatArray()
                    }
                    _recordingComplete.emit(result)
                    _vadTriggered.emit(Unit)
                    return
                }
            }

            // Safety: max recording duration
            if (elapsedMs >= MAX_RECORDING_MS) {
                Log.i(TAG, "Max recording duration reached (${MAX_RECORDING_MS}ms)")
                _isRecording.value = false
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                _amplitudeLevel.value = 0f

                val result = synchronized(audioSamples) {
                    audioSamples.toFloatArray()
                }
                _recordingComplete.emit(result)
                return
            }
        }
    }

    /**
     * Compute Root Mean Square amplitude of PCM samples.
     */
    private fun computeRMS(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count).toFloat()
    }

    /**
     * Check if currently recording.
     */
    fun isCurrentlyRecording(): Boolean = _isRecording.value

    /**
     * Release all resources.
     */
    fun release() {
        stopRecording()
        recordingScope?.cancel()
    }
}
