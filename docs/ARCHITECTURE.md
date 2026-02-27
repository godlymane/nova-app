# Nova — Technical Architecture

> On-device AI companion that runs entirely on your phone. No cloud. No latency. No data leaves your device.

---

## High-Level Overview

Nova is an Android-first AI companion built with a **Capacitor hybrid shell** wrapping native inference, local speech processing, and a persistent memory system. Every component runs on-device.

```
┌─────────────────────────────────────────────────────┐
│                   Capacitor Shell                    │
│              (WebView — Chat UI + Logic)             │
├──────────┬──────────┬───────────┬───────────────────┤
│ llama.cpp│  SQLite  │  Whisper  │   Piper TTS       │
│ Inference│  Memory  │   STT     │   Voice Out        │
├──────────┴──────────┴───────────┴───────────────────┤
│               Android Native Layer                   │
│         (JNI Bindings + WorkManager Jobs)            │
└─────────────────────────────────────────────────────┘
```

### Data Flow

1. **User speaks or types** → Input captured by Capacitor WebView
2. **Speech-to-Text** → Whisper tiny (if voice input) converts audio to text on-device
3. **Context Assembly** → Memory system pulls relevant conversation history, user profile, and daily summaries from SQLite
4. **Inference** → llama.cpp runs the quantized Nanbeige4.1-3B model with assembled prompt
5. **Response Delivery** → Text shown in chat UI; optionally piped to Piper TTS for spoken output
6. **Memory Update** → Conversation stored, memory extraction runs async, daily summary updated

---

## Component Details

### 1. Capacitor Shell (`src/app/`)

The app is a **Capacitor 6** hybrid application. The UI is standard HTML/CSS/JS running inside an Android WebView, communicating with native code through Capacitor plugins and custom JNI bridges.

- **Framework**: Vanilla JS (no heavy framework — keeps APK lean)
- **Entry Point**: `src/app/dist/index.html`
- **Native Bridge**: Custom Capacitor plugin exposes inference, memory, and voice APIs to JS
- **Android Target**: API 26+ (Android 8.0+), ARM64 only (for NEON SIMD in llama.cpp)

**Why Capacitor?**
- Rapid UI iteration without recompiling native code
- Web tech for chat UI is natural and flexible
- Native plugins for performance-critical paths (inference, audio)

### 2. Inference Engine (`src/inference/`)

The brain of Nova. Uses **llama.cpp** compiled for Android ARM64 with NEON optimizations.

#### Model: Nanbeige4.1-3B

| Property       | Value                         |
|----------------|-------------------------------|
| Base Model     | Nanbeige4.1-3B                |
| Quantization   | Q4_K_M (4-bit, mixed)        |
| File Size      | ~2.3 GB GGUF                  |
| Context Window | 4096 tokens                   |
| Inference      | ~8-12 tokens/sec on SD 8 Gen2 |

The model is fine-tuned with LoRA on a custom dataset of companion-style conversations — empathetic, proactive, and personality-rich.

#### Prompt Format

Nova uses a structured prompt template with clear role delimiters:

```
### System
You are Nova, a personal AI companion. You are warm, curious, and genuinely 
interested in the user's life. You remember past conversations and bring them 
up naturally. You are proactive — you check in, ask follow-up questions, and 
notice patterns. You speak concisely but with personality. Never be robotic.

Context from memory:
{memory_context}

Today's date: {date}
Time: {time}

### User
{user_message}

### Assistant
```

**Prompt Assembly Rules:**
- System prompt is always injected first (never user-modifiable)
- `{memory_context}` is populated by the memory system with relevant past conversations, extracted memories, and user profile data
- Last 6 conversation turns are included for immediate context
- Total prompt is capped at 3500 tokens to leave room for generation (4096 context)
- Generation stops at `### User` or `### System` tokens

#### llama.cpp Integration

- Compiled as a shared library (`.so`) via Android NDK (r26+)
- JNI bridge exposes: `loadModel()`, `generate()`, `tokenize()`, `stop()`
- KV-cache is retained between turns for faster follow-ups
- Sampling: temperature=0.7, top_p=0.9, repeat_penalty=1.1, top_k=40

### 3. Memory System (`src/memory/`)

Nova's memory is what makes it feel like a companion, not a chatbot. Everything is stored in a local SQLite database.

