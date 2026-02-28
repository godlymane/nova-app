@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d C:\Users\devda\nova-app
echo BUILD_START
call gradlew.bat assembleDebug --stacktrace 2>&1
echo BUILD_EXIT_CODE=%ERRORLEVEL%
