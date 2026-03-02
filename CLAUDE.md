# Nova Companion App

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

**Requirements:** Android Studio, JDK 17, Android SDK 35, NDK (for llama.cpp native libs).

API keys go in `local.properties` (git-ignored). See README.md for the full list.

## Architecture

- **Package:** `com.nova.companion`
- **Language:** Kotlin, Jetpack Compose (Material3)
- **Min SDK:** 26 (Android 8.0) | **Target SDK:** 35
- **ABI:** arm64-v8a only

### Key Patterns

- **Routing:** `NovaRouter` classifies messages → `TEXT_LOCAL`, `TEXT_CLOUD`, `AUTOMATION`, `VOICE_ELEVEN`, `VOICE_LOCAL`
- **Tools:** 4-tier system in `tools/tier1..tier4/`. Each tool is a singleton `object` with `register(registry)` pattern.
- **Cloud:** `CloudLLMService` handles agentic tool loops (up to 8 rounds). `InferenceRouter` handles streaming chat with provider fallback.
- **Voice:** `WakeWordService` (Porcupine) → `ElevenLabsVoiceService` (WebSocket) or local Whisper+Piper.
- **Memory:** `MemoryManager` extracts facts from conversations. `ContextEngine` collects device state every 5 min.
- **Database:** Room with entities: `MessageEntity`, `Memory`, `UserProfileEntry`, `DailySummary`, `ContactCache`.

### Conventions

- API keys are injected via `BuildConfig` from `local.properties` — never hardcode them.
- All cloud calls go through `CloudConfig` for key validation and network checks.
- Tool executors return `ToolResult(success: Boolean, message: String)`.
- Deprecated Android APIs must use version-gated `if (Build.VERSION.SDK_INT >= ...)` with `@Suppress("DEPRECATION")` on the else branch.
- Nova's personality: casual bro-talk, no emojis, short responses unless asked for detail.

## Important Files

| File | Purpose |
|------|---------|
| `core/NovaRouter.kt` | Message routing and intent detection |
| `cloud/CloudLLMService.kt` | Agentic cloud LLM with tool calling |
| `cloud/CloudConfig.kt` | API key management and network state |
| `inference/InferenceRouter.kt` | Cloud/local inference routing with provider fallback |
| `ui/chat/ChatViewModel.kt` | Main state management and message orchestration |
| `voice/WakeWordService.kt` | "Hey Nova" background wake word |
| `voice/ElevenLabsVoiceService.kt` | Cloud voice WebSocket |
| `tools/ToolRegistry.kt` | Tool registration and lookup |
| `brain/context/ContextEngine.kt` | Device context collection service |
| `memory/MemoryManager.kt` | Memory extraction and retrieval |
