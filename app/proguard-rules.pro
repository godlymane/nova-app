# Nova Companion ProGuard Rules
# ─────────────────────────────────────────────────────────────────

# ── JNI / Native methods ──────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Nova JNI bridges (accessed from native code via RegisterNatives) ──
-keep class com.nova.companion.inference.LlamaJNI { *; }
-keep class com.nova.companion.voice.WhisperJNI { *; }
-keep class com.nova.companion.voice.PiperJNI { *; }

# ── JNI callback interfaces (called from native code) ─────────────────
-keep class com.nova.companion.voice.WhisperSegmentCallback { *; }
-keep class com.nova.companion.voice.PiperAudioCallback { *; }
-keep interface com.nova.companion.voice.WhisperSegmentCallback { *; }
-keep interface com.nova.companion.voice.PiperAudioCallback { *; }

# ── Picovoice Porcupine (wake word detection) ───────────────────────
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# ── ElevenLabs voice service ──────────────────────────────────────────
-keep class com.nova.companion.voice.ElevenLabsVoiceService { *; }
-keep class com.nova.companion.cloud.ElevenLabsTTS { *; }
-keep class com.nova.companion.cloud.CloudConfig { *; }

# ── Gson (JSON parsing for ElevenLabs WebSocket, OpenAI, etc.) ───────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── OkHttp ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Room Database ─────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Kotlin Coroutines ─────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Jetpack Compose ───────────────────────────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ── AndroidX / Lifecycle ──────────────────────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ── WorkManager (notification scheduling) ─────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Nova service classes (accessed via intent/manifest) ───────────────
-keep class com.nova.companion.voice.WakeWordService { *; }
-keep class com.nova.companion.notification.BootReceiver { *; }
-keep class com.nova.companion.notification.** { *; }

# ── BuildConfig (accessed reflectively by WakeWordService) ────────────
-keep class com.nova.companion.BuildConfig { *; }

# ── Enums (used in VoiceState, ConnectionState, RouteType, etc.) ──────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Suppress warnings for optional dependencies ──────────────────────
-dontwarn java.lang.invoke.StringConcatFactory