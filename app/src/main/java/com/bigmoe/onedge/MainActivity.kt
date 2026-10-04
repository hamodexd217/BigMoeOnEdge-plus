package com.bigmoe.onedge

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as OnEdgeApplication).container
        val viewModelFactory = AppViewModelFactory(appContainer)

        setContent {
            var showRoomUpgradeWarning by remember { mutableStateOf(appContainer.consumeRoomUpgradeNotice()) }
            val settings by appContainer.settingsDataStore.settingsFlow
                .collectAsState(initial = com.bigmoe.onedge.data.local.datastore.EngineSettings())

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
                    fileViewModel = fileViewModel
                )

                if (showRoomUpgradeWarning) {
                    AlertDialog(
                        onDismissRequest = { showRoomUpgradeWarning = false },
                        title = { Text("Chat history reset on database upgrade") },
                        text = { Text("This development build upgrades Room database v1 to v2 with a destructive migration. Old chats are cleared; model files and settings are not affected.") },
                        confirmButton = {
                            TextButton(onClick = { showRoomUpgradeWarning = false }) { Text("Understood") }
                        }
                    )
                }
            }
        }
    }
}
