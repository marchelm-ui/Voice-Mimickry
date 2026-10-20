plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kenku.mimic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kenku.mimic"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Pixel phones are 64-bit ARM.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    androidResources {
        noCompress += listOf("onnx", "bin", "txt", "json")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // sherpa-onnx and onnxruntime-android both ship onnxruntime 1.28.2; keep one copy.
            pickFirsts += listOf("lib/arm64-v8a/libonnxruntime.so")
        }
    }

    signingConfigs {
        create("kenku") {
            storeFile = file("kenku.keystore")
            storePassword = "kenkuvoice"
            keyAlias = "kenku"
            keyPassword = "kenkuvoice"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kenku")
        }
        debug { signingConfig = signingConfigs.getByName("kenku") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true     // Robolectric reads the real assets
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(files("libs/sherpa-onnx.aar"))
    // onnxruntime Java bindings, same 1.28.2 build sherpa-onnx links against (downloaded by CI)
    implementation(files("libs/onnxruntime-android.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
}
