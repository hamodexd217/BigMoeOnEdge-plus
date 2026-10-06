package com.bigmoe.onedge.core

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.bigmoe.onedge.MainActivity
import com.bigmoe.onedge.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Foreground-service shell required while a native generation continues off-screen. */
class GenerationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var lastNotificationAt = 0L
    private var lastNotifiedTokens = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        startForeground(NOTIFICATION_ID, notification("Generating…", id))
        if (observeJob?.isActive != true) {
            val manager = (application as com.bigmoe.onedge.OnEdgeApplication).container.generationManager
            observeJob = scope.launch {
                manager.state.collectLatest { state ->
                    if (!state.active) {
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    } else updateNotification(state)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(state: GenerationManager.State) {
        val now = System.currentTimeMillis()
        val tokenChangedEnough = state.generatedTokens >= 0 && (lastNotifiedTokens < 0 || state.generatedTokens - lastNotifiedTokens >= 16)
        if (now - lastNotificationAt < 1000L && !tokenChangedEnough) return
        lastNotificationAt = now
        lastNotifiedTokens = state.generatedTokens

        val total = state.elapsedMs / 1000
        val elapsed = when {
            total >= 3600 -> "${total / 3600}h %02dm %02ds".format((total / 60) % 60, total % 60)
            total >= 60 -> "${total / 60}m %02ds".format(total % 60)
            else -> "%.1f s".format(state.elapsedMs / 1000.0)
        }
        val progress = if (state.generatedTokens > 0) "${state.generatedTokens} tokens • " else ""
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification("$progress$elapsed", state.sessionId.orEmpty(), state.chatTitle))
    }

    private fun notification(text: String, sessionId: String, title: String = "") = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_generation)
        .setContentTitle("BigMoeOnEdge+ • Generating")
        .setContentText(text)
        .setSubText(if (title.isBlank()) sessionId else "Chat: $title")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(PendingIntent.getActivity(this, sessionId.hashCode(), Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() { observeJob?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val CHANNEL_ID = "generation"
        const val NOTIFICATION_ID = 4107
        fun start(context: Context, sessionId: String) {
            ensureChannel(context)
            val i = Intent(context, GenerationForegroundService::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) { context.stopService(Intent(context, GenerationForegroundService::class.java)) }
        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AI generation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows active BigMoeOnEdge+ generations"
            })
        }
    }
}
