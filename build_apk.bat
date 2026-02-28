@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "C:\Users\devda\nova-app"

echo === Checking Java ===
java -version

echo === Generating Gradle Wrapper ===
if not exist gradlew.bat (
    echo Downloading gradle wrapper jar...
    mkdir gradle\wrapper 2>nul
    powershell -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'"
    
    REM Create gradlew.bat inline
    echo Creating gradlew.bat...
)

echo === Building Debug APK ===
call gradle\wrapper\gradle-wrapper.jar 2>nul