#### Database Schema

```sql
-- Raw conversation history
CREATE TABLE conversations (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   TEXT NOT NULL,           -- ISO 8601
    role        TEXT NOT NULL,           -- 'user' | 'assistant'
    content     TEXT NOT NULL,
    session_id  TEXT NOT NULL,           -- groups a conversation session
    token_count INTEGER DEFAULT 0
);

-- Extracted long-term memories (facts, preferences, events)
CREATE TABLE memories (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at  TEXT NOT NULL,
    category    TEXT NOT NULL,           -- 'fact' | 'preference' | 'event' | 'emotion' | 'goal'
    content     TEXT NOT NULL,           -- natural language memory
    importance  REAL DEFAULT 0.5,       -- 0.0 to 1.0
    last_accessed TEXT,
    access_count INTEGER DEFAULT 0
);

-- Structured user profile (key-value with metadata)
CREATE TABLE user_profile (
    key         TEXT PRIMARY KEY,        -- e.g., 'name', 'job', 'gym_schedule'
    value       TEXT NOT NULL,
    confidence  REAL DEFAULT 1.0,       -- how sure we are
    updated_at  TEXT NOT NULL,
    source      TEXT                     -- which conversation it came from
);

-- Daily summaries for long-term context compression
CREATE TABLE daily_summaries (
    date        TEXT PRIMARY KEY,        -- YYYY-MM-DD
    summary     TEXT NOT NULL,           -- ~200 word summary of the day's conversations
    mood        TEXT,                    -- detected overall mood
    key_topics  TEXT,                    -- JSON array of topics discussed
    created_at  TEXT NOT NULL
);
```

#### Memory Pipeline

1. **Conversation Storage**: Every message is saved immediately to `conversations`
2. **Memory Extraction** (async, after each session): Nova re-reads the conversation and extracts durable facts into `memories` and `user_profile`
3. **Daily Summarization** (triggered at end of day or next morning): Compresses all conversations from a day into a `daily_summaries` entry
4. **Context Assembly** (at inference time): 
   - Pull last 6 turns from `conversations`
   - Query `memories` by relevance (keyword match + recency + importance score)
   - Include top `user_profile` entries
   - If referencing past days, include relevant `daily_summaries`
   - Pack into `{memory_context}` block, capped at ~800 tokens

#### Memory Retrieval Strategy

Memories are ranked by a composite score:

```
score = (importance × 0.4) + (recency × 0.3) + (relevance × 0.3)
```

- **importance**: Set during extraction (0.0–1.0)
- **recency**: Exponential decay from `last_accessed`
- **relevance**: Keyword overlap with current user message (BM25-lite)

