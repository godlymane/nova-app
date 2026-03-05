package com.nova.companion.biohack.hypnosis

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * HypnosisHapticEngine — Variable-rate haptic pulse generator for hypnosis sessions.
 *
 * Generates rhythmic vibration pulses at a configurable frequency (Hz).
 * Lower frequencies (1-2Hz) for deep relaxation, higher (4-6Hz) for alertness.
 * Supports smooth transitions between pulse rates at phase boundaries.
 */
class HypnosisHapticEngine {
    companion object {
        private const val TAG = "HypnosisHaptic"
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var currentPulseHz: Float = 4f
    @Volatile private var amplitude: Int = 60  // 0-255

    val isRunning get() = job?.isActive == true

    /**
     * Start haptic pulses at the given frequency.
     * @param context Android context for vibrator access
     * @param pulseHz Pulse frequency in Hz (e.g. 4.0 for 4 pulses/second)
     * @param amp Vibration amplitude 0-255
     */
    fun start(context: Context, pulseHz: Float = 4f, amp: Int = 60) {
        currentPulseHz = pulseHz
        amplitude = amp.coerceIn(0, 255)
        job?.cancel()

        job = scope.launch {
            Log.i(TAG, "Haptic started at ${pulseHz}Hz")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModern(context)
            } else {
                startLegacy(context)
            }
        }
    }

    private suspend fun CoroutineScope.startModern(context: Context) {
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        val vibrator = vm.defaultVibrator
        if (!vibrator.hasVibrator()) return

        while (isActive) {
            val periodMs = (1000f / currentPulseHz).toLong().coerceAtLeast(100)
            val onMs = periodMs / 2
            val offMs = periodMs - onMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(onMs, amplitude)
                )
            }
            delay(periodMs)
        }
        vibrator.cancel()
    }

    @Suppress("DEPRECATION")
    private suspend fun CoroutineScope.startLegacy(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (!vibrator.hasVibrator()) return

        while (isActive) {
            val periodMs = (1000f / currentPulseHz).toLong().coerceAtLeast(100)
            val onMs = periodMs / 2

            vibrator.vibrate(onMs)
            delay(periodMs)
        }
        vibrator.cancel()
    }

    /**
     * Transition to a new pulse frequency. Takes effect on next pulse cycle.
     */
    fun transitionTo(pulseHz: Float) {
        currentPulseHz = pulseHz.coerceIn(0.25f, 20f)
        Log.i(TAG, "Haptic transitioning to ${currentPulseHz}Hz")
    }

    fun stop(context: Context) {
        job?.cancel()
        job = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping haptic", e)
        }
        Log.i(TAG, "Haptic stopped")
    }
}
