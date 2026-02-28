package com.nova.companion.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level Text-to-Speech engine using Piper TTS.
 *
 * Synthesizes speech locally using an ONNX voice model.
 * Audio is played through Android AudioTrack for low-latency output.
 * Supports streaming synthesis for faster time-to-first-audio.
 */
class PiperTTS {

    companion object {
        private const val TAG = "PiperTTS"
    }

    private val piper = PiperJNI()
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var playbackScope: CoroutineScope? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Emits amplitude for sound wave visualization (0.0 - 1.0)
    private val _playbackAmplitude = MutableStateFlow(0f)
    val playbackAmplitude: StateFlow<Float> = _playbackAmplitude.asStateFlow()

    // Emits when speech finishes
    private val _speechComplete = MutableSharedFlow<Unit>()
    val speechComplete: SharedFlow<Unit> = _speechComplete.asSharedFlow()

    // Error events
    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    /**
     * Initialize Piper with voice model.
     * @param modelPath Path to the ONNX voice model file.
     * @param configPath Path to the model's JSON config file.
     * @return true if initialized successfully.
     */
    suspend fun loadModel(modelPath: String, configPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Loading Piper voice model: $modelPath")
                val success = piper.initialize(modelPath, configPath)
                _isModelLoaded.value = success
                if (success) {
                    Log.i(TAG, "Piper loaded (sample rate: ${piper.getSampleRate()}Hz)")
                } else {
                    Log.e(TAG, "Failed to initialize Piper")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Piper model", e)
                _isModelLoaded.value = false
                false
            }
        }

    /**
     * Speak the given text.
     * Synthesizes audio and plays it through the device speaker.
     * Uses streaming synthesis for lower latency.
     *
     * @param text The text to speak aloud.
     * @param scope CoroutineScope for async playback.
     */
    fun speak(text: String, scope: CoroutineScope) {
        if (!_isModelLoaded.value) {
            Log.e(TAG, "Piper model not loaded")
            return
        }

        if (text.isBlank()) return

        // Stop any current speech first
        stop()

        _isSpeaking.value = true

        playbackScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        playbackJob = playbackScope?.launch {
            try {
                Log.i(TAG, "Synthesizing: \"$text\"")

                val sampleRate = piper.getSampleRate()

                // Initialize AudioTrack for playback
                val audioTrackInstance = createAudioTrack(sampleRate)
                audioTrack = audioTrackInstance
                audioTrackInstance.play()

                // Use streaming synthesis for lower latency
                val callback = object : PiperAudioCallback {
                    override fun onAudioChunk(
                        samples: ShortArray,
                        sampleRate: Int,
                        isLast: Boolean
                    ) {
                        if (!_isSpeaking.value) return

                        // Write audio to AudioTrack
                        audioTrackInstance.write(samples, 0, samples.size)

                        // Compute amplitude for visualization
                        val amplitude = computeAmplitude(samples)
                        _playbackAmplitude.value = amplitude

                        if (isLast) {
                            Log.d(TAG, "Last audio chunk received")
                        }
                    }
                }

                piper.synthesizeStreaming(text, callback)

                // Wait for AudioTrack to finish playing buffered audio
                // (AudioTrack.write is blocking, so when synthesizeStreaming returns,
                // all audio has been written)
                delay(200) // Small buffer for final audio drain
                audioTrackInstance.stop()
                audioTrackInstance.release()
                audioTrack = null

                _isSpeaking.value = false
                _playbackAmplitude.value = 0f

                withContext(Dispatchers.Main) {
                    _speechComplete.emit(Unit)
                }

                Log.i(TAG, "Speech playback complete")

            } catch (e: CancellationException) {
                Log.i(TAG, "Speech cancelled")
                cleanupAudioTrack()
            } catch (e: Exception) {
                Log.e(TAG, "Speech synthesis error", e)
                cleanupAudioTrack()
                withContext(Dispatchers.Main) {
                    _error.emit("Speech failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Speak with pre-synthesized audio (for replay).
     * @param audioData Raw PCM samples to play.
     * @param sampleRate Sample rate of the audio.
     */
    fun playAudio(audioData: ShortArray, sampleRate: Int, scope: CoroutineScope) {
        stop()
        _isSpeaking.value = true

        playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        playbackJob = playbackScope?.launch {
            try {
                val track = createAudioTrack(sampleRate)
                audioTrack = track
                track.play()
                track.write(audioData, 0, audioData.size)
                delay(200)
                track.stop()
                track.release()
                audioTrack = null
                _isSpeaking.value = false
                _playbackAmplitude.value = 0f
                _speechComplete.emit(Unit)
            } catch (e: Exception) {
                cleanupAudioTrack()
            }
        }
    }

    /**
     * Stop current speech playback immediately.
     */
    fun stop() {
        _isSpeaking.value = false
        _playbackAmplitude.value = 0f
        playbackJob?.cancel()
        playbackScope?.cancel()
        cleanupAudioTrack()
    }

    /**
     * Check if currently speaking.
     */
    fun isSpeaking(): Boolean = _isSpeaking.value

    /**
     * Create an AudioTrack configured for Piper's output format.
     */
    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Clean up AudioTrack resources.
     */
    private fun cleanupAudioTrack() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    /**
     * Compute normalized amplitude from PCM samples for visualization.
     */
    private fun computeAmplitude(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            val s = sample.toDouble()
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / samples.size)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        if (_isModelLoaded.value) {
            piper.release()
            _isModelLoaded.value = false
        }
    }
}
