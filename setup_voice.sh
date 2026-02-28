#!/bin/bash
# ============================================================
# Nova Voice Setup Script
# Sets up whisper.cpp and piper for Android ARM64 compilation
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_DIR="$SCRIPT_DIR/app/src/main/cpp"

echo "=== Nova Voice Setup ==="
echo "Setting up whisper.cpp and piper for Android ARM64..."
echo ""

# ── 1. Clone whisper.cpp ──────────────────────────────────────
echo "[1/4] Cloning whisper.cpp..."
if [ -d "$CPP_DIR/whisper.cpp" ]; then
    echo "  whisper.cpp already exists, pulling latest..."
    cd "$CPP_DIR/whisper.cpp" && git pull
else
    git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git "$CPP_DIR/whisper.cpp"
fi
echo "  Done."
echo ""

# ── 2. Clone piper ────────────────────────────────────────────
echo "[2/4] Cloning piper..."
if [ -d "$CPP_DIR/piper" ]; then
    echo "  piper already exists, pulling latest..."
    cd "$CPP_DIR/piper" && git pull
else
    git clone --depth 1 https://github.com/rhasspy/piper.git "$CPP_DIR/piper"
fi
echo "  Done."
echo ""

# ── 3. Download ONNX Runtime for Android ARM64 ───────────────
echo "[3/4] Setting up ONNX Runtime for Android..."
ONNX_DIR="$CPP_DIR/onnxruntime"
ONNX_VERSION="1.17.0"

if [ -d "$ONNX_DIR" ]; then
    echo "  ONNX Runtime already exists."
else
    mkdir -p "$ONNX_DIR"
    echo "  Downloading ONNX Runtime v${ONNX_VERSION} for Android..."

    # Download the Android AAR which contains prebuilt native libs
    ONNX_URL="https://github.com/nictar/onnxruntime-android/releases/download/v${ONNX_VERSION}/onnxruntime-android-${ONNX_VERSION}.aar"

    TEMP_AAR="/tmp/onnxruntime.aar"

    # Try downloading from Maven Central
    MAVEN_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ONNX_VERSION}/onnxruntime-android-${ONNX_VERSION}.aar"
    curl -L -o "$TEMP_AAR" "$MAVEN_URL" 2>/dev/null || {
        echo "  Maven download failed. Please manually download ONNX Runtime Android AAR."
        echo "  URL: $MAVEN_URL"
        echo "  Extract to: $ONNX_DIR/"
        echo ""
        echo "  Expected structure:"
        echo "    $ONNX_DIR/include/onnxruntime_cxx_api.h"
        echo "    $ONNX_DIR/lib/arm64-v8a/libonnxruntime.so"
    }

    if [ -f "$TEMP_AAR" ]; then
        echo "  Extracting..."
        cd "$ONNX_DIR"
        unzip -q "$TEMP_AAR" -d temp_aar

        # Extract native lib
        mkdir -p lib/arm64-v8a
        cp temp_aar/jni/arm64-v8a/libonnxruntime.so lib/arm64-v8a/ 2>/dev/null || true

        # Extract headers (may need separate download)
        mkdir -p include
        echo "  NOTE: Headers may need to be downloaded separately from:"
        echo "    https://github.com/microsoft/onnxruntime/releases/tag/v${ONNX_VERSION}"

        rm -rf temp_aar "$TEMP_AAR"
    fi
fi
echo "  Done."
echo ""

# ── 4. Download voice models ─────────────────────────────────
echo "[4/4] Model download instructions:"
echo ""
echo "  WHISPER MODEL (required for speech-to-text):"
echo "    Download: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
echo "    Size: ~75MB"
echo "    Copy to: /sdcard/Download/ggml-tiny.en.bin"
echo ""
echo "  PIPER VOICE MODEL (required for text-to-speech):"
echo "    Download voice + config:"
echo "      https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/amy/medium/en_US-amy-medium.onnx"
echo "      https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/amy/medium/en_US-amy-medium.onnx.json"
echo "    Size: ~60MB (model) + ~5KB (config)"
echo "    Copy both to: /sdcard/Download/"
echo ""
echo "  Alternative voice (more natural but larger):"
echo "      https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/en_US-lessac-medium.onnx"
echo "      https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"
echo ""

echo "=== Setup Complete ==="
echo ""
echo "Next steps:"
echo "  1. Open project in Android Studio"
echo "  2. Connect ARM64 device (or use ARM64 emulator)"
echo "  3. Build and run"
echo "  4. Copy voice model files to device Downloads folder"
echo "  5. In Nova app, tap the Voice toggle in the top bar"
echo ""
echo "Troubleshooting:"
echo "  - If CMake fails, ensure NDK r26+ is installed via SDK Manager"
echo "  - If whisper.cpp has API changes, check the latest whisper.h header"
echo "  - If ONNX Runtime headers are missing, download from GitHub releases"
