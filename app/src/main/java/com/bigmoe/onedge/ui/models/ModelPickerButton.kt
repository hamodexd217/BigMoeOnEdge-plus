package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.ModelManager

/**
 * The chip button in the chat's top bar. Tapping it opens a small list of every registered model: pick one to load
 * it (the usual confirmation with the memory advice and projector choice follows), or turn the loaded one off.
 * "Manage models" still opens the full Models page.
 */
@Composable
fun ModelPickerButton(
    viewModel: ModelViewModel,
    isGenerating: Boolean,
    onManageModels: () -> Unit
) {
    val ui by viewModel.uiState.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    var open by remember { mutableStateOf(false) }

    val models = ui.models.filterNot { ModelManager.isProjectorName(it.name) }
    val loadedPath = (engine as? EngineState.Ready)?.info?.path
    val loading = engine is EngineState.Loading

    Box {
        IconButton(onClick = { viewModel.refresh(); open = true }) {
            Icon(imageVector = Icons.Default.Memory, contentDescription = "Models")
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Models", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (loading) {
                    Text("Loading a model\u2026", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (isGenerating) {
                    Text("Stop the reply to unload the model.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (models.isEmpty()) {
                Text(
                    "No models yet. Add one from the Models page.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            models.forEach { model ->
                val isLoaded = model.path == loadedPath
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                ModelManager.formatBytes(model.sizeBytes) + if (isLoaded) " \u2022 Loaded" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        Text(
                            if (isLoaded) "Unload" else "Load",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    enabled = !loading && !(isLoaded && isGenerating),
                    onClick = {
                        open = false
                        if (isLoaded) viewModel.unload() else viewModel.requestLoad(model.path)
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage models\u2026") },
                onClick = { open = false; onManageModels() }
            )
        }
    }
    // Same confirmation step as on the Models page (memory advice, projector choice).
    ui.pendingLoad?.let { pending -> PendingLoadDialog(viewModel, pending) }
}
