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

// Optional CPU target for the native engine, e.g. -PbmoeArmArch=armv8.2-a+dotprod+fp16+i8mm. Newer cores (Cortex-X3/X4,
// A715/A720 and later) run the int8 matrix kernels much faster with i8mm, which mostly speeds up prompt processing.
// A build made with a feature the phone lacks crashes on load (SIGILL), so the default stays the safe baseline.
val bmoeArmArch: String? = providers.gradleProperty("bmoeArmArch").orNull?.trim()?.takeIf { it.isNotEmpty() }

// android:appCategory written into the manifest (see AndroidManifest.xml). "game" matches the reference app;
// pass -PbmoeAppCategory=undefined to build without the hint (the behaviour before this property existed).
val bmoeAppCategory: String = (providers.gradleProperty("bmoeAppCategory").orNull ?: "game").trim().lowercase().also {
    val allowed = setOf("accessibility", "audio", "game", "image", "maps", "news", "productivity", "social", "video", "undefined")
    require(it in allowed) { "bmoeAppCategory must be one of $allowed, got '$it'" }
}

// Keep the engine's compute threads on the fast CPU cores with a small priority boost (see cpp/perf_tuning.h).
// Off by default: pinning the JNI caller is not guaranteed to pin an already-created ggml worker pool,
 // and can contend with the worker pool on heterogeneous Android CPUs. Opt in for device-specific A/B tests.
val bmoePinFastCores: Boolean = providers.gradleProperty("bmoePinFastCores").orNull?.trim()?.lowercase() == "true"

// ---- Release signing -------------------------------------------------------------------------------------
// Android only installs an update over an existing app when both APKs are signed with the SAME key. Without a
// signingConfig, AGP signs with ~/.android/debug.keystore, which a fresh GitHub Actions runner generates anew on
// every run: each CI APK then has a different certificate and cannot update the previous one (INSTALL_FAILED_
// UPDATE_INCOMPATIBLE / "signatures do not match"). The key is supplied via environment variables or -P properties
// (never committed): BMOE_KEYSTORE_FILE, BMOE_KEYSTORE_PASSWORD, BMOE_KEY_ALIAS, BMOE_KEY_PASSWORD.
fun bmoeSigningValue(name: String): String? =
    (System.getenv(name) ?: providers.gradleProperty(name).orNull)?.trim()?.takeIf { it.isNotEmpty() }

val bmoeKeystoreFile: String? = bmoeSigningValue("BMOE_KEYSTORE_FILE")
val bmoeKeystorePassword: String? = bmoeSigningValue("BMOE_KEYSTORE_PASSWORD")
val bmoeKeyAlias: String? = bmoeSigningValue("BMOE_KEY_ALIAS")
val bmoeKeyPassword: String? = bmoeSigningValue("BMOE_KEY_PASSWORD") ?: bmoeKeystorePassword
val bmoeHasStableKey: Boolean =
    bmoeKeystoreFile != null && bmoeKeystorePassword != null && bmoeKeyAlias != null
// CI sets -PbmoeRequireStableSigning=true so a missing secret fails the build instead of silently producing an
// APK signed with a throwaway debug key. Local Android Studio builds keep the default debug key.
val bmoeRequireStableSigning: Boolean =
    providers.gradleProperty("bmoeRequireStableSigning").orNull?.trim()?.lowercase() == "true"
if (bmoeRequireStableSigning) {
    check(bmoeHasStableKey) {
        "Stable signing is required (-PbmoeRequireStableSigning=true) but BMOE_KEYSTORE_FILE, " +
            "BMOE_KEYSTORE_PASSWORD and BMOE_KEY_ALIAS are not all set. See docs/SIGNING.md."
    }
    check(File(bmoeKeystoreFile!!).isFile) { "BMOE_KEYSTORE_FILE does not exist: $bmoeKeystoreFile" }
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
        versionCode = 11
        versionName = "0.6.0"
        manifestPlaceholders["bmoeAppCategory"] = bmoeAppCategory

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
                        "-DBMOE_ENABLE_MULTIMODAL=" + (if (bmoeMultimodal) "ON" else "OFF"),
                        "-DBMOE_PIN_FAST_CORES=" + (if (bmoePinFastCores) "ON" else "OFF")
                    ).plus(bmoeCiCmakeArguments)
                        .plus(if (bmoeArmArch != null) listOf("-DBMOE_ARM_ARCH=$bmoeArmArch") else emptyList())
                        .toTypedArray()
                )
            }
        }

        ndk {
            abiFilters.addAll(bmoeAbis)
        }
    }

    signingConfigs {
        if (bmoeHasStableKey) {
            create("bmoeStable") {
                storeFile = file(bmoeKeystoreFile!!)
                storePassword = bmoeKeystorePassword
                keyAlias = bmoeKeyAlias
                keyPassword = bmoeKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Same key as release so debug-type CI APKs (what the workflows ship) are update-compatible.
            if (bmoeHasStableKey) signingConfig = signingConfigs.getByName("bmoeStable")
            // A debuggable app runs with ART's debug compilation, CheckJNI (every JNI call is validated, including
            // the per-token callback and the image/array copies) and the unoptimised Compose paths: the UI and the
            // JNI traffic are measurably slower. The CI ships the debug build type, so it is NOT debuggable by
            // default; pass -PbmoeDebuggable=true when attaching a debugger.
            isDebuggable = providers.gradleProperty("bmoeDebuggable").orNull == "true"
            // The engine is compute bound; an unoptimised native build is unusably slow, so
            // build CMake in Release mode even for the debug APK.
            externalNativeBuild {
                cmake {
                    arguments("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
        release {
            if (bmoeHasStableKey) signingConfig = signingConfigs.getByName("bmoeStable")
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
        val dspAssets = file("src/main/assets/hexagon")
        val requiredJni = listOf("libonedge-engine.so", "libc++_shared.so")
        val requiredDsp = listOf(
            "libggml-htp-v73.so", "libggml-htp-v75.so", "libggml-htp-v79.so", "libggml-htp-v81.so"
        )
        val missingJni = requiredJni.filterNot { File(jni, it).isFile && File(jni, it).length() > 0L }
        val missingDsp = requiredDsp.filterNot { File(dspAssets, it).isFile && File(dspAssets, it).length() > 0L }
        check(missingJni.isEmpty() && missingDsp.isEmpty()) {
            "Hexagon/NPU prebuilt build requested but payload is incomplete. Missing ARM64 JNI: " +
                "${missingJni.joinToString().ifEmpty { "none" }}; missing Hexagon DSP assets: " +
                "${missingDsp.joinToString().ifEmpty { "none" }}. Run scripts/build-hexagon-onedge-engine.sh " +
                "in the Snapdragon toolchain first. DSP skeletons must be staged under src/main/assets/hexagon, " +
                "not jniLibs/arm64-v8a."
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
    implementation("androidx.compose.animation:animation")
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
