package com.nova.companion.cloud

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Text-to-Speech via ElevenLabs REST API.
 *
 * Uses the Flash model (eleven_flash_v2_5) for lowest latency (~75ms).
 * Falls back to local Piper TTS if the API fails or device is offline.
 */
object ElevenLabsTTS {

    private const val TAG = "NovaCloud"
    private const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
    private const val MODEL_ID = "eleven_flash_v2_5"
    private const val OUTPUT_FORMAT = "mp3_44100_128"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    /**
     * Synthesize speech from text and return raw audio bytes (MP3).
     *
     * @param text The text to speak
     * @param context Android context for usage tracking
     * @return MP3 audio bytes, or null if the request failed
     */
    suspend fun synthesize(
        text: String,
        context: Context
    ): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.elevenLabsApiKey
        val voiceId = CloudConfig.elevenLabsVoiceId

        if (apiKey.isBlank() || voiceId.isBlank()) {
            Log.w(TAG, "ElevenLabs API key or voice ID not configured")
            return@withContext null
        }

        if (!CloudConfig.isOnline(context)) {
            Log.w(TAG, "Device offline, skipping ElevenLabs TTS")
            return@withContext null
        }

        val requestBody = JsonObject().apply {
            addProperty("text", text)
            addProperty("model_id", MODEL_ID)
            add("voice_settings", JsonObject().apply {
                addProperty("stability", 0.5)
                addProperty("similarity_boost", 0.75)
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/$voiceId?output_format=$OUTPUT_FORMAT")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/mpeg")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).executeSuspend()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "no body"
                Log.e(TAG, "ElevenLabs TTS failed: ${response.code} - $errorBody")
                response.close()
                return@withContext null
            }

            val audioBytes = response.body?.bytes()
            response.close()

            if (audioBytes != null) {
                CloudConfig.trackElevenLabsChars(context, text.length)
                Log.d(TAG, "ElevenLabs TTS: ${audioBytes.size} bytes for ${text.length} chars")
            }

            audioBytes
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs TTS error", e)
            null
        }
    }

    /**
     * Synthesize and immediately play audio through MediaPlayer.
     *
     * @param text Text to speak
     * @param context Android context
     * @param onComplete Called when playback finishes
     * @param onError Called if synthesis or playback fails
     */
    suspend fun speak(
        text: String,
        context: Context,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val audioBytes = synthesize(text, context)

        if (audioBytes == null) {
            onError("TTS synthesis failed — falling back to local")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // Write to temp file for MediaPlayer
                val tempFile = File(context.cacheDir, "nova_tts_${System.currentTimeMillis()}.mp3")
                FileOutputStream(tempFile).use { it.write(audioBytes) }

                withContext(Dispatchers.Main) {
                    stopPlayback()

                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener {
                            tempFile.delete()
                            onComplete()
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                            tempFile.delete()
                            onError("Playback error: $what")
                            true
                        }
                        prepare()
                        start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS playback error", e)
                onError(e.message ?: "Playback failed")
            }
        }
    }

    /**
     * Stop any active TTS playback.
     */
    fun stopPlayback() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping media player", e)
            }
        }
        mediaPlayer = null
    }

    /**
     * Streaming TTS — synthesize and return audio bytes in chunks for faster first-byte.
     * Streams audio via OkHttp response body streaming.
     */
    suspend fun synthesizeStreaming(
        text: String,
        context: Context,
        onChunk: (ByteArray) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val apiKey = CloudConfig.elevenLabsApiKey
        val voiceId = CloudConfig.elevenLabsVoiceId

        if (apiKey.isBlank() || voiceId.isBlank() || !CloudConfig.isOnline(context)) {
            return@withContext false
        }

        val requestBody = JsonObject().apply {
            addProperty("text", text)
            addProperty("model_id", MODEL_ID)
            add("voice_settings", JsonObject().apply {
                addProperty("stability", 0.5)
                addProperty("similarity_boost", 0.75)
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/$voiceId/stream?output_format=$OUTPUT_FORMAT")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/mpeg")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).executeSuspend()

            if (!response.isSuccessful) {
                Log.e(TAG, "ElevenLabs stream failed: ${response.code}")
                response.close()
                return@withContext false
            }

            val source = response.body?.source() ?: run {
                response.close()
                return@withContext false
            }

            val buffer = ByteArray(4096)
            while (!source.exhausted()) {
                val bytesRead = source.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    onChunk(buffer.copyOf(bytesRead))
                }
            }

            response.close()
            CloudConfig.trackElevenLabsChars(context, text.length)
            true
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs streaming error", e)
            false
        }
    }
}

/**
 * Extension to make OkHttp calls suspendable.
 */
suspend fun Call.executeSuspend(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}
