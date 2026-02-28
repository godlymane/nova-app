package com.nova.companion.inference

/**
 * JNI bridge to llama.cpp native library.
 * All native methods correspond to functions in llama_jni.cpp.
 */
class LlamaJNI {

    companion object {
        init {
            System.loadLibrary("nova_llama")
        }
    }

    /**
     * Load a GGUF model from the given file path.
     * @param modelPath Absolute path to the .gguf model file on device storage.
     * @param nThreads Number of CPU threads to use for inference.
     * @return true if model loaded successfully.
     */
    external fun loadModel(modelPath: String, nThreads: Int): Boolean

    /**
     * Generate a complete response (blocking).
     * @param prompt The full formatted prompt string.
     * @param maxTokens Maximum tokens to generate.
     * @param temperature Sampling temperature (0.0 = greedy, higher = more random).
     * @param topP Top-p (nucleus) sampling threshold.
     * @param stopStrings Array of strings that stop generation when encountered.
     * @return The generated text.
     */
    external fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopStrings: Array<String>
    ): String

    /**
     * Generate tokens with streaming callback.
     * Each token is sent to the callback as it's generated.
     * Call from a background thread / coroutine.
     *
     * @param prompt The full formatted prompt string.
     * @param maxTokens Maximum tokens to generate.
     * @param temperature Sampling temperature.
     * @param topP Top-p sampling threshold.
     * @param stopStrings Array of strings that stop generation.
     * @param callback Object implementing onToken(String) to receive each token.
     */
    external fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopStrings: Array<String>,
        callback: TokenCallback
    )

    /** Request cancellation of current generation. */
    external fun cancelGeneration()

    /** Unload the model and free all native memory. */
    external fun unloadModel()

    /** Check if a model is currently loaded and ready. */
    external fun isModelLoaded(): Boolean

    /** Get model loading progress (0.0 to 1.0). */
    external fun getLoadProgress(): Float

    /** Check if generation is currently in progress. */
    external fun isGenerating(): Boolean
}

/**
 * Callback interface for streaming token generation.
 * Implemented in Kotlin, called from native C++ code.
 */
interface TokenCallback {
    fun onToken(token: String)
}
