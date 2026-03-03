package com.nova.companion.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.nova.companion.cloud.CloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Premium ElevenLabs TTS client using the REST streaming API.
 *
 * Flow: text → POST /v1/text-to-speech/{voice_id}/stream → PCM audio → AudioTrack playback
 *
 * Uses eleven_turbo_v2_5 model for lowest latency with premium voice quality.
 * Streams PCM 24kHz 16-bit mono directly to AudioTrack for instant playback.
 */
object ElevenLabsTTS {

    private const val TAG = "ElevenLabsTTS"
    private const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
    private const val SAMPLE_RATE = 24000
    private const val MODEL_ID = "eleven_flash_v2_5"

    // Buffer size for streaming audio chunks (4KB = ~85ms of audio at 24kHz 16-bit)
    private const val STREAM_BUFFER_SIZE = 4096

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * Audio amplitude (RMS) normalized to 0f..1f, updated per PCM chunk during playback.
     * Used by the aura overlay to sync SPEAKING visualization to voice output volume.
     */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Speak text using ElevenLabs TTS API with streaming playback.
     *
     * Streams PCM audio directly to AudioTrack for minimal latency —
     * audio starts playing as soon as the first chunk arrives from the API.
     *
     * @param text Text to synthesize
     * @param onStart Called when audio playback begins
     * @param onComplete Called when audio finishes playing
     * @param onError Called if TTS fails
     */
    suspend fun speak(
        text: String,
        onStart: () -> Unit = {},
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (text.isBlank()) {
            onComplete()
            return
        }

        val apiKey = CloudConfig.elevenLabsApiKey
        if (apiKey.isBlank()) {
            onError("ElevenLabs API key not configured")
            return
        }

        val voiceId = CloudConfig.elevenLabsVoiceId.ifBlank { "21m00Tcm4TlvDq8ikWAM" }

        // Stop any ongoing speech first
        stop()

        withContext(Dispatchers.IO) {
            try {
                _isSpeaking.value = true

                // output_format MUST be a query parameter, NOT in the JSON body.
                // Without it, API returns MP3 which sounds like garbage as raw PCM.
                val url = "$BASE_URL/$voiceId/stream?output_format=pcm_24000"

                val jsonBody = """
                    {
                        "text": ${escapeJson(text)},
                        "model_id": "$MODEL_ID",
                        "voice_settings": {
                            "stability": 0.5,
                            "similarity_boost": 0.75,
                            "style": 0.0,
                            "use_speaker_boost": true
                        }
                    }
                """.trimIndent()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("xi-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "audio/pcm")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                Log.i(TAG, ">>> TTS request: voice=$voiceId model=$MODEL_ID text=\"${text.take(80)}...\" (${text.length} chars)")

                val response = client.newCall(request).execute()

                Log.i(TAG, ">>> TTS response: ${response.code} contentType=${response.header("Content-Type")}")

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "TTS API error ${response.code}: $errorBody")
                    _isSpeaking.value = false
                    withContext(Dispatchers.Main) { onError("TTS failed: ${response.code}") }
                    return@withContext
                }

                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    _isSpeaking.value = false
                    withContext(Dispatchers.Main) { onError("Empty TTS response") }
                    return@withContext
                }

                // Track character usage
                try {
                    // We don't have context here, so just log it
                    Log.d(TAG, "ElevenLabs TTS: ${text.length} chars synthesized")
                } catch (_: Exception) {}

