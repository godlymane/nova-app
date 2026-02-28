# Nova Voice System — Setup & Architecture

## Overview

Nova's voice system adds fully offline speech-to-text (Whisper) and text-to-speech (Piper) capabilities. All processing runs on-device with no internet required.

**Voice conversation flow:**
```
Mic → AudioRecord (16kHz PCM) → whisper.cpp (STT) → LLM (Nanbeige) → Piper TTS → AudioTrack → Speaker
```

## Architecture

```
com.nova.companion.voice/
├── WhisperJNI.kt          # JNI bindings → whisper_jni.cpp → whisper.cpp
├── PiperJNI.kt            # JNI bindings → piper_jni.cpp → piper + ONNX RT
├── AudioRecorder.kt       # Android AudioRecord, 16kHz mono, VAD
├── WhisperSTT.kt          # High-level STT (record → transcribe)
├── PiperTTS.kt            # High-level TTS (synthesize → AudioTrack)
└── VoiceManager.kt        # Orchestrates full pipeline + state machine

com.nova.companion.ui/
├── VoiceComponents.kt     # MicButton, SoundWaveVisualizer, VoiceStateIndicator
├── NovaTestScreen.kt      # Updated: voice/text mode toggle, voice input bar
└── NovaViewModel.kt       # Updated: voice state flows, voice controls

app/src/main/cpp/
├── whisper_jni.cpp         # C++ JNI bridge for WhisperJNI.kt
├── piper_jni.cpp           # C++ JNI bridge for PiperJNI.kt
├── whisper.cpp/            # [git submodule] whisper.cpp source
├── piper/                  # [git submodule] piper source
├── onnxruntime/            # Prebuilt ONNX Runtime for Android ARM64
│   ├── include/
│   └── lib/arm64-v8a/libonnxruntime.so
└── CMakeLists.txt          # Updated: builds nova_whisper.so + nova_piper.so
```

## Voice State Machine

```
IDLE → LISTENING → TRANSCRIBING → THINKING → SPEAKING → IDLE
  ↑                                                        |
  └────────────────────────────────────────────────────────┘
```

- **IDLE**: Voice mode on, waiting for mic press
- **LISTENING**: Recording from mic (red pulse animation)
- **TRANSCRIBING**: Whisper processing audio (thinking dots)
- **THINKING**: LLM generating response (thinking dots)
- **SPEAKING**: Piper playing audio (wave animation)

User can interrupt SPEAKING by tapping the mic button.

## Setup Instructions

### Prerequisites
- Android Studio (latest stable)
- NDK r26+ (install via SDK Manager → SDK Tools → NDK)
- CMake 3.22.1+ (install via SDK Manager → SDK Tools → CMake)
- Physical ARM64 device (or ARM64 emulator)

### Step 1: Clone Native Dependencies

Run the setup script:
```bash
cd nova-app
chmod +x setup_voice.sh
./setup_voice.sh
```

Or manually:
```bash
cd app/src/main/cpp

# whisper.cpp
git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git

# piper
git clone --depth 1 https://github.com/rhasspy/piper.git
```

### Step 2: ONNX Runtime

Piper TTS requires ONNX Runtime for Android. Download from:
- Maven: `com.microsoft.onnxruntime:onnxruntime-android:1.17.0`
- Or GitHub releases: https://github.com/microsoft/onnxruntime/releases

Extract to `app/src/main/cpp/onnxruntime/`:
```
onnxruntime/
├── include/
│   └── onnxruntime_cxx_api.h  (and other headers)
└── lib/
    └── arm64-v8a/
        └── libonnxruntime.so
```

### Step 3: Voice Models (copy to device)

**Whisper tiny model** (~75MB):
```
https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin
```
Copy to device: `/sdcard/Download/ggml-tiny.en.bin`

**Piper voice model** (~60MB + config):
```
https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/amy/medium/en_US-amy-medium.onnx
https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/amy/medium/en_US-amy-medium.onnx.json
```
Copy both to device: `/sdcard/Download/`

### Step 4: Build & Run

1. Open project in Android Studio
2. Sync Gradle
3. Connect ARM64 device
4. Build → Run

## Usage

1. Load the LLM model (existing flow)
2. Tap the **Voice/Text** toggle in the top bar
3. First toggle loads Whisper + Piper models (~5-10s)
4. **Hold** the mic button to speak
5. **Release** to send (or wait 3s silence for auto-send)
6. Nova responds with text + voice
7. **Tap** while Nova is speaking to interrupt

## Native Libraries Built

| Library | File | Source | Purpose |
|---------|------|--------|---------|
| `nova_llama` | `libnova_llama.so` | llama.cpp | LLM inference |
| `nova_whisper` | `libnova_whisper.so` | whisper.cpp | Speech-to-text |
| `nova_piper` | `libnova_piper.so` | piper + ONNX RT | Text-to-speech |

## Key Configuration

### AudioRecorder
- Sample rate: 16kHz (Whisper requirement)
- Format: Mono, 16-bit PCM
- VAD silence threshold: 300 RMS
- VAD silence duration: 3 seconds
- Max recording: 30 seconds

### Whisper
- Model: ggml-tiny.en (~75MB)
- Threads: 4
- Language: English
- Sampling: Greedy (fastest)
- Expected latency: ~1-2s for 10s audio

### Piper TTS
- Voice: en_US-amy-medium (~60MB)
- Sample rate: 22050Hz (model-dependent)
- Streaming: Sentence-by-sentence synthesis
- Expected latency: ~200-500ms to first audio

## Troubleshooting

**CMake build fails:**
- Ensure NDK r26+ is installed
- Check `app/src/main/cpp/whisper.cpp/` exists
- Check `app/src/main/cpp/piper/` exists
- Verify ONNX Runtime headers at `onnxruntime/include/`

**Voice models not found:**
- Check `/sdcard/Download/` for model files
- Check logcat for `VoiceManager` tag
- Grant storage permissions in device settings

**Mic not working:**
- Grant RECORD_AUDIO permission (runtime request)
- Check `AudioRecorder` tag in logcat
- Ensure no other app is using the microphone

**No audio output:**
- Check device volume and media routing
- Check `PiperTTS` tag in logcat
- Verify Piper model + config paths match

**whisper.cpp API changes:**
- If whisper.h header has changed, update `whisper_jni.cpp`
- Key functions: `whisper_init_from_file_with_params`, `whisper_full`, `whisper_full_n_segments`
