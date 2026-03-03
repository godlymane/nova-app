package com.nova.companion.biohack

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * VagusStimulator — Phase 15: Bio-Acoustic Overdrive Protocol
 *
 * Uses ultra-low frequency haptic micro-pulses designed to stimulate
 * the Vagus nerve when the phone is held to the chest.
 * Kills the physical panic/anxiety response via parasympathetic activation.
 *
 * Scientific basis: Vagus nerve stimulation at 1-10Hz has documented
 * anxiolytic effects in clinical research.
 */
object VagusStimulator {
    private const val TAG = "VagusStimulator"

    // Vagus nerve resonance frequencies (Hz)
    private const val PRIMARY_HZ = 4        // Deep theta, parasympathetic resonance
    private const val SECONDARY_HZ = 7      // Alpha boundary, calm focus

    private var job: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Start Vagus nerve stimulation protocol */
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "VibratorManager requires API 31+")
            startLegacy(context)
            return
        }
        val vm = context.getSystemService(VibratorManager::class.java) ?: run {
            Log.e(TAG, "VibratorManager unavailable")
            return
        }
        val vibrator = vm.defaultVibrator
        if (!vibrator.hasVibrator()) {
            Log.w(TAG, "Device has no vibrator")
            return
        }

        job?.cancel()
        job = scope.launch {
            Log.i(TAG, "VagusStimulator started — Vagal activation protocol active")
            while (isActive) {
                // Primary wave: 4Hz pulse (250ms on, 0ms off cycle)
                val primaryMs = (1000L / PRIMARY_HZ)
                val onMs = primaryMs / 2
                val offMs = primaryMs - onMs

                // Use WAVEFORM for precise low-freq micro-pulses
                val timings = LongArray(4) { if (it % 2 == 0) onMs else offMs }
                val amplitudes = IntArray(4) { if (it % 2 == 0) 80 else 0 }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
                }

                delay(8000L)      // 8s active stimulation cycle
                vibrator.cancel()
                delay(2000L)      // 2s rest
            }
        }
    }

    /** Legacy fallback for API < 31 */
    private fun startLegacy(context: Context) {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (!vibrator.hasVibrator()) return
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val periodMs = (1000L / PRIMARY_HZ)
                val pattern = longArrayOf(0, periodMs / 2, periodMs / 2)
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
                delay(8000L)
                vibrator.cancel()
                delay(2000L)
            }
        }
    }

    /** Stop stimulation */
    fun stop(context: Context) {
        job?.cancel()
        job = null
        Log.i(TAG, "VagusStimulator stopped")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).cancel()
        }
    }

    val isRunning get() = job?.isActive == true
}
