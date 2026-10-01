import java.net.URI

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.airgest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.airgest"
        // minSdk 26: continued gesture strokes (drag) need API 26, and camera
        // foreground services behave predictably from Android 8 onwards.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // MediaPipe maps the .task model straight from the APK, so it must not be compressed.
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // Camera (CameraX) + a LifecycleOwner-capable Service to bind it to
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.lifecycle.service)

    // MediaPipe Tasks - Hand Landmarker (runs fully on-device)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// ---------------------------------------------------------------------------
// Hand Landmarker model
// The official MediaPipe model bundle (~7.5 MB) is downloaded ONCE at build time
// into src/main/assets. It is bundled inside the APK, so the app itself never
// needs the INTERNET permission. If you build offline, download the file manually
// (see README.md) and place it at app/src/main/assets/hand_landmarker.task
// ---------------------------------------------------------------------------
val handModelFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

val downloadHandModel by tasks.registering {
    group = "air control"
    description = "Downloads the MediaPipe hand_landmarker.task model into src/main/assets"
    onlyIf { !handModelFile.exists() || handModelFile.length() < 1_000_000L }
    doLast {
        handModelFile.parentFile.mkdirs()
        println("Downloading hand_landmarker.task ...")
        try {
            URI(handModelUrl).toURL().openStream().use { input ->
                handModelFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            handModelFile.delete()
            throw GradleException(
                "Could not download hand_landmarker.task (" + e.message + "). " +
                "Download it manually from " + handModelUrl +
                " and save it as app/src/main/assets/hand_landmarker.task"
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadHandModel) }
