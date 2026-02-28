package com.nova.companion.voice

/**
 * JNI bridge to whisper.cpp native library.
 * Handles speech-to-text transcription using the Whisper tiny model.
 *
 * Native methods correspond to functions in whisper_jni.cpp.
 * The native library "nova_whisper" is compiled via CMake alongside llama.cpp.
 */
class WhisperJNI {

    companion object {
        init {
            System.loadLibrary("nova_whisper")
        }
    }

    /**
     * Initialize whisper context with model file.
     * @param modelPath Absolute path to whisper model (ggml-tiny.bin or similar).
     * @return true if model loaded successfully.
     */
    external fun initContext(modelPath: String): Boolean

    /**
     * Transcribe audio samples to text.
     * @param samples Float array of 16kHz mono audio samples (normalized -1.0 to 1.0).
     * @param numSamples Number of valid samples in the array.
     * @param language Language code ("en" for English, "auto" for auto-detect).
     * @param translate If true, translate to English regardless of source language.
     * @return Transcribed text string.
     */
    external fun transcribe(
        samples: FloatArray,
        numSamples: Int,
        language: String,
        translate: Boolean
    ): String

    /**
     * Transcribe with partial results callback for live transcription.
     * @param samples Float array of 16kHz mono audio.
     * @param numSamples Number of valid samples.
     * @param language Language code.
     * @param callback Receives partial transcription segments as they're decoded.
     * @return Final complete transcription.
     */
    external fun transcribeWithCallback(
        samples: FloatArray,
        numSamples: Int,
        language: String,
        callback: WhisperSegmentCallback
    ): String

    /**
     * Check if whisper context is initialized and ready.
     */
    external fun isInitialized(): Boolean

    /**
     * Free whisper context and all associated memory.
     */
    external fun freeContext()

    /**
     * Get the whisper.cpp version string.
     */
    external fun getVersion(): String
}

/**
 * Callback interface for partial transcription segments.
 * Called from native code as each segment is decoded.
 */
interface WhisperSegmentCallback {
    /**
     * Called when a new segment is decoded.
     * @param startMs Start timestamp in milliseconds.
     * @param endMs End timestamp in milliseconds.
     * @param text The decoded text for this segment.
     */
    fun onSegment(startMs: Long, endMs: Long, text: String)
}
