@echo off
echo ============================================
echo  Nova - llama.cpp Setup Script
echo ============================================
echo.

cd /d "%~dp0"

set LLAMA_DIR=app\src\main\cpp\llama.cpp

if exist "%LLAMA_DIR%\CMakeLists.txt" (
    echo [OK] llama.cpp already present at %LLAMA_DIR%
    echo Updating to latest...
    cd "%LLAMA_DIR%"
    git pull
    cd /d "%~dp0"
) else (
    echo [INFO] Cloning llama.cpp into %LLAMA_DIR%...
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "%LLAMA_DIR%"
)

echo.
echo ============================================
echo  Setup complete!
echo.
echo  Next steps:
echo  1. Open this folder in Android Studio
echo  2. Let Gradle sync (it will download NDK/CMake)
echo  3. Connect your phone or start arm64 emulator
echo  4. Build ^& Run
echo  5. Copy your .gguf model to phone Downloads/
echo  6. Tap "Load Model" in the app
echo ============================================
pause
