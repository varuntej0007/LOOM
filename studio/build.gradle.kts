// LOOM Studio (Plan B): a standalone host app for any on-device model, wrapped in LOOM's
// virtual-chiplet runtime. Separate module; the evaluated camera app in :app is untouched.
// Versions match :app exactly, so the first build needs nothing new from the network.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.loom.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loom.studio"
        minSdk = 30 // PowerManager.getThermalHeadroom() needs API 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.4.0-m4"
        // The iQOO 15 is arm64; native runtimes (LiteRT, llama.cpp) will ship arm64 only.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        // Debug builds only for now. No release signing config: the release keystore is not in
        // the repo and Studio must not use it.
        release {
            isMinifyEnabled = false
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
    }

    testOptions {
        // Unit tests cover pure-Kotlin logic (inspector rules, Weaver decisions, planner);
        // Android calls such as Log return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    // Imported models are memory-mapped from app-private storage; bundled samples (later) must
    // stay uncompressed in the APK for the same reason.
    androidResources {
        noCompress += listOf("tflite", "task", "gguf", "onnx")
    }
}

dependencies {
    val lifecycle = "2.8.7"
    val cameraX = "1.4.1"   // same as :app
    // LiteRT 1.4.x = the TensorFlow Lite Interpreter API, Java only (no Kotlin-version coupling
    // with this repo's Kotlin 2.0.21). LiteRT 2.x (CompiledModel API) pulls lifecycle 2.10 /
    // Kotlin 2.2 artifacts that this toolchain cannot consume.
    val litert = "1.4.2"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu-api:$litert")

    testImplementation("junit:junit:4.13.2")
}
