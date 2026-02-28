@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d C:\Users\devda\nova-app

echo === Generating Gradle Wrapper ===
call "C:\Users\devda\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat" wrapper
echo === Wrapper generation done ===

echo === Building Debug APK ===
call gradlew.bat assembleDebug
echo === Build done, exit code: %ERRORLEVEL% ===
