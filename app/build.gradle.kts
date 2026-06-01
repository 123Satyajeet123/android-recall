plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.brave.veloxcore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brave.veloxcore"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // test keys for now
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

  // Object Detection (Phase 1)
  implementation("com.google.mediapipe:tasks-vision:0.10.14")

  // Camera (Phase 1)
  implementation("androidx.camera:camera-camera2:1.4.1")
  implementation("androidx.camera:camera-lifecycle:1.4.1")
  implementation("androidx.camera:camera-view:1.4.1")

  // Android basics
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.core:core-ktx:1.15.0")

  // Database (Recall)
  implementation("androidx.room:room-runtime:2.6.1")
  implementation("androidx.room:room-ktx:2.6.1")
  ksp("androidx.room:room-compiler:2.6.1")
}