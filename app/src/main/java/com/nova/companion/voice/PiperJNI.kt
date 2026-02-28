package com.nova.companion.voice

/**
 * JNI bridge to Piper TTS native library.
 * Handles text-to-speech synthesis using ONNX Runtime on-device.
 *
 * Native methods correspond to functions in piper_jni.cpp.
 * The native library "nova_piper" is compiled via CMake.
 */
class PiperJNI {

    companion object {
        init {
            System.loadLibrary("nova_piper")
        }
    }

    /**
     * Initialize Piper with a voice model and its config.
     * @param modelPath Absolute path to the ONNX voice model file.
     * @param configPath Absolute path to the voice model JSON config.
     * @return true if initialized successfully.
     */
    external fun initialize(modelPath: String, configPath: String): Boolean

    /**
     * Synthesize speech from text.
     * @param text The text to speak.
     * @param speakerId Speaker ID for multi-speaker models (0 for single-speaker).
     * @param lengthScale Controls speech speed (1.0 = normal, <1.0 = faster, >1.0 = slower).
     * @param noiseScale Controls expressiveness/variation (0.667 default).
     * @param noiseW Controls phoneme duration randomness (0.8 default).
     * @return Raw PCM audio samples as ShortArray (16-bit signed, mono).
     *         Sample rate is determined by the model (typically 22050Hz).
     */
    external fun synthesize(
        text: String,
        speakerId: Int = 0,
        lengthScale: Float = 1.0f,
        noiseScale: Float = 0.667f,
        noiseW: Float = 0.8f
    ): ShortArray

    /**
     * Synthesize with callback for streaming audio output.
     * Generates audio sentence-by-sentence for lower latency.
     * @param text Full text to speak.
     * @param callback Receives audio chunks as they're generated.
     */
    external fun synthesizeStreaming(
        text: String,
        callback: PiperAudioCallback
    )

    /**
     * Get the sample rate of the loaded voice model.
     * @return Sample rate in Hz (typically 22050).
     */
    external fun getSampleRate(): Int

    /**
     * Check if Piper is initialized with a voice model.
     */
    external fun isInitialized(): Boolean

    /**
     * Free all Piper resources and ONNX session.
     */
    external fun release()
}

/**
 * Callback for streaming audio synthesis.
 * Called from native code as each sentence/chunk is synthesized.
 */
interface PiperAudioCallback {
    /**
     * Called when a chunk of audio has been synthesized.
     * @param samples PCM audio samples (16-bit signed, mono).
     * @param sampleRate Sample rate in Hz.
     * @param isLast true if this is the final chunk.
     */
    fun onAudioChunk(samples: ShortArray, sampleRate: Int, isLast: Boolean)
}
