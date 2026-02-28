#!/bin/bash
echo "============================================"
echo " Nova - llama.cpp Setup Script"
echo "============================================"
echo ""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

LLAMA_DIR="app/src/main/cpp/llama.cpp"

if [ -f "$LLAMA_DIR/CMakeLists.txt" ]; then
    echo "[OK] llama.cpp already present at $LLAMA_DIR"
    echo "Updating to latest..."
    cd "$LLAMA_DIR"
    git pull
    cd "$SCRIPT_DIR"
else
    echo "[INFO] Cloning llama.cpp into $LLAMA_DIR..."
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
fi

echo ""
echo "============================================"
echo " Setup complete!"
echo ""
echo " Next steps:"
echo " 1. Open this folder in Android Studio"
echo " 2. Let Gradle sync (downloads NDK/CMake)"
echo " 3. Connect phone or start arm64 emulator"
echo " 4. Build & Run"
echo " 5. Copy .gguf model to phone Downloads/"
echo " 6. Tap 'Load Model' in the app"
echo "============================================"