                // Stream PCM to AudioTrack
                withContext(Dispatchers.Main) { onStart() }
                streamToAudioTrack(inputStream)
                withContext(Dispatchers.Main) { onComplete() }

            } catch (e: Exception) {
                Log.e(TAG, "TTS failed", e)
                withContext(Dispatchers.Main) { onError("TTS error: ${e.message}") }
            } finally {
                _isSpeaking.value = false
                _amplitude.value = 0f
                releaseAudioTrack()
            }
        }
    }

    /**
     * Stream PCM audio data from InputStream to AudioTrack.
     *
     * Two fixes for glitch-free playback:
     * 1. Pre-buffer ~200ms of audio before starting playback (prevents underruns)
     * 2. Ensure 16-bit PCM byte alignment (network chunks can be odd-sized)
     */
    private fun streamToAudioTrack(inputStream: InputStream) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Large buffer = smooth playback despite network jitter (1 second of audio)
        val bufferSize = maxOf(minBufferSize * 4, SAMPLE_RATE * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track

        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var totalBytes = 0
        var hasOddByte = false   // Track if previous chunk had an odd trailing byte
        var oddByte: Byte = 0   // The held-back odd byte

        // Pre-buffer threshold: ~200ms of audio at 24kHz 16-bit mono = 9600 bytes
        val preBufferBytes = SAMPLE_RATE * 2 / 5  // 9600 bytes
        var playbackStarted = false

        try {
            inputStream.use { stream ->
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    if (!_isSpeaking.value) break

                    var offset = 0
                    var length = bytesRead

                    // If we held back an odd byte from last chunk, prepend it
                    if (hasOddByte) {
                        // Write the odd byte + first byte of this chunk as a complete sample
                        val sampleBytes = byteArrayOf(oddByte, buffer[0])
                        track.write(sampleBytes, 0, 2)
                        totalBytes += 2
                        offset = 1
                        length = bytesRead - 1
                        hasOddByte = false
                    }

                    // If remaining length is odd, hold back the last byte
                    if (length % 2 != 0) {
                        oddByte = buffer[offset + length - 1]
                        hasOddByte = true
                        length -= 1
                    }

                    // Write even-aligned PCM data
                    if (length > 0) {
                        track.write(buffer, offset, length)
                        totalBytes += length
                        // Compute RMS amplitude from this PCM chunk for aura visualization
                        _amplitude.value = computeRms(buffer, offset, length)
                    }

                    // Start playback after pre-buffering enough data
                    if (!playbackStarted && totalBytes >= preBufferBytes) {
                        track.play()
                        playbackStarted = true
                        Log.d(TAG, "Playback started after pre-buffering ${totalBytes / 1024}KB")
                    }
                }
            }

            // If we never hit pre-buffer threshold but have data, start playback now
            if (!playbackStarted && totalBytes > 0 && _isSpeaking.value) {
                track.play()
                playbackStarted = true
                Log.d(TAG, "Playback started with all data: ${totalBytes / 1024}KB (below pre-buffer threshold)")
            }

            // Wait for AudioTrack to drain all buffered audio before returning.
            // track.stop() in MODE_STREAM tells it to stop after draining, but
            // does NOT block — so we must wait for playbackHead to reach totalFrames.
            if (_isSpeaking.value && totalBytes > 0 && playbackStarted) {
                val totalFrames = totalBytes / 2  // 2 bytes per frame (16-bit mono)
                track.stop()

                // Poll until AudioTrack has played all frames (or we're stopped externally)
                var waitMs = 0
                val maxWaitMs = (totalFrames.toLong() * 1000 / SAMPLE_RATE) + 2000 // expected + 2s grace
                while (_isSpeaking.value && waitMs < maxWaitMs) {
                    val head = track.playbackHeadPosition
                    if (head >= totalFrames) break
                    Thread.sleep(50)
                    waitMs += 50
                }

                val durationMs = (totalBytes.toLong() * 1000) / (SAMPLE_RATE * 2)
                Log.d(TAG, "TTS playback complete: ${totalBytes / 1024}KB, ~${durationMs}ms, waited ${waitMs}ms for drain")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio streaming error", e)
        }
    }

    /**
     * Stop current speech playback immediately.
     */
    fun stop() {
        _isSpeaking.value = false
        _amplitude.value = 0f
        playbackJob?.cancel()
        playbackJob = null
        releaseAudioTrack()
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                    track.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack", e)
        }
        audioTrack = null
    }

    /**
     * Compute RMS amplitude from 16-bit PCM samples, normalized to 0f..1f.
     * Used to drive the SPEAKING aura waveform visualization.
     */
    private fun computeRms(buffer: ByteArray, offset: Int, length: Int): Float {
        if (length < 2) return 0f
        val numSamples = length / 2
        var sumSquares = 0.0
        for (i in 0 until numSamples) {
            val idx = offset + i * 2
            // Little-endian 16-bit PCM
            val sample = (buffer[idx].toInt() and 0xFF) or (buffer[idx + 1].toInt() shl 8)
            val normalized = sample.toShort().toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }
        return kotlin.math.sqrt(sumSquares / numSamples).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Properly escape a string for JSON embedding.
     */
    private fun escapeJson(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
