/**
 * JNI bridge for whisper.cpp - Speech-to-Text
 *
 * Provides native methods for the WhisperJNI Kotlin class.
 * Handles audio transcription using whisper.cpp compiled for ARM64.
 */

#include <jni.h>
#include <string>
#include <android/log.h>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Global whisper context
static struct whisper_context *g_ctx = nullptr;

extern "C" {

// ============================================================
// initContext - Load whisper model
// ============================================================
JNIEXPORT jboolean JNICALL
Java_com_nova_companion_voice_WhisperJNI_initContext(
        JNIEnv *env,
        jobject /* this */,
        jstring modelPath) {

    if (g_ctx != nullptr) {
        LOGI("Freeing existing whisper context");
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading whisper model: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only for Android

    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (g_ctx == nullptr) {
        LOGE("Failed to initialize whisper context");
        return JNI_FALSE;
    }

    LOGI("Whisper model loaded successfully");
    return JNI_TRUE;
}

// ============================================================
// transcribe - Basic transcription (returns full text)
// ============================================================
JNIEXPORT jstring JNICALL
Java_com_nova_companion_voice_WhisperJNI_transcribe(
        JNIEnv *env,
        jobject /* this */,
        jfloatArray samples,
        jint numSamples,
        jstring language,
        jboolean translate) {

    if (g_ctx == nullptr) {
        LOGE("Whisper context not initialized");
        return env->NewStringUTF("");
    }

    // Get audio samples
    jfloat *audioData = env->GetFloatArrayElements(samples, nullptr);

    // Configure whisper parameters
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;
    params.translate = translate;
    params.n_threads = 4;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;

    LOGI("Transcribing %d samples...", numSamples);

    // Run inference
    int result = whisper_full(g_ctx, params, audioData, numSamples);

    env->ReleaseFloatArrayElements(samples, audioData, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("Whisper inference failed with code: %d", result);
        return env->NewStringUTF("");
    }

    // Collect transcribed text
    std::string transcription;
    int n_segments = whisper_full_n_segments(g_ctx);

    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(g_ctx, i);
        transcription += text;
    }

    LOGI("Transcription complete: %d segments, text: \"%s\"",
         n_segments, transcription.c_str());

    return env->NewStringUTF(transcription.c_str());
}

// ============================================================
// transcribeWithCallback - Transcription with segment callbacks
// ============================================================
JNIEXPORT jstring JNICALL
Java_com_nova_companion_voice_WhisperJNI_transcribeWithCallback(
        JNIEnv *env,
        jobject /* this */,
        jfloatArray samples,
        jint numSamples,
        jstring language,
        jobject callback) {

    if (g_ctx == nullptr) {
        LOGE("Whisper context not initialized");
        return env->NewStringUTF("");
    }

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onSegmentMethod = env->GetMethodID(
            callbackClass, "onSegment", "(JJLjava/lang/String;)V");

    if (onSegmentMethod == nullptr) {
        LOGE("Could not find onSegment callback method");
        return env->NewStringUTF("");
    }

    // Get audio samples
    jfloat *audioData = env->GetFloatArrayElements(samples, nullptr);

    // Configure parameters
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;
    params.translate = false;
    params.n_threads = 4;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = true;

    LOGI("Transcribing with callback: %d samples...", numSamples);

    // Run inference
    int result = whisper_full(g_ctx, params, audioData, numSamples);

    env->ReleaseFloatArrayElements(samples, audioData, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("Whisper inference failed with code: %d", result);
        return env->NewStringUTF("");
    }

    // Iterate segments and call back to Kotlin
    std::string fullText;
    int n_segments = whisper_full_n_segments(g_ctx);

    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(g_ctx, i);
        int64_t t0 = whisper_full_get_segment_t0(g_ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(g_ctx, i);

        // Convert whisper time units (centiseconds) to milliseconds
        jlong startMs = t0 * 10;
        jlong endMs = t1 * 10;

        jstring segmentText = env->NewStringUTF(text);
        env->CallVoidMethod(callback, onSegmentMethod, startMs, endMs, segmentText);
        env->DeleteLocalRef(segmentText);

        fullText += text;

        LOGD("Segment %d [%lld-%lld ms]: %s", i, (long long)startMs, (long long)endMs, text);
    }

    LOGI("Transcription with callback complete: %d segments", n_segments);
    return env->NewStringUTF(fullText.c_str());
}

// ============================================================
// isInitialized
// ============================================================
JNIEXPORT jboolean JNICALL
Java_com_nova_companion_voice_WhisperJNI_isInitialized(
        JNIEnv *env,
        jobject /* this */) {
    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// freeContext
// ============================================================
JNIEXPORT void JNICALL
Java_com_nova_companion_voice_WhisperJNI_freeContext(
        JNIEnv *env,
        jobject /* this */) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("Whisper context freed");
    }
}

// ============================================================
// getVersion
// ============================================================
JNIEXPORT jstring JNICALL
Java_com_nova_companion_voice_WhisperJNI_getVersion(
        JNIEnv *env,
        jobject /* this */) {
    return env->NewStringUTF("whisper.cpp (Nova build)");
}

} // extern "C"
