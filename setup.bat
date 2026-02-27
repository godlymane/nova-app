@echo off
REM ── Nova App Setup Script ──
REM Run this from C:\Users\devda\Projects\

echo === Nova AI Companion — Project Setup ===
echo.

REM Step 1: Create GitHub repo using gh CLI
echo [1/4] Creating private GitHub repo...
gh repo create nova-app --private --description "On-device AI companion - Nova" --clone
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to create repo. Make sure 'gh' CLI is installed and authenticated.
    echo Install: winget install GitHub.cli
    echo Auth: gh auth login
    pause
    exit /b 1
)

REM Step 2: Copy project files into the cloned repo
echo [2/4] Copying project files...
xcopy /E /I /Y nova-app-files\* nova-app\
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to copy files. Make sure nova-app-files folder exists.
    pause
    exit /b 1
)

REM Step 3: Install npm dependencies
echo [3/4] Installing Capacitor dependencies...
cd nova-app\src\app
call npm install
cd ..\..\..

REM Step 4: Git commit and push
echo [4/4] Committing and pushing...
cd nova-app
git add -A
git commit -m "Initial project setup + architecture docs + chat UI"
git push -u origin main
cd ..

echo.
echo === Done! Repo is live at: https://github.com/devdattareddy/nova-app ===
pause
