package com.bigmoe.onedge.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/** Reads RAM, CPU layout and a short memory-bandwidth test. Takes about a second; safe to call from the UI. */
class DeviceScanner(private val context: Context) {

    suspend fun scan(npuRuntime: Boolean): DeviceProfile = withContext(Dispatchers.Default) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val fast = fastCoreCount(cores)
        DeviceProfile(
            totalRamMb = mem.totalMem / (1024L * 1024L),
            availRamMb = mem.availMem / (1024L * 1024L),
            cores = cores,
            fastCores = fast,
            soc = socName(),
            memBandwidthGBs = measureBandwidthGBs(threads = fast.coerceIn(1, 4)),
            npuRuntime = npuRuntime
        )
    }

    private fun socName(): String {
        val parts = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            val maker = Build.SOC_MANUFACTURER
            val model = Build.SOC_MODEL
            if (!maker.isNullOrBlank() && maker != Build.UNKNOWN) parts += maker
            if (!model.isNullOrBlank() && model != Build.UNKNOWN) parts += model
        }
        if (parts.isEmpty()) parts += Build.HARDWARE
        return parts.joinToString(" ")
    }

    /** Cores within ~75% of the fastest core's max frequency. Falls back to half the cores if sysfs is unreadable. */
    private fun fastCoreCount(cores: Int): Int {
        val freqs = (0 until cores).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.size < cores / 2 || freqs.isEmpty()) return max(1, cores / 2)
        val top = freqs.max()
        return freqs.count { it >= top * 0.75 }.coerceIn(1, cores)
    }

    /** Multi-thread memcpy over off-heap buffers; returns GB/s moved, or a conservative guess if memory is too tight. */
    private fun measureBandwidthGBs(threads: Int): Double {
        val chunk = 16 * 1024 * 1024
        val passes = 6
        return try {
            val src = List(threads) { ByteBuffer.allocateDirect(chunk) }
            val dst = List(threads) { ByteBuffer.allocateDirect(chunk) }
            // Warm up page tables once.
            src.indices.forEach { i -> src[i].clear(); dst[i].clear(); dst[i].put(src[i]) }
            val start = System.nanoTime()
            val workers = src.indices.map { i ->
                Thread {
                    repeat(passes) { src[i].clear(); dst[i].clear(); dst[i].put(src[i]) }
                }.also { it.start() }
            }
            workers.forEach { it.join() }
            val seconds = (System.nanoTime() - start) / 1e9
            val gb = threads.toDouble() * passes * chunk / (1024.0 * 1024.0 * 1024.0)
            (gb / max(seconds, 1e-3)).coerceIn(2.0, 120.0)
        } catch (_: OutOfMemoryError) {
            FALLBACK_GBS
        } catch (_: Throwable) {
            FALLBACK_GBS
        }
    }

    private companion object {
        const val FALLBACK_GBS = 12.0
    }
}
