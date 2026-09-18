import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

// CI-tunable knobs:
//   -Paxis.abis=arm64-v8a,x86_64     -> restrict native ABIs (e.g. emulator e2e uses x86_64)
//   -Paxis.splitApks=true             -> enable APK splits per ABI (release)
//   -Paxis.buildNumber=42             -> versionCode from CI run number
//   -Paxis.ccache=true                -> use ccache launcher for native compilation
val defaultAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val axisAbis: List<String> = (
    project.findProperty("axis.abis") as? String
    )?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "all" } ?: defaultAbis
val axisSplitApks = (project.findProperty("axis.splitApks") as? String) == "true"
val buildNumber = (project.findProperty("axis.buildNumber") as? String)?.toIntOrNull() ?: 1

val keystoreFile = System.getenv("AXIS_KEYSTORE_FILE")
val keystorePassword = System.getenv("AXIS_KEYSTORE_PASSWORD") ?: "axis-translate-release"
val keystoreAlias = System.getenv("AXIS_KEY_ALIAS") ?: "axis-translate"
val keystoreAliasPassword = System.getenv("AXIS_KEY_PASSWORD") ?: keystorePassword

android {
    namespace = "com.axis.translate"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.axis.translate"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en")

        ndk {
            // AGP forbids ndk.abiFilters and splits.abi being set at the same
            // time: when split APKs are enabled the splits block owns the ABI
            // list; otherwise abiFilters controls the native build scope
            // (e.g. -Paxis.abis=x86_64 for emulator e2e runs).
            if (!axisSplitApks) {
                abiFilters.addAll(axisAbis)
            }
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DAXIS_LLAMA_TAG=v0.4.1"
                )
                if ((project.findProperty("axis.ccache") as? String) == "true") {
                    arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
                    )
                }
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        create("release") {
            val resolvedStore = keystoreFile?.let { file(it) }
                ?: rootProject.file("keystore/axis-translate-release.jks")
            storeFile = resolvedStore
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            keyPassword = keystoreAliasPassword
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = axisSplitApks
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
        disable += listOf(
            "UnusedMaterial3ScaffoldPaddingParameter",
            "GradleDependency",
            "AndroidGradlePluginVersion"
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                test.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.material)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.mlkit.text.recognition.devanagari)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// Deterministic build timestamp for reproducible release archives
val buildTimestamp: String by lazy {
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

tasks.register("printBuildInfo") {
    doLast {
        logger.lifecycle("axis-build: versionCode=$buildNumber abis=$axisAbis splitApks=$axisSplitApks timestamp=$buildTimestamp")
    }
}
