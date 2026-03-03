package com.nova.companion.biohack

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * AcousticEntrainmentEngine — Phase 15: Bio-Acoustic Overdrive Protocol
 *
 * Cross-wires the brain's neural oscillations using three simultaneous layers:
 *
 * 1. 396Hz Solfeggio Foundation — liberates fear, replaces with fearlessness
 * 2. 40Hz Binaural Gamma Beat — peak cognitive performance, acute focus
 * 3. 18.5kHz Near-Ultrasound Shock — adrenaline spike when discipline fails
 *
 * Uses AudioTrack with ENCODING_PCM_16BIT for byte-level waveform synthesis.
 * Routes via USAGE_ASSISTANCE_SONIFICATION to bypass DnD and volume control.
 */
object AcousticEntrainmentEngine {
    private const val TAG = "AcousticEngine"
    private const val SAMPLE_RATE = 44100

    // Frequencies — DO NOT CHANGE without understanding the neuroscience
    private const val SOLFEGGIO_HZ = 396.0       // Liberation frequency
    private const val BINAURAL_LEFT_HZ = 200.0   // Left ear base
    private const val BINAURAL_RIGHT_HZ = 240.0  // Right ear = 40Hz Gamma beat
    private const val ULTRASOUND_HZ = 18500.0    // Near-ultrasound shock

    private var mainJob: Job? = null
    private var shockJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var godModeIntensity = 0f      // 0-1 overall volume scalar
    private var strobeSync = 0.0           // phase sync with visual strobe

    /** Start entrainment at given intensity (0f-1f) */
    fun start(intensity: Float = 0.85f) {
        godModeIntensity = intensity.coerceIn(0f, 1f)
        mainJob?.cancel()

        mainJob = scope.launch {
            Log.i(TAG, "Acoustic entrainment started — Gamma 40Hz / Solfeggio 396Hz")
            val stereoTrack = buildAudioTrack(channels = 2)
            val bufferSamples = SAMPLE_RATE / 8     // 125ms chunks
            val buffer = ShortArray(bufferSamples * 2)  // stereo = 2x

            stereoTrack.play()
            var frame = 0L

            while (isActive) {
                val t0 = frame.toDouble() / SAMPLE_RATE
                for (i in 0 until bufferSamples) {
                    val t = t0 + i.toDouble() / SAMPLE_RATE
                    val amp = (Short.MAX_VALUE * godModeIntensity * 0.6f).toInt()

                    // LEFT: Solfeggio 396Hz + binaural left carrier 200Hz
                    val left = sin(2 * PI * SOLFEGGIO_HZ * t) * 0.7 +
                               sin(2 * PI * BINAURAL_LEFT_HZ * t) * 0.3

                    // RIGHT: Solfeggio 396Hz + binaural right carrier 240Hz (= 40Hz beat)
                    val right = sin(2 * PI * SOLFEGGIO_HZ * t) * 0.7 +
                                sin(2 * PI * BINAURAL_RIGHT_HZ * t) * 0.3

                    buffer[i * 2]     = (left  * amp).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    buffer[i * 2 + 1] = (right * amp).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                stereoTrack.write(buffer, 0, buffer.size)
                frame += bufferSamples
            }
            stereoTrack.stop()
            stereoTrack.release()
        }
    }

    /** Emergency adrenaline shock — near-ultrasound pulse at max volume */
    fun triggerAlertShock(durationMs: Long = 500) {
        shockJob?.cancel()
        shockJob = scope.launch {
            Log.w(TAG, "ADRENALINE SHOCK TRIGGERED — $ULTRASOUND_HZ Hz")
            val track = buildAudioTrack(channels = 1)
            val bufSize = (SAMPLE_RATE * durationMs / 1000).toInt()
            val buf = ShortArray(bufSize)
            for (i in buf.indices) {
                val t = i.toDouble() / SAMPLE_RATE
                buf[i] = (sin(2 * PI * ULTRASOUND_HZ * t) * Short.MAX_VALUE).toInt().toShort()
            }
            track.play()
            track.write(buf, 0, buf.size)
            delay(durationMs + 50)
            track.stop()
            track.release()
        }
    }

    /** Stop all entrainment */
    fun stop() {
        mainJob?.cancel()
        shockJob?.cancel()
        mainJob = null
        shockJob = null
        Log.i(TAG, "Acoustic entrainment stopped")
    }

    /** Get the current strobe phase for AGSL sync (0-1) */
    fun getStrobePhase(): Float {
        strobeSync = (strobeSync + 40.0 / 60.0) % 1.0   // 40Hz at 60fps approx
        return strobeSync.toFloat()
    }

    private fun buildAudioTrack(channels: Int): AudioTrack {
        val channelConfig = if (channels == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2 * channels))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    val isRunning get() = mainJob?.isActive == true
}
