package com.nova.companion.biohack.hypnosis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * HypnosisAudioEngine — Dynamic binaural beat synthesizer for hypnosis sessions.
 *
 * Generates stereo PCM audio with:
 * - Carrier frequency in both ears (base tone)
 * - Binaural beat: left ear = carrier, right ear = carrier + binauralHz
 *   The brain perceives the difference as a pulsing frequency that entrains neural oscillations.
 *
 * Supports smooth frequency transitions between hypnosis phases to avoid jarring shifts.
 */
class HypnosisAudioEngine {
    companion object {
        private const val TAG = "HypnosisAudio"
        private const val SAMPLE_RATE = 44100
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current frequencies (volatile for cross-thread reads from the synthesis loop)
    @Volatile private var currentCarrierHz: Float = 200f
    @Volatile private var currentBinauralHz: Float = 10f
    @Volatile private var intensity: Float = 0.5f

    // Transition targets
    @Volatile private var targetCarrierHz: Float = 200f
    @Volatile private var targetBinauralHz: Float = 10f
    private var transitionSamplesRemaining = 0
    private var transitionSamplesTotal = 0

    val isRunning get() = job?.isActive == true

    /**
     * Start audio synthesis with given frequencies.
     * @param binauralHz The binaural beat frequency (e.g. 10Hz for alpha)
     * @param carrierHz The base carrier frequency (e.g. 200Hz)
     * @param volume Overall volume 0-1
     */
    fun start(binauralHz: Float = 10f, carrierHz: Float = 200f, volume: Float = 0.5f) {
        currentBinauralHz = binauralHz
        currentCarrierHz = carrierHz
        targetBinauralHz = binauralHz
        targetCarrierHz = carrierHz
        intensity = volume.coerceIn(0f, 1f)

        job?.cancel()
        job = scope.launch {
            Log.i(TAG, "Audio started — carrier=${carrierHz}Hz, binaural=${binauralHz}Hz")
            val track = buildStereoTrack()
            val bufferSamples = SAMPLE_RATE / 8  // 125ms chunks
            val buffer = ShortArray(bufferSamples * 2)  // stereo interleaved

            // Amplitude ramp-in over first 2 seconds to avoid pops
            var rampSamples = SAMPLE_RATE * 2
            var frame = 0L

            try {
                track.play()
                while (isActive) {
                    for (i in 0 until bufferSamples) {
                        // Smooth frequency transitions
                        if (transitionSamplesRemaining > 0) {
                            val progress = 1f - (transitionSamplesRemaining.toFloat() / transitionSamplesTotal)
                            currentCarrierHz = lerp(currentCarrierHz, targetCarrierHz, progress)
                            currentBinauralHz = lerp(currentBinauralHz, targetBinauralHz, progress)
                            transitionSamplesRemaining--
                            if (transitionSamplesRemaining == 0) {
                                currentCarrierHz = targetCarrierHz
                                currentBinauralHz = targetBinauralHz
                            }
                        }

                        val t = (frame + i).toDouble() / SAMPLE_RATE

                        // Ramp-in envelope
                        val rampFactor = if (rampSamples > 0) {
                            rampSamples--
                            1f - (rampSamples.toFloat() / (SAMPLE_RATE * 2))
                        } else 1f

                        val amp = (Short.MAX_VALUE * intensity * rampFactor * 0.5f).toInt()

                        // Left ear: carrier frequency
                        val left = sin(2 * PI * currentCarrierHz * t)

                        // Right ear: carrier + binaural offset (creates the beat)
                        val right = sin(2 * PI * (currentCarrierHz + currentBinauralHz) * t)

                        buffer[i * 2] = (left * amp).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        buffer[i * 2 + 1] = (right * amp).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    track.write(buffer, 0, buffer.size)
                    frame += bufferSamples
                }
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
                Log.i(TAG, "Audio stopped")
            }
        }
    }

    /**
     * Smoothly transition to new frequencies over the given duration.
     */
    fun transitionTo(binauralHz: Float, carrierHz: Float, durationMs: Long = 3000) {
        targetBinauralHz = binauralHz
        targetCarrierHz = carrierHz
        transitionSamplesTotal = (SAMPLE_RATE * durationMs / 1000).toInt()
        transitionSamplesRemaining = transitionSamplesTotal
        Log.i(TAG, "Transitioning to carrier=${carrierHz}Hz, binaural=${binauralHz}Hz over ${durationMs}ms")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float {
        return from + (to - from) * fraction.coerceIn(0f, 1f)
    }

    private fun buildStereoTrack(): AudioTrack {
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }
}
