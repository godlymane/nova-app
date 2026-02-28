/**
 * JNI bridge for Piper TTS - Text-to-Speech
 *
 * Provides native methods for the PiperJNI Kotlin class.
 * Handles speech synthesis using Piper with ONNX Runtime.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <fstream>
#include <sstream>

#include "piper.hpp"

#define LOG_TAG "PiperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Global Piper state
static piper::PiperConfig g_piperConfig;
static piper::Voice g_voice;
static bool g_initialized = false;
static int g_sampleRate = 22050;

extern "C" {

// ============================================================
// initialize - Load Piper voice model
// ============================================================
JNIEXPORT jboolean JNICALL
Java_com_nova_companion_voice_PiperJNI_initialize(
        JNIEnv *env,
        jobject /* this */,
        jstring modelPath,
        jstring configPath) {

    if (g_initialized) {
        LOGI("Releasing existing Piper voice");
        // Piper cleanup handled by destructor
        g_initialized = false;
    }

    const char *model = env->GetStringUTFChars(modelPath, nullptr);
    const char *config = env->GetStringUTFChars(configPath, nullptr);

    LOGI("Loading Piper voice model: %s", model);
    LOGI("Config: %s", config);

    try {
        // Initialize Piper config (eSpeakNG data path not needed for ONNX models
        // that have their own phonemizer built-in)
        g_piperConfig.useESpeak = false;

        // Load voice model
        std::optional<piper::SpeakerId> speakerId;
        piper::loadVoice(g_piperConfig, model, config, g_voice, speakerId, false);

        // Get sample rate from voice config
        g_sampleRate = g_voice.synthesisConfig.sampleRate;

        g_initialized = true;
        LOGI("Piper voice loaded. Sample rate: %d Hz", g_sampleRate);

    } catch (const std::exception &e) {
        LOGE("Failed to load Piper voice: %s", e.what());
        g_initialized = false;
    }

    env->ReleaseStringUTFChars(modelPath, model);
    env->ReleaseStringUTFChars(configPath, config);

    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// synthesize - Generate audio from text (blocking)
// ============================================================
JNIEXPORT jshortArray JNICALL
Java_com_nova_companion_voice_PiperJNI_synthesize(
        JNIEnv *env,
        jobject /* this */,
        jstring text,
        jint speakerId,
        jfloat lengthScale,
        jfloat noiseScale,
        jfloat noiseW) {

    if (!g_initialized) {
        LOGE("Piper not initialized");
        return env->NewShortArray(0);
    }

    const char *inputText = env->GetStringUTFChars(text, nullptr);
    LOGI("Synthesizing: \"%s\"", inputText);

    try {
        // Configure synthesis parameters
        piper::SynthesisConfig synthConfig = g_voice.synthesisConfig;
        synthConfig.lengthScale = lengthScale;
        synthConfig.noiseScale = noiseScale;
        synthConfig.noiseW = noiseW;

        if (speakerId > 0) {
            synthConfig.speakerId = speakerId;
        }

        // Synthesize
        std::vector<int16_t> audioBuffer;
        piper::SynthesisResult result;
        piper::textToAudio(g_piperConfig, g_voice, inputText, audioBuffer, result);

        env->ReleaseStringUTFChars(text, inputText);

        LOGI("Synthesis complete: %zu samples (%.2f seconds)",
             audioBuffer.size(),
             (float)audioBuffer.size() / g_sampleRate);

        // Copy to Java array
        jshortArray output = env->NewShortArray(audioBuffer.size());
        env->SetShortArrayRegion(output, 0, audioBuffer.size(), audioBuffer.data());

        return output;

    } catch (const std::exception &e) {
        LOGE("Synthesis failed: %s", e.what());
        env->ReleaseStringUTFChars(text, inputText);
        return env->NewShortArray(0);
    }
}

// ============================================================
// synthesizeStreaming - Generate audio with chunk callbacks
// ============================================================
JNIEXPORT void JNICALL
Java_com_nova_companion_voice_PiperJNI_synthesizeStreaming(
        JNIEnv *env,
        jobject /* this */,
        jstring text,
        jobject callback) {

    if (!g_initialized) {
        LOGE("Piper not initialized");
        return;
    }

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onAudioChunkMethod = env->GetMethodID(
            callbackClass, "onAudioChunk", "([SIZ)V");

    if (onAudioChunkMethod == nullptr) {
        LOGE("Could not find onAudioChunk callback method");
        return;
    }

    const char *inputText = env->GetStringUTFChars(text, nullptr);
    LOGI("Streaming synthesis: \"%s\"", inputText);

    try {
        // Split text into sentences for streaming
        std::string fullText(inputText);
        std::vector<std::string> sentences;

        // Simple sentence splitting on . ! ?
        std::string current;
        for (char c : fullText) {
            current += c;
            if (c == '.' || c == '!' || c == '?') {
                // Trim whitespace
                size_t start = current.find_first_not_of(" \t\n");
                if (start != std::string::npos) {
                    sentences.push_back(current.substr(start));
                }
                current.clear();
            }
        }
        // Add remaining text if any
        if (!current.empty()) {
            size_t start = current.find_first_not_of(" \t\n");
            if (start != std::string::npos) {
                sentences.push_back(current.substr(start));
            }
        }

        // If no sentence breaks found, treat whole text as one chunk
        if (sentences.empty()) {
            sentences.push_back(fullText);
        }

        LOGD("Split into %zu sentences for streaming", sentences.size());

        // Synthesize each sentence and callback
        for (size_t i = 0; i < sentences.size(); i++) {
            std::vector<int16_t> audioBuffer;
            piper::SynthesisResult result;

            piper::textToAudio(g_piperConfig, g_voice,
                             sentences[i], audioBuffer, result);

            if (audioBuffer.empty()) continue;

            bool isLast = (i == sentences.size() - 1);

            // Create Java short array and callback
            jshortArray jAudio = env->NewShortArray(audioBuffer.size());
            env->SetShortArrayRegion(jAudio, 0, audioBuffer.size(), audioBuffer.data());

            env->CallVoidMethod(callback, onAudioChunkMethod,
                              jAudio, (jint)g_sampleRate, (jboolean)isLast);

            env->DeleteLocalRef(jAudio);

            LOGD("Sentence %zu/%zu: %zu samples", i + 1, sentences.size(), audioBuffer.size());
        }

    } catch (const std::exception &e) {
        LOGE("Streaming synthesis failed: %s", e.what());
    }

    env->ReleaseStringUTFChars(text, inputText);
}

// ============================================================
// getSampleRate
// ============================================================
JNIEXPORT jint JNICALL
Java_com_nova_companion_voice_PiperJNI_getSampleRate(
        JNIEnv *env,
        jobject /* this */) {
    return g_sampleRate;
}

// ============================================================
// isInitialized
// ============================================================
JNIEXPORT jboolean JNICALL
Java_com_nova_companion_voice_PiperJNI_isInitialized(
        JNIEnv *env,
        jobject /* this */) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// release
// ============================================================
JNIEXPORT void JNICALL
Java_com_nova_companion_voice_PiperJNI_release(
        JNIEnv *env,
        jobject /* this */) {
    if (g_initialized) {
        g_initialized = false;
        LOGI("Piper resources released");
    }
}

} // extern "C"
