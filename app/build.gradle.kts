plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.videoeditor.tracket"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.236"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    externalNativeBuild {
      cmake {
        abiFilters("arm64-v8a", "x86_64")
      }
    }
  }

  // Native build: libam_engine.so (decoder pool + OpenGL renderer).
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }

  // Release signing is applied only when credentials are provided through the environment
  // (STORE_PASSWORD, KEY_PASSWORD, optional KEYSTORE_PATH). Without them the release build
  // is simply unsigned instead of failing. Debug builds use the standard Android debug key.
  val releaseStorePassword: String? = System.getenv("STORE_PASSWORD")
  signingConfigs {
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    if (releaseStorePassword != null) {
      create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
        storePassword = releaseStorePassword
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
    release {
      // Lossless PNG recompression (the AGP default) — shrinks res/ a little, no visual change.
      isCrunchPngs = true
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Always sign release builds so the APK is installable from CI.
      // Use a persistent release keystore for production; debug signing is a test fallback only.
      signingConfig = if (releaseStorePassword != null) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debugConfig")
      }
    }
  }

  // APK size (build 107): the x86 / x86_64 folders carry ONLY third-party dependency libraries
  // (MediaPipe, ONNX Runtime, xeno) for PC emulators — the app's own native code
  // (libam_engine.so) is arm64-v8a only and every real phone is ARM.
  // Excluding the two x86 ABIs from packaging removes ~81 MB of dead weight while the APK
  // stays a single universal file for ARM phones (arm64-v8a + armeabi-v7a, unchanged).
  packaging {
    jniLibs {
      excludes += listOf("lib/x86/**")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.coil.video)
  implementation(libs.onnxruntime.android)
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
