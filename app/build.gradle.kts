plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vboard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vboard.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // sherpa-onnx ships arm64-v8a, armeabi-v7a and x86_64 .so files, and with no
            // filter all three land in the APK against a < 30 MB budget (PRODUCT_SPEC
            // VB-621). arm64-v8a is the only ABI the target hardware uses: 32-bit-only
            // Android phones are gone, and Play has required a 64-bit build since 2019.
            // x86_64 covers emulators only; add an emulator-friendly variant if that ever
            // needs to change, rather than shipping it to every user.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    lint {
        // Reported and uploaded as CI artifacts; tightened to abort once the
        // baseline is established.
        abortOnError = false
    }

    packaging {
        jniLibs {
            // sherpa-onnx ships arm64-v8a/armeabi-v7a/x86_64; keep them uncompressed.
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // Model downloads: WorkManager owns the retry/constraint/process-death story that a
    // START_NOT_STICKY foreground service could not (PRODUCT_SPEC VB-402).
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // On-device speech recognition (streaming Zipformer + Parakeet TDT).
    implementation(libs.sherpa.onnx)
    // On-device LLM refinement (MediaPipe LLM Inference).
    implementation(libs.mediapipe.tasks.genai)
    // tar.bz2 extraction for downloaded ASR model archives.
    implementation(libs.commons.compress)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ------------------------------------------------------------------ unit tests
// Bootstrap of the JVM test source set in :app (app/src/test). Robolectric and
// Espresso were already declared, but nothing had ever been compiled against
// them and there was no test wiring at all; the block below is what makes
// `:app:testDebugUnitTest` actually run, for every test in the source set.
//
// Two engines on purpose: this module runs the JUnit Platform (junit-jupiter),
// while Robolectric is a JUnit 4 runner and cannot be driven by Jupiter. The
// vintage engine runs the @RunWith(RobolectricTestRunner) classes alongside
// plain Jupiter ones, so a test that needs no Android framework does not have to
// pay for a Robolectric sandbox.
//
// Versions are literals rather than catalog aliases so this hunk touches only
// this file; fold them into gradle/libs.versions.toml when the catalog is next
// edited (junit4 4.13.2, junit-platform 1.11.3 == junit-jupiter 5.11.3).
android {
    testOptions {
        // Robolectric needs the merged resources and the manifest to build its
        // application under test.
        unitTests.isIncludeAndroidResources = true
        // Non-Robolectric tests get stubbed android.jar returns instead of
        // "Method ... not mocked" for the odd incidental framework call.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // ApplicationProvider and friends for Robolectric tests.
    testImplementation(libs.androidx.junit)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}
