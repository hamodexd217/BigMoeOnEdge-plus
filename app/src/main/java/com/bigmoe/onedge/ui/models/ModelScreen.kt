package com.bigmoe.onedge.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.data.local.datastore.loadConfigDiffers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    viewModel: ModelViewModel,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.uiState.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(context.contentResolver, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("MODELS", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                }
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusCard(engine, settings, onUnload = viewModel::unload, onReload = viewModel::reloadCurrent) }

            item { SupportedModelsCard() }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add a model / mmproj", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pick a .gguf file: a model, or an mmproj file (the vision projector that lets a vision model " +
                                "see pictures and video). It is copied into the app's private storage (${ui.modelsDir}) " +
                                "because the engine needs a real file path. You need as much free storage as the " +
                                "file size.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Advanced: you can also `adb push model.gguf` (or the mmproj file) into that folder and press refresh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        val progress = ui.importProgress
                        if (progress != null) {
                            if (progress.totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { (progress.copiedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${ModelManager.formatBytes(progress.copiedBytes)} / ${ModelManager.formatBytes(progress.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(ModelManager.formatBytes(progress.copiedBytes), style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = viewModel::cancelImport) { Text("Cancel import") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose .gguf (model or mmproj)") }
                                OutlinedButton(onClick = viewModel::refresh) { Text("Refresh") }
                            }
                        }
                    }
                }
            }

            ui.message?.let { msg ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Load the last model on app start", modifier = Modifier.weight(1f))
                    Switch(checked = settings.autoLoadLastModel, onCheckedChange = viewModel::setAutoLoad)
                }
            }

            if (ui.models.isEmpty()) {
                item { Text("No models yet.", color = MaterialTheme.colorScheme.secondary) }
            }
            items(ui.models.filterNot { ModelManager.isProjectorName(it.name) }, key = { it.path }) { model ->
                ModelRow(
                    model = model,
                    isLoaded = (engine as? EngineState.Ready)?.info?.path == model.path,
                    busy = engine is EngineState.Loading,
                    onLoad = { viewModel.requestLoad(model.path) },
                    onDelete = { viewModel.delete(model.path) }
                )
            }
            // keeps the last row clear of the floating navigation bar
            item(key = "bottom_spacer") { androidx.compose.foundation.layout.Spacer(Modifier.height(112.dp)) }
        }
    }

    ui.pendingLoad?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingLoad,
            title = { Text(if (pending.advice.canLoad) "Load model?" else "Cannot load") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pending.advice.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                    pending.advice.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (pending.advice.canLoad) {
                        ProjectorPicker(
                            projectors = pending.projectors,
                            selected = pending.selectedProjector,
                            onSelect = viewModel::selectProjector
                        )
                        Text(
                            "Loading can take a minute or more and uses a lot of memory. Keep the app open.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                if (pending.advice.canLoad) TextButton(onClick = viewModel::confirmLoad) { Text("Load") }
                else TextButton(onClick = viewModel::dismissPendingLoad) { Text("OK") }
            },
            dismissButton = {
                if (pending.advice.canLoad) TextButton(onClick = viewModel::dismissPendingLoad) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SupportedModelsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Any MoE model will work.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Even regular (dense) models can work: they are detected automatically and loaded the ordinary way.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ProjectorPicker(
    projectors: List<ModelFile>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = when {
        selected == null -> "None"
        else -> projectors.firstOrNull { it.path == selected }?.name ?: "None"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Vision projector (mmproj)", style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedName, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = { expanded = false; onSelect(null) })
                projectors.forEach { projector ->
                    DropdownMenuItem(
                        text = { Text(projector.name) },
                        onClick = { expanded = false; onSelect(projector.path) }
                    )
                }
            }
        }
        if (projectors.isEmpty()) {
            Text("Put an mmproj .gguf beside the model to enable images and video.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatusCard(
    engine: EngineState,
    settings: com.bigmoe.onedge.data.local.datastore.EngineSettings,
    onUnload: () -> Unit,
    onReload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (engine) {
                EngineState.Unloaded -> Text("No model loaded")
                is EngineState.Loading -> {
                    Text("Loading ${engine.path.substringAfterLast('/')}…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "The engine does not report progress; large models can take a few minutes.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                is EngineState.Failed -> {
                    Text("Load failed", color = MaterialTheme.colorScheme.error)
                    Text(engine.message, style = MaterialTheme.typography.bodySmall)
                }
                is EngineState.Ready -> {
                    val info = engine.info
                    Text("Loaded: ${info.name}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (info.mmprojPath.isNullOrBlank()) "Vision: inactive (no mmproj)"
                        else "Vision: active • ${info.mmprojPath.substringAfterLast('/')}"
                    )
                    Text(
                        "arch ${info.arch} • context ${info.nCtx} • ${info.nExpertUsed} experts/token • " +
                            "loaded in ${"%.1f".format(java.util.Locale.US, info.loadSeconds)} s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (info.config.mmapBaseline) "Regular (non-MoE) model: ordinary load, no expert streaming"
                        else "Expert streaming ON • I/O–compute overlap: " + if (info.overlapActive) "active" else "not active",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (info.thinkControl == "none") {
                        Text(
                            "This model has no switch to turn thinking off.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (loadConfigDiffers(info.config, settings)) {
                        Text(
                            "Some settings changed since this model was loaded (context, sampling, cache, threads). " +
                                "They apply after a reload.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loadConfigDiffers(info.config, settings)) Button(onClick = onReload) { Text("Reload") }
                        OutlinedButton(onClick = onUnload) { Text("Unload") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelFile,
    isLoaded: Boolean,
    busy: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleSmall)
                Text(ModelManager.formatBytes(model.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
            if (isLoaded) {
                Text("Loaded", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            } else {
                TextButton(onClick = onLoad, enabled = !busy) { Text("Load") }
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.Delete, contentDescription = "Delete model")
            }
        }
    }
}
