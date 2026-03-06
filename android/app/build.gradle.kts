plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.adverant.prosecreator.tts"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.adverant.prosecreator.tts"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // NanoHTTPD — lightweight HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // sherpa-onnx — on-device TTS with Kokoro model
    implementation("com.github.k2-fsa:sherpa-onnx:1.12.28")

    // OkHttp for model downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.json:json:20231013")
}
