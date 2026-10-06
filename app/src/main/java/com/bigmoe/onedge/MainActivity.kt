package com.bigmoe.onedge

import android.os.Bundle
import android.os.Build
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.bigmoe.onedge.di.AppViewModelFactory
import com.bigmoe.onedge.ui.chat.ChatViewModel
import com.bigmoe.onedge.ui.history.HistoryViewModel
import com.bigmoe.onedge.ui.models.ModelViewModel
import com.bigmoe.onedge.ui.navigation.AppNavigation
import com.bigmoe.onedge.ui.settings.SettingsViewModel
import com.bigmoe.onedge.ui.theme.BigMoeTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_OPEN_SESSION_ID)) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as OnEdgeApplication).container
        val viewModelFactory = AppViewModelFactory(appContainer)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Android 13+: ask only when the user has explicitly opened the app. The foreground
            // service itself is started only for an actual generation.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            var showRoomUpgradeWarning by remember { mutableStateOf(appContainer.consumeRoomUpgradeNotice()) }
            val settings by appContainer.settingsDataStore.settingsFlow
                .collectAsState(initial = com.bigmoe.onedge.data.local.datastore.EngineSettings())
            val generation by appContainer.generationManager.state.collectAsState()

            androidx.compose.runtime.LaunchedEffect(generation.active, generation.sessionId) {
                val target = intent.getStringExtra(EXTRA_OPEN_SESSION_ID)
                if (target != null && target == generation.sessionId && generation.active) {
                    // The notification already guarantees the process is foreground; navigation is
                    // handled by the chat route below using the same session id.
                }
            }

            BigMoeTheme(themeId = settings.themeId, customPalettesJson = settings.customPalettesJson) {
                val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
                val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
                val modelViewModel: ModelViewModel = viewModel(factory = viewModelFactory)
                val fileViewModel: com.bigmoe.onedge.ui.files.FileViewModel = viewModel(factory = viewModelFactory)

                AppNavigation(
                    chatViewModel = chatViewModel,
                    historyViewModel = historyViewModel,
                    settingsViewModel = settingsViewModel,
                    modelViewModel = modelViewModel,
                    fileViewModel = fileViewModel,
                    initialSessionId = intent.getStringExtra(EXTRA_OPEN_SESSION_ID)
                )

                if (showRoomUpgradeWarning) {
                    AlertDialog(
                        onDismissRequest = { showRoomUpgradeWarning = false },
                        title = { Text("Database upgraded") },
                        text = { Text("The app database has been upgraded. Existing chats are kept when the installed database supports the migration.") },
                        confirmButton = {
                            TextButton(onClick = { showRoomUpgradeWarning = false }) { Text("Understood") }
                        }
                    )
                }
            }
        }
    }
    companion object {
        const val EXTRA_OPEN_SESSION_ID = "open_session_id"
    }
}
