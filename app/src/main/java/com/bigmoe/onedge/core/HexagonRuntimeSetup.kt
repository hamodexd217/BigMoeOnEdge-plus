package com.bigmoe.onedge.core

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Installs the DSP-side HTP skeletons into a real filesystem directory and configures the FastRPC
 * loader before libonedge-engine is loaded. DSP skeleton ELFs are NOT Android arm64 JNI libraries:
 * they belong in assets and must be discoverable through ADSP_LIBRARY_PATH.
 *
 * The CPU APK intentionally has no such assets; in that case this object only logs and returns.
 */
object HexagonRuntimeSetup {
    private const val TAG = "OnEdge-HTP"
    private const val ASSET_DIR = "hexagon"
    private const val TARGET_DIR = "hexagon-dsp"
    private const val STAMP_FILE = ".payload-stamp"
    private val skeletons = listOf(
        "libggml-htp-v73.so",
        "libggml-htp-v75.so",
        "libggml-htp-v79.so",
        "libggml-htp-v81.so"
    )

    @JvmStatic
    fun prepare(context: Context) {
        val app = context.applicationContext
        try {
            val names = app.assets.list(ASSET_DIR)?.toSet().orEmpty()
            if (!skeletons.all(names::contains)) {
                Log.i(TAG, "No complete HTP DSP payload in APK assets; leaving normal CPU startup unchanged")
                return
            }

            val target = File(app.filesDir, TARGET_DIR)
            if (!target.isDirectory && !target.mkdirs()) {
                Log.e(TAG, "Cannot create private DSP directory: ${target.absolutePath}")
                return
            }
            val stamp = runCatching {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
            }.getOrDefault("unknown")
            val stampFile = File(target, STAMP_FILE)
            val complete = skeletons.all { File(target, it).isFile && File(target, it).length() > 0L }
            if (!complete || runCatching { stampFile.readText() }.getOrNull() != stamp) {
                for (name in skeletons) {
                    val destination = File(target, name)
                    val temporary = File(target, "$name.tmp")
                    app.assets.open("$ASSET_DIR/$name").use { input ->
                        FileOutputStream(temporary).use { output -> input.copyTo(output) }
                    }
                    if (temporary.length() <= 0L) {
                        temporary.delete()
                        error("Empty bundled DSP skeleton: $name")
                    }
                    if (destination.exists() && !destination.delete()) error("Cannot replace DSP skeleton: $name")
                    if (!temporary.renameTo(destination)) error("Cannot install DSP skeleton: $name")
                }
                if (!skeletons.all { File(target, it).isFile && File(target, it).length() > 0L }) {
                    error("One or more HTP skeletons failed extraction")
                }
                stampFile.writeText(stamp)
            }

            // FastRPC uses a semicolon-separated search list on Android. Keep any vendor-provided
            // paths, but place this app's matching skeleton directory first for the ggml HTP URI.
            val existing = runCatching { Os.getenv("ADSP_LIBRARY_PATH") }.getOrNull().orEmpty()
            val paths = (listOf(target.absolutePath) + existing.split(';').filter(String::isNotBlank)).distinct()
            Os.setenv("ADSP_LIBRARY_PATH", paths.joinToString(";"), true)
            Log.i(TAG, "Configured ADSP_LIBRARY_PATH with ${skeletons.size} extracted DSP skeletons")
        } catch (t: Throwable) {
            // Don't break non-NPU functionality due to an optional accelerator setup failure.
            Log.e(TAG, "HTP DSP runtime setup failed; native HTP availability probe should report unavailable", t)
        }
    }
}
