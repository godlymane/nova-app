# Nova Companion ProGuard Rules

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep LlamaJNI class (accessed from native code)
-keep class com.nova.companion.inference.LlamaJNI { *; }

# Keep Compose
-dontwarn androidx.compose.**
