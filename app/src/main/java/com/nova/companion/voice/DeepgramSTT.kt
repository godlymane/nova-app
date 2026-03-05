package com.nova.companion.voice

import android.content.Context
import android.util.Log
import com.nova.companion.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Deepgram-powered STT — cloud-based, far more accurate than local Whisper.
 * Supports Indian English (en-IN), contact name biasing via keywords param.
 * Drop-in replacement for WhisperSTT — same StateFlow interface.
 *
 * Usage:
 *   val stt = DeepgramSTT(context)
 *   stt.startListening(scope)
 *   // speak...
 *   stt.stopListening(scope)
 *   stt.transcriptionResult.collect { text -> ... }
 */
class DeepgramSTT(private val context: Context) {

    companion object {
        private const val TAG = "DeepgramSTT"
        private const val API_URL = "https://api.deepgram.com/v1/listen"
        private const val MODEL = "nova-2"
        private const val LANGUAGE = "en-IN"
    }

    val recorder = AudioRecorder()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _transcriptionResult = MutableSharedFlow<String>()
    val transcriptionResult: SharedFlow<String> = _transcriptionResult.asSharedFlow()

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    // Contact names injected here for keyword biasing (fixes "Raul" → "Rahul")
    var contactKeywords: List<String> = emptyList()

    fun isReady(): Boolean = !_isTranscribing.value &&
            BuildConfig.DEEPGRAM_API_KEY.isNotBlank()

    fun startListening(scope: CoroutineScope): Boolean {
        _partialText.value = ""

        scope.launch {
            recorder.recordingComplete.collect { samples ->
                transcribeAudio(samples)
            }
        }

        val started = recorder.startRecording()
        if (!started) {
            Log.e(TAG, "Failed to start recording")
            scope.launch { _error.emit("Failed to start microphone") }
        }
        return started
    }

    fun stopListening(scope: CoroutineScope) {
        val samples = recorder.stopRecording()
        if (samples != null && samples.isNotEmpty()) {
            scope.launch { transcribeAudio(samples) }
        } else {
            scope.launch { _transcriptionResult.emit("") }
        }
    }

    private suspend fun transcribeAudio(samples: FloatArray) {
        if (samples.isEmpty()) {
            _transcriptionResult.emit("")
            return
        }

        _isTranscribing.value = true
        _partialText.value = "..."

        try {
            val result = withContext(Dispatchers.IO) {
                sendToDeepgram(samples)
            }
            val trimmed = result.trim()
            Log.i(TAG, "Deepgram transcription: \"$trimmed\"")
            _partialText.value = trimmed
            _transcriptionResult.emit(trimmed)
        } catch (e: Exception) {
            Log.e(TAG, "Deepgram error, falling back to empty", e)
            _error.emit("STT failed: ${e.message}")
            _transcriptionResult.emit("")
        } finally {
            _isTranscribing.value = false
        }
    }

    private fun sendToDeepgram(samples: FloatArray): String {
        // Convert float PCM → 16-bit PCM WAV bytes
        val wavBytes = floatsToWav(samples)

        // Build URL with params
        val keywords = contactKeywords
            .filter { it.isNotBlank() }
            .take(50) // Deepgram supports up to 50 keywords
            .joinToString("&") { "keywords=${it.trim()}" }

        val urlStr = buildString {
            append(API_URL)
            append("?model=$MODEL&language=$LANGUAGE&smart_format=true&punctuate=true&diarize=false")
            if (keywords.isNotEmpty()) append("&$keywords")
        }

        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Token ${BuildConfig.DEEPGRAM_API_KEY}")
            conn.setRequestProperty("Content-Type", "audio/wav")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            DataOutputStream(conn.outputStream).use { it.write(wavBytes) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                Log.e(TAG, "Deepgram error $responseCode: $err")
                return ""
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            parseDeepgramResponse(responseText)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDeepgramResponse(json: String): String {
        return try {
            val root = JSONObject(json)
            val results = root.getJSONObject("results")
            val channels = results.getJSONArray("channels")
            if (channels.length() == 0) return ""
            val alternatives = channels.getJSONObject(0).getJSONArray("alternatives")
            if (alternatives.length() == 0) return ""
            alternatives.getJSONObject(0).optString("transcript", "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Deepgram response", e)
            ""
        }
    }

    /**
     * Convert float32 PCM samples (range -1..1) to a valid WAV byte array.
     */
    private fun floatsToWav(samples: FloatArray): ByteArray {
        val sampleRate = AudioRecorder.SAMPLE_RATE
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val pcmBytes = samples.size * 2
        val totalBytes = 44 + pcmBytes

        val buffer = java.nio.ByteBuffer.allocate(totalBytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalBytes - 8)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)               // PCM chunk size
        buffer.putShort(1)              // PCM format
        buffer.putShort(numChannels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(pcmBytes)

        // PCM samples
        for (sample in samples) {
            val s = (sample * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            buffer.putShort(s)
        }

        return buffer.array()
    }

    fun release() {
        recorder.release()
    }
}
