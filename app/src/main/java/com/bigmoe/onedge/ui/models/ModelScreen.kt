package com.bigmoe.onedge.ui.models

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.OutlinedTextField
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
import java.io.File
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings
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
    var sharedPathAccessGranted by remember { mutableStateOf(hasSharedPathAccess(context)) }
    var showAdvancedPath by remember { mutableStateOf(false) }
    var targetIncompleteImportId by remember { mutableStateOf<String?>(null) }
    var targetMtpModelPath by remember { mutableStateOf<String?>(null) }

    // MANAGE_EXTERNAL_STORAGE is a special Settings grant, not a normal runtime prompt.
    // When the user returns, retry registration automatically so the path is not copied.
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        sharedPathAccessGranted = hasSharedPathAccess(context)
        if (sharedPathAccessGranted) {
            viewModel.registerExistingPath()
        } else {
            viewModel.showMessage("Storage access was not enabled. Try Add GGUF from storage again after granting access, or use Import GGUF file(s) (copy).")
        }
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        sharedPathAccessGranted = granted
        if (granted) viewModel.registerExistingPath()
        else viewModel.showMessage("Storage permission was not granted. Use Import GGUF file(s) (copy), or grant access and try Add GGUF from storage again.")
    }

    // Default path: select a GGUF in Android's picker and register its real shared-storage path.
    // This avoids making a second multi-GB copy. The native engine needs a filesystem path, not content://.
    val noCopyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = resolveSharedStorageDocumentPath(context, uri)
            if (path == null) {
                viewModel.showMessage(
                    "This picker location does not expose a direct filesystem path. Browse to Internal storage in the picker, " +
                        "select the .gguf there, or use Import & copy for cloud/provider files."
                )
            } else if (!path.endsWith(".gguf", ignoreCase = true)) {
                viewModel.showMessage("Choose a .gguf file (model, MTP draft, or mmproj). No file was copied.")
            } else {
                viewModel.setExistingPathInput(path)
                if (!isSharedAbsolutePath(path) || sharedPathAccessGranted) {
                    viewModel.registerExistingPath()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val appSettings = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    try {
                        allFilesAccessLauncher.launch(appSettings)
                    } catch (_: Exception) {
                        allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    viewModel.registerExistingPath()
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val targetId = targetIncompleteImportId
        targetIncompleteImportId = null
        if (uris.isNotEmpty()) viewModel.importModels(context.contentResolver, uris, targetId)
    }

    // A sidecar is chosen independently from ordinary models. Copy it privately so the native loader
    // receives a stable filesystem path even when the original lives behind a SAF/cloud provider URI.
    val mtpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val modelPath = targetMtpModelPath
        targetMtpModelPath = null
        if (uri != null && modelPath != null) {
            viewModel.importAndAttachDraft(context.contentResolver, uri, modelPath)
        }
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
                            "Choose a GGUF from Internal storage to add it in place. The model stays in its original folder, " +
                                "so BigMoeOnEdge does not create another multi-GB copy. A storage-access permission may be " +
                                "requested the first time. Cloud/provider files may need the copy option below.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = ui.importProgress == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Import GGUF file(s) (copy)") }
                        OutlinedButton(
                            onClick = { noCopyPicker.launch(arrayOf("*/*")) },
                            enabled = ui.importProgress == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add GGUF from storage (no copy) may be slower") }
                        TextButton(onClick = { showAdvancedPath = !showAdvancedPath }) {
                            Text(if (showAdvancedPath) "Hide advanced path entry" else "Advanced: enter a path manually")
                        }
                        if (showAdvancedPath) {
                            Text(
                                "Use this only if the picker cannot expose the original filesystem path. Split GGUFs must be registered from the first shard.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            OutlinedTextField(
                                value = ui.existingPathInput,
                                onValueChange = viewModel::setExistingPathInput,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Existing GGUF path (no copy)") },
                                placeholder = { Text("/storage/emulated/0/Download/model-00001-of-00012.gguf") },
                                singleLine = true
                            )
                            val needsSharedStoragePermission = isSharedAbsolutePath(ui.existingPathInput) && !sharedPathAccessGranted
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (!isSharedAbsolutePath(ui.existingPathInput) || sharedPathAccessGranted) {
                                        viewModel.registerExistingPath()
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        val appSettings = Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        try {
                                            allFilesAccessLauncher.launch(appSettings)
                                        } catch (_: Exception) {
                                            allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                        }
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                    } else {
                                        viewModel.registerExistingPath()
                                    }
                                }) {
                                    Text(if (needsSharedStoragePermission) "Grant path access" else "Register path")
                                }
                                OutlinedButton(onClick = viewModel::refresh) { Text("Refresh") }
                            }
                            if (needsSharedStoragePermission) {
                                Text(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                                        "Android requires Allow access to manage all files for direct paths. Registration retries when you return."
                                    else
                                        "Allow file read access when Android asks. The file is not copied.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
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

            if (ui.drafts.isNotEmpty()) {
                item {
                    Text("MTP draft sidecars", style = MaterialTheme.typography.titleMedium)
                    Text("Attach one to each target above. Sidecars are hidden from the target-model list.", style = MaterialTheme.typography.bodySmall)
                }
                items(ui.drafts, key = { "draft:${it.path}" }) { draft ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(draft.name, style = MaterialTheme.typography.titleSmall)
                                Text("${ModelManager.formatBytes(draft.sizeBytes)} • " + if (draft.inAppStorage) "App storage" else "Registered path (no copy)", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { viewModel.delete(draft.path) }) { Text("Remove") }
                        }
                    }
                }
            }
            if (ui.incompleteImports.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Incomplete split models", style = MaterialTheme.typography.titleMedium)
                        Text("These imports are saved privately and survive app restarts. They cannot be loaded until every shard passes validation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(ui.incompleteImports, key = { "incomplete:${it.id}" }) { pending ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pending.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${pending.presentShardIndices.size}/${pending.expectedShardCount} shards imported • ${ModelManager.formatBytes(pending.totalSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("Missing shard indices: " + pending.missingShardIndices.joinToString { it.toString().padStart(5, '0') }.ifBlank { "none" }, style = MaterialTheme.typography.bodySmall)
                            Text("Status: ${pending.status}", style = MaterialTheme.typography.labelMedium, color = if (pending.validationMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                            pending.validationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        targetIncompleteImportId = pending.id
                                        picker.launch(arrayOf("*/*"))
                                    },
                                    enabled = ui.importProgress == null && pending.missingShardIndices.isNotEmpty() && pending.status != "Validation failed",
                                    modifier = Modifier.weight(1f)
                                ) { Text("Add missing files") }
                                OutlinedButton(
                                    onClick = { viewModel.removeIncompleteImport(pending.id) },
                                    enabled = ui.importProgress == null
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            if (ui.models.isEmpty() && ui.incompleteImports.isEmpty()) {
                item { Text("No models yet.", color = MaterialTheme.colorScheme.secondary) }
            }
            items(ui.models.filterNot { ModelManager.isProjectorName(it.name) }, key = { it.path }) { model ->
                ModelRow(
                    model = model,
                    isLoaded = (engine as? EngineState.Ready)?.info?.path == model.path,
                    busy = engine is EngineState.Loading,
                    onLoad = { viewModel.requestLoad(model.path) },
                    onDelete = { viewModel.delete(model.path) },
                    drafts = ui.drafts,
                    attachedDraftPath = ui.modelDrafts[model.path],
                    onAttachDraft = { viewModel.attachDraft(model.path, it) },
                    onPickDraft = {
                        targetMtpModelPath = model.path
                        mtpPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onRename = { viewModel.rename(model.path, it) },
                    settings = settings,
                    viewModel = viewModel
                )
            }
            // keeps the last row clear of the floating navigation bar
            item(key = "bottom_spacer") { androidx.compose.foundation.layout.Spacer(Modifier.height(112.dp)) }
        }
    }

    ui.pendingLoad?.let { pending -> PendingLoadDialog(viewModel, pending) }
}

/** The confirmation step of a load (memory advice + projector choice). Shared by the Models page and the chat's model picker. */
@Composable
internal fun PendingLoadDialog(viewModel: ModelViewModel, pending: PendingLoad) {
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
                    info.draftModelPath?.let { Text("Separate MTP draft: ${it.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall) }
                    info.loadWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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
    onDelete: () -> Unit,
    drafts: List<ModelFile>,
    attachedDraftPath: String?,
    onAttachDraft: (String?) -> Unit,
    onPickDraft: () -> Unit,
    onRename: (String) -> Unit,
    settings: EngineSettings,
    viewModel: ModelViewModel
) {
    var draftMenu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by remember(model.path, model.displayName) { mutableStateOf(model.displayName) }
    // MTP is optional and most people never use it, so its section stays folded until the arrow is pressed.
    var mtpExpanded by remember(model.path) { mutableStateOf(false) }
    val attachedName = drafts.firstOrNull { it.path == attachedDraftPath }?.name
    val mtpSummary = when (settings.spec) {
        SpecSource.MTP -> "On \u2022 " + (attachedName ?: "model's own head")
        SpecSource.NGRAM -> "Off (n-gram guess-ahead is on)"
        SpecSource.OFF -> if (attachedName != null) "Off \u2022 sidecar: $attachedName" else "Off"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    Text("${ModelManager.formatBytes(model.sizeBytes)} • " + if (model.inAppStorage) "App storage" else "Registered path (no copy)", style = MaterialTheme.typography.labelSmall)
                }
                if (isLoaded) {
                    Text("Loaded", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                } else {
                    TextButton(onClick = onLoad, enabled = !busy) { Text("Load") }
                }
                // Rename renames the model itself, so it lives here and not inside the (optional) MTP section.
                IconButton(onClick = { renameDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename model")
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove model")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { mtpExpanded = !mtpExpanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("MTP draft", style = MaterialTheme.typography.labelMedium)
                    Text(mtpSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (mtpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (mtpExpanded) "Collapse MTP draft" else "Expand MTP draft",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (mtpExpanded) {
                Text(
                    "Note: with an mmproj (vision projector), a message that contains an image is answered without guessing ahead (normal speed, greedy decoding). Text-only messages still use MTP.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Separate MTP sidecar", style = MaterialTheme.typography.labelMedium)
                        Text(attachedName ?: "No sidecar attached", style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { draftMenu = true }) { Text("Attach") }
                        DropdownMenu(expanded = draftMenu, onDismissRequest = { draftMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Choose separate MTP GGUF…") },
                                onClick = { draftMenu = false; onPickDraft() }
                            )
                            DropdownMenuItem(text = { Text("Use model's built-in MTP / none") }, onClick = { draftMenu = false; onAttachDraft(null) })
                            drafts.forEach { d ->
                                DropdownMenuItem(text = { Text(d.name) }, onClick = { draftMenu = false; onAttachDraft(d.path) })
                            }
                        }
                    }
                }
                MtpSettingsPanel(settings = settings, viewModel = viewModel)
            }
            if (!model.inAppStorage) Text("This file stays in its original folder; removing it here only unregisters it.", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Rename model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("New name") })
                    if (model.name.contains(Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE))) {
                        Text("This changes only the model display name. The original shard filenames stay unchanged so llama.cpp can discover every part.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { renameDialog = false; onRename(renameText) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Resolve only Android's real external-storage DocumentsProvider URIs to the backing file path.
 * Arbitrary cloud providers and Downloads virtual documents intentionally return null: their
 * content:// URI cannot safely be passed to the native GGUF loader as a filesystem path.
 */
private fun resolveSharedStorageDocumentPath(context: android.content.Context, uri: Uri): String? = runCatching {
    if (!DocumentsContract.isDocumentUri(context, uri) ||
        uri.authority != "com.android.externalstorage.documents") return@runCatching null

    val documentId = DocumentsContract.getDocumentId(uri)
    val colon = documentId.indexOf(':')
    if (colon <= 0 || colon == documentId.lastIndex) return@runCatching null

    val volumeId = documentId.substring(0, colon)
    val relativePath = documentId.substring(colon + 1).replace('\\', '/')
    if (relativePath.startsWith('/') || relativePath.split('/').any { it == ".." }) return@runCatching null

    val base = if (volumeId.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory()
    } else {
        // External-volume file roots usually look like /storage/<UUID>/Android/data/<package>/files.
        context.getExternalFilesDirs(null).filterNotNull().firstNotNullOfOrNull { appDir ->
            val normalized = appDir.absolutePath.replace('\\', '/')
            val marker = "/Android/data/${context.packageName}/"
            val at = normalized.indexOf(marker)
            if (at > 0 && normalized.substring(0, at).substringAfterLast('/') == volumeId) {
                File(normalized.substring(0, at))
            } else null
        } ?: return@runCatching null
    }.canonicalFile

    val selected = File(base, relativePath).canonicalFile
    if (selected.path != base.path && !selected.path.startsWith(base.path + File.separator)) return@runCatching null
    selected.path
}.getOrNull()

private fun hasSharedPathAccess(context: android.content.Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    else -> true
}

/** Only absolute paths in Android shared/external storage need the special storage grant. */
private fun isSharedAbsolutePath(rawPath: String): Boolean {
    val path = rawPath.trim().replace('\\', '/')
    return path.startsWith("/storage/") || path == "/sdcard" || path.startsWith("/sdcard/")
}

