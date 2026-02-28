plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

fun getLocalProperty(key: String, defaultValue: String = ""): String {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) return defaultValue
    propsFile.readLines().forEach { line ->
        if (line.startsWith("$key=")) {
            return line.substringAfter("=")
        }
    }
    return defaultValue
}

android {
    namespace = "com.nova.companion"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.nova.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${getLocalProperty("ELEVENLABS_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"${getLocalProperty("ELEVENLABS_VOICE_ID")}\"")
        buildConfigField("String", "ELEVENLABS_AGENT_ID", "\"${getLocalProperty("ELEVENLABS_AGENT_ID", "agent_1001kjg9ge5cem")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${getLocalProperty("OPENAI_API_KEY")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${getLocalProperty("GEMINI_API_KEY")}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${getLocalProperty("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"${getLocalProperty("PICOVOICE_ACCESS_KEY")}\"")
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"${getLocalProperty("OPENWEATHER_API_KEY")}\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-DNDEBUG", "-fPIC")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-O0", "-g")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // WorkManager for scheduled notifications
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Accompanist - runtime permissions for mic access
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Cloud Bridge - OkHttp + SSE + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Compose animation (for voice UI effects)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-graphics")

    // Picovoice Porcupine — wake word detection
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // ElevenLabs Android SDK — Conversational AI with client tools
    implementation("io.elevenlabs:elevenlabs-android:0.7.2")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
