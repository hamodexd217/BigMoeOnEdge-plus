package com.bigmoe.onedge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bigmoe.onedge.MainActivity
import com.bigmoe.onedge.OnEdgeApplication
import com.bigmoe.onedge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the local API server, so Android does not kill the process while other apps use
 * the model. It follows the settings: while "server enabled" is on it runs, and a changed port / bind address
 * restarts it. A partial wake lock is held only while a request is being served.
 */
class ApiServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ApiServer? = null
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val busy = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as OnEdgeApplication).container.disableApiServer()
            shutdown()
            return START_NOT_STICKY
        }
        // Must happen within seconds of startForegroundService(), before anything slow.
        startForeground(NOTIFICATION_ID, notification("Starting…"))
        if (watchJob?.isActive != true) {
            val container = (application as OnEdgeApplication).container
            watchJob = scope.launch {
                var lastKey = ""
                container.settingsDataStore.settingsFlow.collect { s ->
                    if (!s.serverEnabled) {
                        shutdown()
                        return@collect
                    }
                    val key = "${s.serverBindAll}:${s.serverPort}"
                    if (key != lastKey || server?.isRunning != true) {
                        lastKey = key
                        startServer(container, s.serverBindAll, s.serverPort)
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startServer(container: com.bigmoe.onedge.di.AppContainer, bindAll: Boolean, port: Int) {
        server?.stop()
        // First start: generate the key so authentication is on from the beginning.
        val s0 = container.settingsDataStore.settingsFlow.first()
        if (s0.serverApiKey.isBlank()) container.settingsDataStore.updateServerApiKey(ApiKey.generate())

        val api = ApiServer(
            engine = container.engineController,
            settings = { container.settingsDataStore.settingsFlow.first() },
            log = { Log.i(TAG, it) },
            onBusy = { b -> onBusy(b) }
        )
        try {
            api.start(bindAll, port)
            server = api
            val url = "http://${if (bindAll) "<device-ip>" else "127.0.0.1"}:${api.boundPort}"
            updateNotification("Listening on $url")
            Log.i(TAG, "server up: $url")
        } catch (e: Exception) {
            server = null
            Log.e(TAG, "cannot start server on port $port: ${e.message}")
            updateNotification("Cannot listen on port $port: ${e.message}")
        }
    }

    private fun onBusy(b: Boolean) {
        if (b) {
            if (busy.incrementAndGet() == 1) acquireWakeLock()
        } else if (busy.decrementAndGet() <= 0) {
            busy.set(0)
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BigMoeOnEdge:apiServer").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            if (!wl.isHeld) wl.acquire(WAKE_LOCK_MAX_MS) // safety net only; released when the request ends
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun shutdown() {
        server?.stop()
        server = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ApiServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_generation)
            .setContentTitle("BigMoeOnEdge+ • API server")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        watchJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BmoeApi"
        const val ACTION_STOP = "com.bigmoe.onedge.action.STOP_API_SERVER"
        const val CHANNEL_ID = "api_server"
        const val NOTIFICATION_ID = 4108
        private const val WAKE_LOCK_MAX_MS = 6L * 60 * 60 * 1000

        /** Starts the service (it then runs while the "server enabled" setting is on). Never throws. */
        fun start(context: Context): Boolean = try {
            val i = Intent(context, ApiServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            true
        } catch (e: Exception) {
            // e.g. Android 12+ refuses a foreground-service start while the app is in the background.
            Log.w(TAG, "cannot start the API server service: ${e.message}")
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ApiServerService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Local API server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the on-device API server is running"
                }
            )
        }
    }
}
