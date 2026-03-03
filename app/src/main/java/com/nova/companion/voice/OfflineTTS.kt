package com.nova.companion.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Offline text-to-speech using Android's built-in TTS engine.
 * Used as a fallback when ElevenLabs is unavailable (no internet).
 */
object OfflineTTS {

    private const val TAG = "OfflineTTS"

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    fun init(context: Context, onReady: () -> Unit = {}) {
        if (isInitialized) {
            onReady()
            return
        }

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (isInitialized) {
                    tts?.setPitch(1.0f)
                    tts?.setSpeechRate(1.0f)
                    Log.i(TAG, "TTS engine initialized")
                    onReady()
                } else {
                    Log.e(TAG, "Language not supported: $result")
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    /**
     * Speak text and suspend until playback completes.
     */
    suspend fun speak(text: String): Boolean = suspendCancellableCoroutine { cont ->
        val engine = tts
        if (engine == null || !isInitialized) {
            Log.e(TAG, "TTS not initialized")
            if (cont.isActive) cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val utteranceId = UUID.randomUUID().toString()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "TTS speaking")
            }

            override fun onDone(id: String?) {
                Log.d(TAG, "TTS done")
                if (cont.isActive) cont.resume(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                Log.e(TAG, "TTS error")
                if (cont.isActive) cont.resume(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error code: $errorCode")
                if (cont.isActive) cont.resume(false)
            }
        })

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        cont.invokeOnCancellation {
            engine.stop()
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
