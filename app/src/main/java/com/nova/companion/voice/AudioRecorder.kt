package com.nova.companion.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Handles microphone recording for Whisper STT.
 *
 * Records 16kHz mono 16-bit PCM audio from the device microphone.
 * Includes Voice Activity Detection (VAD) to auto-stop after silence.
 * Emits amplitude levels for UI visualization.
 *
 * Changes from original:
 * - Audio source changed to VOICE_RECOGNITION (better noise handling than MIC)
 * - NoiseSuppressor and AcousticEchoCanceler attached after init (when available)
 * - Adaptive VAD: samples ambient noise for first 500ms, sets threshold to 2× ambient RMS
 * - Gain normalization: running gain factor normalizes audio to ~0.7 of peak
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000 // Whisper expects 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // VAD configuration — used as fallback minimum when adaptive threshold is unavailable
        private const val VAD_SILENCE_THRESHOLD_MIN = 300f  // Absolute floor RMS below which = silence
        private const val VAD_SILENCE_DURATION_MS = 3000L   // Stop after 3s of silence
        private const val VAD_MIN_SPEECH_MS = 500L           // Min speech before VAD can trigger

        // Ambient sampling window (adaptive VAD)
        private const val AMBIENT_SAMPLE_DURATION_MS = 500L  // First 500ms used to measure ambient noise
        private const val AMBIENT_THRESHOLD_MULTIPLIER = 2f  // VAD threshold = 2× ambient RMS

        // Max recording duration (safety limit)
        private const val MAX_RECORDING_MS = 30000L // 30 seconds max

        // Gain normalization
        private const val GAIN_TARGET = 0.7f           // Target normalized peak amplitude
        private const val GAIN_SMOOTH_FACTOR = 0.05f   // How quickly running gain adjusts (0 = instant, 1 = frozen)
        private const val GAIN_MIN = 0.1f              // Clamp gain to prevent extreme amplification on silence
        private const val GAIN_MAX = 10f               // Clamp gain to prevent clipping on quiet signal
    }

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var recordingJob: Job? = null
    private var recordingScope: CoroutineScope? = null

    // Accumulated audio samples (normalized float -1.0 to 1.0, gain-adjusted)
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

    // Running gain factor for normalization (starts at 1.0, adapts over time)
    @Volatile private var runningGain = 1f

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
            // VOICE_RECOGNITION: tuned for speech capture — uses device's pre-processing
            // for far-field speech, provides better frequency response than raw MIC source.
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
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

        // Attach NoiseSuppressor if device supports it
        val sessionId = audioRecord!!.audioSessionId
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.i(TAG, "NoiseSuppressor attached (session $sessionId)")
            } catch (e: Exception) {
                Log.w(TAG, "NoiseSuppressor create failed", e)
                noiseSuppressor = null
            }
        } else {
            Log.d(TAG, "NoiseSuppressor not available on this device")
        }

        // Attach AcousticEchoCanceler if device supports it
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                Log.i(TAG, "AcousticEchoCanceler attached (session $sessionId)")
            } catch (e: Exception) {
                Log.w(TAG, "AcousticEchoCanceler create failed", e)
                echoCanceler = null
            }
        } else {
            Log.d(TAG, "AcousticEchoCanceler not available on this device")
        }

        audioSamples.clear()
        runningGain = 1f
        _isRecording.value = true
        _amplitudeLevel.value = 0f

        recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        recordingJob = recordingScope?.launch {
            recordLoop(bufferSize)
        }

        audioRecord?.startRecording()
        Log.i(TAG, "Recording started (16kHz mono, VOICE_RECOGNITION source)")
        return true
    }

    /**
     * Stop recording and return accumulated audio samples.
     * @return FloatArray of normalized (and gain-adjusted) audio samples, or null if not recording.
     */
    fun stopRecording(): FloatArray? {
        if (!_isRecording.value) return null

        _isRecording.value = false
        audioRecord?.stop()
        recordingJob?.cancel()
        recordingScope?.cancel()

        releaseAudioEffects()
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
     * Main recording loop. Reads from AudioRecord, accumulates gain-normalized samples,
     * computes amplitude for UI, and runs adaptive VAD.
     *
     * Phase 1 (first AMBIENT_SAMPLE_DURATION_MS): Sample ambient noise to set VAD threshold.
     * Phase 2 (after ambient phase): Normal VAD + gain normalization in effect.
     */
    private suspend fun recordLoop(bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        var silenceStartMs = 0L
        var hasSpeechStarted = false
        val recordingStartMs = System.currentTimeMillis()

        // Adaptive VAD: accumulated ambient RMS samples during first AMBIENT_SAMPLE_DURATION_MS
        val ambientRmsSamples = mutableListOf<Float>()
        var ambientPhaseComplete = false
        var adaptiveVadThreshold = VAD_SILENCE_THRESHOLD_MIN

        while (_isRecording.value) {
            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1

            if (readCount <= 0) {
                delay(10)
                continue
            }

            val elapsedMs = System.currentTimeMillis() - recordingStartMs

            // ── Compute raw RMS ──────────────────────────────────────
            val rms = computeRMS(buffer, readCount)

            // ── Adaptive VAD threshold (ambient phase) ───────────────
            if (!ambientPhaseComplete) {
                if (elapsedMs < AMBIENT_SAMPLE_DURATION_MS) {
                    ambientRmsSamples.add(rms)
                } else {
                    // Ambient phase done — compute threshold
                    ambientPhaseComplete = true
                    if (ambientRmsSamples.isNotEmpty()) {
                        val ambientMean = ambientRmsSamples.average().toFloat()
                        val candidateThreshold = ambientMean * AMBIENT_THRESHOLD_MULTIPLIER
                        // Threshold is max(candidate, minimum floor) to avoid zeroing out
                        adaptiveVadThreshold = maxOf(candidateThreshold, VAD_SILENCE_THRESHOLD_MIN)
                        Log.i(TAG, "Adaptive VAD threshold set: $adaptiveVadThreshold " +
                                "(ambient RMS mean=${ambientMean}, multiplier=$AMBIENT_THRESHOLD_MULTIPLIER)")
                    }
                }
            }

            // ── Gain normalization ────────────────────────────────────
            // Compute peak amplitude of this chunk (0–32767 for 16-bit)
            var peakAmplitude = 0f
            for (i in 0 until readCount) {
                val v = abs(buffer[i].toFloat())
                if (v > peakAmplitude) peakAmplitude = v
            }

            // Target peak = GAIN_TARGET * Short.MAX_VALUE
            val targetPeak = GAIN_TARGET * Short.MAX_VALUE
            val desiredGain = if (peakAmplitude > 0f) (targetPeak / peakAmplitude) else 1f

            // Smooth gain adjustment to avoid sudden jumps
            runningGain = runningGain + GAIN_SMOOTH_FACTOR * (desiredGain - runningGain)
            runningGain = runningGain.coerceIn(GAIN_MIN, GAIN_MAX)

            // ── Convert to float and apply gain ──────────────────────
            val floatChunk = FloatArray(readCount) { i ->
                val sample = buffer[i].toFloat() / Short.MAX_VALUE
                (sample * runningGain).coerceIn(-1f, 1f) // clamp after gain to avoid float overflow
            }
            synchronized(audioSamples) {
                audioSamples.addAll(floatChunk.toList())
            }

            // ── UI amplitude (normalized 0–1, using raw RMS before gain) ──
            val normalizedAmplitude = (rms / 8000f).coerceIn(0f, 1f)
            _amplitudeLevel.value = normalizedAmplitude

            // ── Voice Activity Detection ──────────────────────────────
            if (rms > adaptiveVadThreshold) {
                hasSpeechStarted = true
                silenceStartMs = 0L
            } else if (hasSpeechStarted && elapsedMs > VAD_MIN_SPEECH_MS) {
                if (silenceStartMs == 0L) {
                    silenceStartMs = System.currentTimeMillis()
                }
                val silenceDuration = System.currentTimeMillis() - silenceStartMs
                if (silenceDuration >= VAD_SILENCE_DURATION_MS) {
                    Log.i(TAG, "VAD: ${VAD_SILENCE_DURATION_MS}ms silence detected, auto-stopping " +
                            "(threshold=$adaptiveVadThreshold, rms=$rms)")
                    _isRecording.value = false
                    audioRecord?.stop()
                    releaseAudioEffects()
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
                releaseAudioEffects()
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
     * Release NoiseSuppressor and AcousticEchoCanceler if attached.
     */
    private fun releaseAudioEffects() {
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing NoiseSuppressor", e)
        }
        try {
            echoCanceler?.release()
            echoCanceler = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AcousticEchoCanceler", e)
        }
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
        releaseAudioEffects()
        recordingScope?.cancel()
    }
}
