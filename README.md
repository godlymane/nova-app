# Nova

**On-device AI companion — runs entirely on your phone.**

Nova is a personal AI companion that remembers your conversations, speaks naturally, and never sends data to the cloud. Built with llama.cpp inference, local speech processing, and a persistent memory system.

## Architecture

- **Capacitor** hybrid shell (WebView UI + native plugins)
- **llama.cpp** — Nanbeige4.1-3B, Q4_K_M quantized (~2.3GB)
- **Whisper tiny** — local speech-to-text
- **Piper TTS** — local text-to-speech
- **SQLite** — conversation history, extracted memories, user profile
- **WorkManager** — proactive notifications and check-ins

## Project Structure

```
nova-app/
├── src/
│   ├── app/              # Capacitor app (HTML/CSS/JS + Android)
│   ├── inference/         # llama.cpp integration
│   ├── voice/            # Whisper STT + Piper TTS
│   ├── memory/           # SQLite memory system
│   └── notifications/    # Proactive triggers
├── models/               # Model files (git-ignored)
├── docs/
│   ├── ARCHITECTURE.md   # Technical architecture
│   └── TRAINING.md       # Model training guide
└── README.md
```

## Setup

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full technical details and build instructions.

See [docs/TRAINING.md](docs/TRAINING.md) for model fine-tuning guide.

## License

Private — All rights reserved.
