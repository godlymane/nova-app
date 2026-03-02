# Nova

**On-device AI companion for Android — your personal assistant that remembers, speaks, and acts.**

Nova is a native Android AI companion built with Kotlin + Jetpack Compose. She routes between local on-device inference and cloud LLMs, controls your phone via accessibility automation, speaks naturally with ElevenLabs voice, and remembers everything about you.

## Architecture

- **Kotlin + Jetpack Compose** — Fully native Android (min SDK 26, target SDK 35)
- **Multi-mode routing** — Local GGUF, Cloud (OpenAI GPT-4o / Gemini / Claude), Voice (ElevenLabs / local Whisper+Piper), Automation
- **llama.cpp** — Nanbeige4.1-3B, Q4_K_M quantized (~2.3GB) for on-device chat
- **Whisper tiny** — Local speech-to-text (39M params)
- **Piper TTS** — Local text-to-speech (en_US-lessac-medium)
- **ElevenLabs** — Cloud conversational AI via WebSocket (bidirectional voice)
- **Picovoice Porcupine** — "Hey Nova" wake word detection (24/7 background)
- **Room + SQLite** — Conversations, extracted memories, user profile, daily summaries
- **WorkManager** — Proactive notifications, morning/evening check-ins
- **AccessibilityService** — Full UI automation (tap, type, scroll, read screen)
- **44 tools across 4 tiers** — From opening apps to ordering food

## Project Structure

```
nova-app/
├── app/src/main/java/com/nova/companion/
│   ├── ui/              # Jetpack Compose UI (chat, onboarding, aura effects, theme)
│   ├── tools/           # 44 tool executors across 4 permission tiers
│   ├── brain/           # Context engine, memory extraction, proactive triggers
│   ├── voice/           # ElevenLabs, Porcupine wake word, local STT/TTS
│   ├── cloud/           # Cloud LLM routing (OpenAI, Gemini, Claude)
│   ├── inference/       # Local GGUF inference via llama.cpp
│   ├── data/            # Room database entities and DAOs
│   ├── notification/    # Push notifications, reminders
│   ├── accessibility/   # UI automation, accessibility service
│   ├── core/            # NovaRouter (mode switching), system prompt
│   ├── memory/          # Memory extraction and retrieval
│   └── overlay/         # Aura visual overlay service
├── app/src/main/assets/ # Porcupine wake word model (.ppn)
├── docs/
│   ├── ARCHITECTURE.md  # Detailed technical architecture
│   └── TRAINING.md      # Model fine-tuning guide
├── build.gradle.kts     # Root build config
├── app/build.gradle.kts # App dependencies and API key injection
└── local.properties     # API keys (git-ignored, never committed)
```

## Setup

1. Clone the repo
2. Copy API keys into `local.properties`:
   ```properties
   OPENAI_API_KEY=sk-...
   ELEVENLABS_API_KEY=...
   ELEVENLABS_VOICE_ID=...
   ELEVENLABS_AGENT_ID=...
   PICOVOICE_ACCESS_KEY=...
   GEMINI_API_KEY=...          # optional — Gemini fallback
   ANTHROPIC_API_KEY=...       # optional — Claude fallback
   ```
3. Open in Android Studio, sync Gradle, build & run
4. On first launch: grant permissions, enable Accessibility Service for full automation

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full technical details.

## License

Private — All rights reserved.
