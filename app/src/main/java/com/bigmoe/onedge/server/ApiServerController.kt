package com.bigmoe.onedge.server

import android.content.Context
import android.content.Intent
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import kotlinx.coroutines.flow.first

/** The app-side switch for the API server: persists the setting and starts/stops the service. */
class ApiServerController(
    private val context: Context,
    private val settings: EngineSettingsDataStore
) {
    /** Turns the server on or off. The key is generated the first time it is turned on. */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) ensureKey()
        settings.updateServerEnabled(enabled)
        if (enabled) ApiServerService.start(context.applicationContext) else ApiServerService.stop(context.applicationContext)
    }

    /** Called at app start: brings the service back up if it was left enabled. */
    suspend fun startIfEnabled() {
        if (settings.settingsFlow.first().serverEnabled) ApiServerService.start(context.applicationContext)
    }

    suspend fun ensureKey(): String {
        val s = settings.settingsFlow.first()
        if (s.serverApiKey.isNotBlank()) return s.serverApiKey
        val k = ApiKey.generate()
        settings.updateServerApiKey(k)
        return k
    }

    suspend fun regenerateKey(): String {
        val k = ApiKey.generate()
        settings.updateServerApiKey(k)
        return k
    }

    companion object {
        /** The URL other apps use. Loopback unless the server is bound to all interfaces. */
        fun endpointUrl(s: EngineSettings): String =
            "http://${if (s.serverBindAll) "<device-ip>" else "127.0.0.1"}:${s.serverPort}"
    }
}

/**
 * adb hook so the server can be tested before (or without) the settings screen:
 *   adb shell am start -n <package>/.MainActivity --es api_server start --ei api_port 8080 --es api_key test-key
 *   adb shell am start -n <package>/.MainActivity --es api_server stop
 * Debuggable builds only (see AppContainer.handleApiServerIntent): MainActivity is exported, so in a release build any
 * app could use these extras to set the key or switch authentication off. Release builds use the settings screen.
 */
object ApiServerIntentHook {
    const val EXTRA_ACTION = "api_server"
    const val EXTRA_PORT = "api_port"
    const val EXTRA_KEY = "api_key"

    suspend fun handle(intent: Intent?, controller: ApiServerController, settings: EngineSettingsDataStore) {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return
        if (intent.hasExtra(EXTRA_PORT)) settings.updateServerPort(intent.getIntExtra(EXTRA_PORT, 8080))
        val key = intent.getStringExtra(EXTRA_KEY)
        if (!key.isNullOrBlank()) settings.updateServerApiKey(key)
        when (action) {
            "start" -> controller.setEnabled(true)
            "stop" -> controller.setEnabled(false)
        }
    }
}
