package com.bigmoe.onedge.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/** Battery, storage, memory and Android version. Read-only; needs no permission. */
class AndroidDeviceInfo(private val context: Context) : DeviceInfoProvider {

    override fun summary(): String {
        val lines = ArrayList<String>()
        lines.add("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        battery()?.let { lines.add(it) }
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            lines.add("Storage: ${gb(stat.availableBytes)} free of ${gb(stat.totalBytes)}")
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            lines.add("Memory: ${gb(info.availMem)} available of ${gb(info.totalMem)}" + if (info.lowMemory) " (the system is low on memory)" else "")
        }
        lines.add("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
        return lines.joinToString("\n")
    }

    private fun battery(): String? {
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "Battery: ${level * 100 / scale}%" + if (charging) " (charging)" else " (not charging)"
    }

    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
