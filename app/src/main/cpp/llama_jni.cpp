#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <thread>

#include "llama.h"

#define TAG "NovaLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============================================================
// Global state
// ============================================================
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_is_generating{false};
static std::atomic<bool> g_cancel_generation{false};
static std::atomic<float> g_load_progress{0.0f};

// Progress callback during model loading
static bool model_load_progress(float progress, void * /*user_data*/) {
    g_load_progress.store(progress);
    LOGI("Model load progress: %.1f%%", progress * 100.0f);
    return true; // return false to cancel
}

// Helper: add a token to a batch (uses pre-allocated seq_id from llama_batch_init)
static void batch_add_token(llama_batch &batch, llama_token id, llama_pos pos, bool logits) {
    const int idx = batch.n_tokens;
    batch.token[idx]    = id;
    batch.pos[idx]      = pos;
    batch.n_seq_id[idx] = 1;
    // Use the pre-allocated seq_id array — do NOT malloc a new one!
    batch.seq_id[idx][0] = 0;
    batch.logits[idx]   = logits ? 1 : 0;
    batch.n_tokens++;
}

// ============================================================
// JNI Functions
// ============================================================
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nova_companion_inference_LlamaJNI_loadModel(
        JNIEnv *env,
        jobject /* this */,
        jstring modelPath,
        jint nThreads) {

    std::lock_guard<std::mutex> lock(g_mutex);

    // Unload existing model if any
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path string");
        return JNI_FALSE;
    }

    LOGI("Loading model from: %s", path);
    g_load_progress.store(0.0f);

    // Initialize llama backend
    llama_backend_init();

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.progress_callback = model_load_progress;
    model_params.progress_callback_user_data = nullptr;

    // Load model
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        llama_backend_free();
        return JNI_FALSE;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;        // Context window
    ctx_params.n_threads = nThreads; // CPU threads for generation
    ctx_params.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(g_model);
        g_model = nullptr;
        llama_backend_free();
        return JNI_FALSE;
    }

    g_load_progress.store(1.0f);
    LOGI("Model loaded successfully. Context size: %d", llama_n_ctx(g_ctx));
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_nova_companion_inference_LlamaJNI_generate(
        JNIEnv *env,
        jobject /* this */,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jobjectArray stopStrings) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    g_is_generating.store(true);
    g_cancel_generation.store(false);

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    // Collect stop strings
    std::vector<std::string> stop_strs;
    if (stopStrings) {
        int stop_count = env->GetArrayLength(stopStrings);
        for (int i = 0; i < stop_count; i++) {
            auto jstr = (jstring) env->GetObjectArrayElement(stopStrings, i);
            const char *s = env->GetStringUTFChars(jstr, nullptr);
            stop_strs.emplace_back(s);
            env->ReleaseStringUTFChars(jstr, s);
        }
    }

    // Tokenize the prompt
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(prompt_str.size() + 128);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                   tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt");
        g_is_generating.store(false);
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    LOGI("Prompt tokens: %d, generating up to %d tokens", n_tokens, maxTokens);

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Evaluate prompt in batch
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch_add_token(batch, tokens[i], i, false);
    }
    batch.logits[batch.n_tokens - 1] = true; // Only compute logits for last token

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to evaluate prompt");
        llama_batch_free(batch);
        g_is_generating.store(false);
        return env->NewStringUTF("");
    }
    llama_batch_free(batch);

    // Set up sampler chain
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    // Generate tokens
    std::string result;
    int n_cur = n_tokens;

    for (int i = 0; i < maxTokens; i++) {
        if (g_cancel_generation.load()) {
            LOGI("Generation cancelled");
            break;
        }

        // Sample next token
        llama_token new_token = llama_sampler_sample(smpl, g_ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("End of generation token reached");
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            result += piece;

            // Check stop strings
            bool should_stop = false;
            for (const auto &stop : stop_strs) {
                if (result.length() >= stop.length()) {
                    if (result.find(stop, result.length() - stop.length() - 1) != std::string::npos) {
                        size_t pos = result.rfind(stop);
                        if (pos != std::string::npos) {
                            result = result.substr(0, pos);
                        }
                        should_stop = true;
                        break;
                    }
                }
            }
            if (should_stop) break;
        }

        // Evaluate the new token
        llama_batch single = llama_batch_init(1, 0, 1);
        batch_add_token(single, new_token, n_cur, true);
        if (llama_decode(g_ctx, single) != 0) {
            LOGE("Failed to evaluate token at position %d", n_cur);
            llama_batch_free(single);
            break;
        }
        llama_batch_free(single);
        n_cur++;
    }

    llama_sampler_free(smpl);
    g_is_generating.store(false);

    LOGI("Generated %d chars", (int) result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_nova_companion_inference_LlamaJNI_generateStreaming(
        JNIEnv *env,
        jobject thiz,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jobjectArray stopStrings,
        jobject callback) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return;
    }

    g_is_generating.store(true);
    g_cancel_generation.store(false);

    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    // Collect stop strings
    std::vector<std::string> stop_strs;
    if (stopStrings) {
        int stop_count = env->GetArrayLength(stopStrings);
        for (int i = 0; i < stop_count; i++) {
            auto jstr = (jstring) env->GetObjectArrayElement(stopStrings, i);
            const char *s = env->GetStringUTFChars(jstr, nullptr);
            stop_strs.emplace_back(s);
            env->ReleaseStringUTFChars(jstr, s);
        }
    }

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGE("Failed to find onToken callback method");
        g_is_generating.store(false);
        return;
    }

    // Tokenize
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(prompt_str.size() + 128);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                                   tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt (result: %d)", n_tokens);
        g_is_generating.store(false);
        return;
    }
    tokens.resize(n_tokens);
    LOGI("Streaming: prompt tokens=%d, max=%d", n_tokens, maxTokens);

    // Clear KV cache and evaluate prompt
    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch_add_token(batch, tokens[i], i, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    LOGI("Evaluating prompt batch (%d tokens)...", n_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to evaluate prompt");
        llama_batch_free(batch);
        g_is_generating.store(false);
        return;
    }
    llama_batch_free(batch);
    LOGI("Prompt evaluated, starting generation...");

    // Sampler
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    std::string accumulated;
    int n_cur = n_tokens;

    for (int i = 0; i < maxTokens; i++) {
        if (g_cancel_generation.load()) {
            LOGI("Streaming generation cancelled at token %d", i);
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("EOG reached at token %d", i);
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            accumulated += piece;

            // Check stop strings
            bool should_stop = false;
            for (const auto &stop : stop_strs) {
                size_t pos = accumulated.rfind(stop);
                if (pos != std::string::npos) {
                    piece = "";
                    should_stop = true;
                    break;
                }
            }

            // Send token to Kotlin callback
            if (!piece.empty()) {
                jstring jPiece = env->NewStringUTF(piece.c_str());
                if (jPiece) {
                    env->CallVoidMethod(callback, onToken, jPiece);
                    env->DeleteLocalRef(jPiece);
                    // Check for Java exception
                    if (env->ExceptionCheck()) {
                        LOGE("Java exception during onToken callback at token %d", i);
                        env->ExceptionClear();
                        break;
                    }
                }
            }

            if (should_stop) {
                LOGI("Stop string hit at token %d", i);
                break;
            }
        }

        // Evaluate new token
        llama_batch single = llama_batch_init(1, 0, 1);
        batch_add_token(single, new_token, n_cur, true);
        if (llama_decode(g_ctx, single) != 0) {
            LOGE("Decode failed at position %d", n_cur);
            llama_batch_free(single);
            break;
        }
        llama_batch_free(single);
        n_cur++;
    }

    llama_sampler_free(smpl);
    g_is_generating.store(false);
    LOGI("Streaming complete: generated %d tokens, %d chars", n_cur - n_tokens, (int)accumulated.size());
}

JNIEXPORT void JNICALL
Java_com_nova_companion_inference_LlamaJNI_cancelGeneration(
        JNIEnv * /*env*/,
        jobject /* this */) {
    g_cancel_generation.store(true);
    LOGI("Generation cancel requested");
}

JNIEXPORT void JNICALL
Java_com_nova_companion_inference_LlamaJNI_unloadModel(
        JNIEnv * /*env*/,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
    g_load_progress.store(0.0f);
    LOGI("Model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_nova_companion_inference_LlamaJNI_isModelLoaded(
        JNIEnv * /*env*/,
        jobject /* this */) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_nova_companion_inference_LlamaJNI_getLoadProgress(
        JNIEnv * /*env*/,
        jobject /* this */) {
    return g_load_progress.load();
}

JNIEXPORT jboolean JNICALL
Java_com_nova_companion_inference_LlamaJNI_isGenerating(
        JNIEnv * /*env*/,
        jobject /* this */) {
    return g_is_generating.load() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
