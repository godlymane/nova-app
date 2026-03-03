package com.nova.companion.biohack

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * RetinalStrobeService — Phase 15: Bio-Acoustic Overdrive Protocol
 *
 * Uses the device's rear camera LED flash to strobe at exactly 15Hz.
 * 15Hz is the cross-spectral frequency that triggers cortisol release
 * via the retino-hypothalamic tract — inducing acute alertness.
 *
 * WARNING: Do not use on photosensitive individuals.
 * Only activate when severe cognitive shock is required to break
 * a discipline failure or loss-of-focus state.
 */
object RetinalStrobeService {
    private const val TAG = "RetinalStrobe"
    private const val STROBE_HZ = 15     // 15Hz = retinal cortisol trigger

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Start 15Hz LED strobe — cortisol shock protocol */
    fun start(context: Context, durationMs: Long = 3000L) {
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = camManager.cameraIdList.firstOrNull { id ->
            camManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            camManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run {
            Log.w(TAG, "No rear flash camera found")
            return
        }

        job?.cancel()
        job = scope.launch {
            Log.w(TAG, "RETINAL STROBE STARTED — 15Hz cortisol induction for ${durationMs}ms")
            val halfPeriodMs = 1000L / STROBE_HZ / 2     // 33ms on / 33ms off

            val startTime = System.currentTimeMillis()
            var flashOn = false

            try {
                while (isActive && (System.currentTimeMillis() - startTime) < durationMs) {
                    flashOn = !flashOn
                    camManager.setTorchMode(cameraId, flashOn)
                    delay(halfPeriodMs)
                }
            } finally {
                // Always ensure torch is off when done
                try { camManager.setTorchMode(cameraId, false) } catch (_: Exception) {}
                Log.i(TAG, "Retinal strobe stopped")
            }
        }
    }

    /** Stop strobe immediately */
    fun stop(context: Context) {
        job?.cancel()
        job = null
        try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            camManager.cameraIdList.forEach { id ->
                try { camManager.setTorchMode(id, false) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping strobe", e)
        }
    }

    val isRunning get() = job?.isActive == true
}