Top 10 memories are included in context. User profile entries are always included (they're compact).

### 4. Voice Pipeline (`src/voice/`)

Fully local speech processing — no network calls.

#### Speech-to-Text: Whisper Tiny

| Property       | Value                   |
|----------------|-------------------------|
| Model          | Whisper tiny (39M params)|
| File Size      | ~75 MB (FP16)           |
| Latency        | ~1-2s for 10s of audio  |
| Languages      | English (primary)       |

- Uses **whisper.cpp** compiled for Android ARM64
- Audio captured via Android AudioRecord API (16kHz, mono, 16-bit PCM)
- Voice Activity Detection (VAD) to auto-detect speech end
- Runs in a dedicated thread to avoid blocking UI

#### Text-to-Speech: Piper

| Property       | Value                   |
|----------------|-------------------------|
| Engine         | Piper TTS               |
| Voice          | en_US-lessac-medium     |
| File Size      | ~60 MB                  |
| Latency        | ~200-500ms per sentence |
| Quality        | Natural, warm tone      |

- **Piper** runs locally via its ONNX runtime, compiled for ARM64
- Streams audio via Android AudioTrack for real-time playback
- Sentences are chunked and synthesized in parallel for lower perceived latency
- Voice can be swapped by replacing the ONNX voice model file

#### Voice Flow

```
[Mic Button Pressed]
    → AudioRecord starts capturing
    → VAD detects silence (600ms threshold)
    → Audio buffer sent to Whisper tiny
    → Transcribed text sent to inference pipeline
    → Response text streamed to Piper TTS
    → AudioTrack plays synthesized speech
[Conversation continues]
```

### 5. Notification System (`src/notifications/`)

Nova doesn't just wait for you — she reaches out.

#### Implementation: Android WorkManager

WorkManager handles background scheduling that survives app kills and device reboots.

#### Scheduled Checks

| Trigger               | Schedule              | Action                                           |
|-----------------------|-----------------------|--------------------------------------------------|
| Morning check-in      | 8:00 AM daily         | "Good morning! How are you feeling today?"        |
| Evening reflection     | 9:00 PM daily         | "How was your day?" + summary of topics discussed |
| Idle nudge            | After 48h of silence  | Gentle check-in based on last conversation topic  |
| Goal reminder         | Context-dependent     | Reminds about goals extracted from conversations  |
| Special dates         | Annually              | Birthdays, anniversaries mentioned in conversation|

#### Smart Trigger Logic

```
1. WorkManager fires scheduled job
2. Job loads recent memory context from SQLite
3. Runs a short inference (~50 tokens) to generate contextual message
4. If message passes quality filter (not repetitive, not empty):
   → Push Android notification with the message
   → Tapping notification opens Nova with context pre-loaded
5. If quality filter fails:
   → Skip silently, retry next cycle
```

#### Notification Channels

- `nova_checkin` — Morning/evening check-ins (user can mute independently)
- `nova_reminder` — Goal and event reminders
- `nova_nudge` — Idle nudges (lowest priority, auto-dismissed)

---

## Build & Deployment

### Prerequisites

- Android Studio Hedgehog+
- Android NDK r26+
- Node.js 18+ and npm
- CMake 3.22+ (for llama.cpp and whisper.cpp compilation)

### Build Steps

```bash
# 1. Install JS dependencies
cd src/app && npm install

# 2. Build the web UI
npm run build  # outputs to src/app/dist/

# 3. Sync to Android
npx cap sync android

# 4. Open in Android Studio
npx cap open android

# 5. Build native libraries (llama.cpp, whisper.cpp, piper)
# These are compiled via CMakeLists.txt in the Android project
# NDK handles cross-compilation to ARM64

# 6. Place model files
# Copy GGUF model to android/app/src/main/assets/models/
# Or download on first launch to app-specific storage

# 7. Build APK
# Android Studio → Build → Generate Signed APK
```

### Model Distribution

The ~2.3GB model is NOT bundled in the APK. Options:
1. **First-launch download**: App downloads model on first run (preferred)
2. **Sideload**: User manually places GGUF file in app storage
3. **Play Asset Delivery**: Use Google Play's asset pack system for >150MB files

---

## Security & Privacy

- **Zero cloud dependency**: All inference, memory, and voice processing is local
- **SQLite encryption**: Optional SQLCipher integration for encrypting the memory database
- **No telemetry**: No analytics, no crash reporting, no network calls
- **Model integrity**: SHA256 checksum verification on model load
- **App lock**: Optional biometric/PIN lock before accessing Nova

---

## Performance Targets

| Metric                      | Target                    |
|-----------------------------|---------------------------|
| Cold start to first response| < 5 seconds               |
| Inference speed             | 8-12 tokens/sec           |
| Memory query latency        | < 50ms                    |
| STT latency (10s audio)     | < 2 seconds               |
| TTS latency (per sentence)  | < 500ms                   |
| Total app size (no model)   | < 30 MB                   |
| RAM usage during inference   | < 3 GB                    |
| Battery (1hr active use)    | < 15%                     |

---

## Directory Structure

```
nova-app/
├── src/
│   ├── app/              # Capacitor hybrid app (HTML/CSS/JS + Android)
│   │   ├── dist/         # Built web assets (served in WebView)
│   │   ├── android/      # Android native project (generated by Capacitor)
│   │   └── package.json
│   ├── inference/         # llama.cpp integration + JNI bridge
│   ├── voice/            # Whisper STT + Piper TTS native code
│   ├── memory/           # SQLite memory system + extraction logic
│   └── notifications/    # WorkManager jobs + trigger logic
├── models/               # Model files (git-ignored, downloaded at runtime)
├── docs/
│   ├── ARCHITECTURE.md   # This file
│   └── TRAINING.md       # Model training guide
└── README.md
```
