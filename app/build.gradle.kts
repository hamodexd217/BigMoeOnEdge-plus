plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    // Kotlin 2.0+ ships the Compose compiler as its own Gradle plugin.
    alias(libs.plugins.kotlin.compose)
}

val bmoeMultimodal: Boolean = (providers.gradleProperty("bmoeMultimodal").orNull ?: "true").toBoolean()
val bmoePrebuiltNative: Boolean = (providers.gradleProperty("bmoePrebuiltNative").orNull ?: "false").toBoolean()

// GitHub Actions runners can repeatedly re-run CMake's self-regeneration step while the
// repository contains the large vendored llama.cpp tree. Keep local Android Studio builds
// unchanged, but suppress the Ninja/CMake regeneration check in CI where the checkout is
// immutable for the duration of a build.
val bmoeCiCmakeArguments: List<String> = if (
    System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
) {
    listOf("-DCMAKE_SUPPRESS_REGENERATION=ON")
} else {
    emptyList()
}

val bmoeAbis: List<String> = (providers.gradleProperty("bmoeAbis").orNull ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.bigmoe.onedge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bigmoe.onedge"
        // 28: the native engine uses O_DIRECT, AHardwareBuffer and other APIs that are only
        // dependable from Android 10 (upstream uses 29); the NDK toolchain also links against this platform level.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments(
                    *listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DBMOE_ENABLE_MULTIMODAL=" + (if (bmoeMultimodal) "ON" else "OFF")
                    ).plus(bmoeCiCmakeArguments).toTypedArray()
                )
            }
        }

        ndk {
            abiFilters.addAll(bmoeAbis)
        }
    }

    buildTypes {
        debug {
            // The engine is compute bound; an unoptimised native build is unusably slow, so
            // build CMake in Release mode even for the debug APK (Kotlin stays debuggable).
            externalNativeBuild {
                cmake {
                    arguments("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
        )
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file(if (bmoePrebuiltNative) "src/main/cpp/prebuilt/CMakeLists.txt" else "src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Keep libonedge-engine.so / libc++_shared.so uncompressed & page aligned.
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

val verifyPrebuiltNpuLibs = tasks.register("verifyPrebuiltNpuLibs") {
    onlyIf { bmoePrebuiltNative }
    doLast {
        val jni = file("src/main/jniLibs/arm64-v8a")
        val required = listOf(
            "libonedge-engine.so",
            "libc++_shared.so",
            "libggml-htp-v73.so",
            "libggml-htp-v75.so",
            "libggml-htp-v79.so",
            "libggml-htp-v81.so"
        )
        val missing = required.filterNot { File(jni, it).isFile }
        check(missing.isEmpty()) {
            "Hexagon/NPU prebuilt build requested but required JNI libraries are missing: ${missing.joinToString()}. " +
                "Run scripts/build-hexagon-onedge-engine.sh in the Snapdragon toolchain first."
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyPrebuiltNpuLibs)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // JVM unit tests: android.jar's org.json is stubbed, so bring a real implementation.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
