package com.nova.companion.biohack.hypnosis

import android.content.Context
import android.util.Log
import com.nova.companion.cloud.ElevenLabsTTS
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * HypnosisVoiceEngine — TTS wrapper for narrating hypnosis scripts.
 *
 * Wraps ElevenLabsTTS with:
 * - Silent mode toggle (skips TTS, exposes script text for on-screen display)
 * - Sequential queuing (waits for current line to finish before speaking next)
 * - Pause/resume support
 */
class HypnosisVoiceEngine {
    companion object {
        private const val TAG = "HypnosisVoice"
    }

    var isSilentMode: Boolean = false
    private val speakMutex = Mutex()
    private var isSpeaking = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Speak a script line via ElevenLabs TTS.
     * In silent mode, calls onScriptText immediately and skips TTS.
     *
     * @param text Script line to speak
     * @param context Android context
     * @param onScriptText Called with the text being spoken (for on-screen display)
     * @param onComplete Called when speech finishes (or immediately in silent mode)
     */
    suspend fun speakScript(
        text: String,
        context: Context,
        onScriptText: (String) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        speakMutex.withLock {
            onScriptText(text)

            if (isSilentMode) {
                // In silent mode, show text for a read-time duration then clear
                delay(calculateReadTimeMs(text))
                onScriptText("")
                onComplete()
                return
            }

            isSpeaking = true
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    scope.launch {
                        ElevenLabsTTS.speak(
                            text = text,
                            context = context,
                            onComplete = {
                                isSpeaking = false
                                onScriptText("")
                                if (cont.isActive) cont.resume(Unit)
                                onComplete()
                            },
                            onError = { error ->
                                Log.w(TAG, "TTS error: $error, falling back to display-only")
                                isSpeaking = false
                                onScriptText("")
                                if (cont.isActive) cont.resume(Unit)
                                onComplete()
                            }
                        )
                    }
                }
            } catch (e: CancellationException) {
                isSpeaking = false
                throw e
            }
        }
    }

    fun stopSpeaking() {
        ElevenLabsTTS.stopPlayback()
        isSpeaking = false
    }

    fun cleanup() {
        stopSpeaking()
        scope.cancel()
    }

    /**
     * Calculate how long to display text in silent mode.
     * ~200ms per word for comfortable reading pace.
     */
    private fun calculateReadTimeMs(text: String): Long {
        val wordCount = text.split("\\s+".toRegex()).size
        return (wordCount * 200L + 1500L).coerceIn(2000L, 8000L)
    }
}
