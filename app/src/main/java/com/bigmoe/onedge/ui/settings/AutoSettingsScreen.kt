package com.bigmoe.onedge.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.bigmoe.onedge.data.local.datastore.AppVisualStyle
import com.bigmoe.onedge.ui.theme.AppTheme
import com.bigmoe.onedge.ui.theme.appSurfaceModifier
import com.bigmoe.onedge.core.AutoSave
import com.bigmoe.onedge.core.AutoTier
import com.bigmoe.onedge.core.AutoSaveCodec
import com.bigmoe.onedge.core.ModelProfileIdentity
import com.bigmoe.onedge.core.AutoTuner
import com.bigmoe.onedge.core.DeviceProfile
import com.bigmoe.onedge.core.ModelFacts
import com.bigmoe.onedge.core.ModelKind
import java.util.Locale
import com.bigmoe.onedge.i18n.tr

private fun speedText(s: AutoSave): String {
    val measured = s.measuredTps
    return if (measured != null) String.format(Locale.US, tr("%.1f tok/s measured  ·  ≈%.1f estimated"), measured, s.estTps)
    else String.format(Locale.US, tr("≈ %.1f tok/s (estimate)"), s.estTps)
}

private fun durationText(seconds: Int): String =
    if (seconds < 90) tr("about {0} seconds", seconds) else tr("about {0} minutes", (seconds + 59) / 60)

private fun kindText(kind: ModelKind): String = when (kind) {
    ModelKind.MOE -> tr("Mixture-of-Experts (MoE)")
    ModelKind.DENSE -> tr("Dense")
    ModelKind.UNKNOWN -> tr("Unknown type (treated as dense)")
}

private fun gb(mb: Long) = String.format(Locale.US, "%.1f GB", mb / 1024.0)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutoSettingsScreen(
    viewModel: AutoTuneViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.ui.collectAsState()
    val saves by viewModel.saves.collectAsState()
    val activeId by viewModel.activeId.collectAsState()
    val modelProfiles by viewModel.modelProfiles.collectAsState()
    val autoApplyProfiles by viewModel.autoApplyModelProfilesEnabled.collectAsState()
    val currentModelPath by viewModel.currentModelPath.collectAsState()
    val currentProfileId = currentModelPath?.let { path -> ModelProfileIdentity.fromPath(path)?.let { modelProfiles[it] } }
    val currentProfile = saves.firstOrNull { it.id == currentProfileId }

    var renameTarget by remember { mutableStateOf<AutoSave?>(null) }
    var deleteTarget by remember { mutableStateOf<AutoSave?>(null) }
    var measureIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingExport by remember { mutableStateOf<AutoSave?>(null) }
    var selectedTier by remember { mutableStateOf(AutoTier.BALANCED) }
    var customSettingsSelected by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri != null && target != null) viewModel.export(target.id, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSave(uri)
    }

    // Always look again when the screen opens: models may have been added or loaded since the app started.
    LaunchedEffect(Unit) {
        viewModel.refreshModels()
        viewModel.scan()
    }

    val busy = ui.busy != null
    val facts = ui.facts

    // Measuring loads a big model for minutes: keep the screen on so the app stays in the foreground
    // (a backgrounded app is the first thing Android kills when memory is short).
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (AppTheme.visualStyle == AppVisualStyle.SOLID) MaterialTheme.colorScheme.surface else Color.Transparent
                ),
                title = { Text(tr("AUTO SETTINGS"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                }
            )
        },
        modifier = modifier,
        containerColor = if (AppTheme.visualStyle == AppVisualStyle.SOLID) MaterialTheme.colorScheme.background else Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                tr("Choose your goal. The optimizer creates one profile for that goal; it does not generate three presets you did not ask for."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Panel(MaterialTheme.colorScheme.secondaryContainer) {
                Text(tr("MODEL PROFILE"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val modelLabel = currentModelPath?.substringAfterLast('/')?.ifBlank { tr("Loaded model") } ?: tr("No model loaded")
                Text(tr("Current model: {0}", modelLabel), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("Active profile: {0}", currentProfile?.name ?: "Global defaults"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    tr("When enabled, choosing Use while a model is loaded assigns that save only to that model. Profiles apply before the model session starts; other models retain their own profile or global defaults."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Automatically apply model profiles"), modifier = Modifier.weight(1f))
                    Switch(checked = autoApplyProfiles, onCheckedChange = viewModel::setAutoApplyModelProfilesEnabled)
                }
                OutlinedButton(
                    onClick = viewModel::resetCurrentModelProfile,
                    enabled = currentModelPath != null && currentProfileId != null
                ) { Text(tr("Reset this model to global defaults")) }
            }

            ui.busy?.let { text ->
                Panel(MaterialTheme.colorScheme.primaryContainer) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (ui.measuring) {
                        OutlinedButton(onClick = viewModel::cancelMeasure) { Text(tr("Cancel")) }
                    }
                }
            }

            ui.message?.let { text ->
                Panel(MaterialTheme.colorScheme.secondaryContainer) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.reloadNeeded && !busy) Button(onClick = viewModel::reloadModel) { Text(tr("Reload model now")) }
                        TextButton(onClick = viewModel::dismissMessage) { Text(tr("OK")) }
                    }
                }
            }

            // ------------------------------------------------------------ device
            Panel(MaterialTheme.colorScheme.surfaceContainerLow, border = true) {
                Text(tr("Your phone"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val d = ui.device
                if (d == null) {
                    Hint(tr("Scanning your phone…"))
                } else {
                    DeviceLines(d)
                }
                OutlinedButton(onClick = viewModel::scan, enabled = !ui.scanning && !busy) {
                    Text(if (ui.scanning) tr("Scanning…") else tr("Scan again"))
                }
            }

            // ------------------------------------------------------------ model
            Panel(MaterialTheme.colorScheme.surfaceContainerLow, border = true) {
                Text(tr("Model"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (ui.models.isEmpty()) {
                    Hint(tr("No models found. Add or load one on the Models page, then come back."))
                } else {
                    ChoiceSetting(
                        label = tr("Tune for"),
                        options = ui.models.map { it.path to it.displayName },
                        selected = ui.selectedPath ?: "",
                        enabled = !busy
                    ) { viewModel.selectModel(it) }
                    if (facts != null) {
                        Hint("${kindText(facts.kind)}  ·  ${gb(facts.sizeMb)}")
                    }
                }
                Text(tr("Optimization goal"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AutoTier.values().forEach { tier ->
                        if (!customSettingsSelected && selectedTier == tier) {
                            Button(onClick = { selectedTier = tier }, enabled = !busy) { Text(tr(tier.title)) }
                        } else {
                            OutlinedButton(onClick = { selectedTier = tier; customSettingsSelected = false }, enabled = !busy) { Text(tr(tier.title)) }
                        }
                    }
                    if (customSettingsSelected) {
                        Button(onClick = { customSettingsSelected = true }, enabled = !busy) { Text(tr("Custom settings")) }
                    } else {
                        OutlinedButton(onClick = { customSettingsSelected = true }, enabled = !busy) { Text(tr("Custom settings")) }
                    }
                }
                if (customSettingsSelected) {
                    val current = ui.engineSettings
                    Panel(MaterialTheme.colorScheme.surfaceContainerLow, border = true) {
                        Text(tr("CUSTOM SETTINGS"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Hint(tr("These controls save to the app's manual engine settings. Sampling changes apply on the next message; context, threads, batch width, cache and I/O changes need a model reload when they differ from the loaded session."))
                        ChoiceSetting(tr("Context length"), listOf(2048 to tr("2,048 tokens"), 4096 to tr("4,096 tokens"), 8192 to tr("8,192 tokens"), 16384 to tr("16,384 tokens"), 32768 to tr("32,768 tokens")), current.contextLength, enabled = !busy, onSelect = viewModel::updateCustomContext)
                        ChoiceSetting(tr("CPU decode threads"), listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4", 6 to "6", 8 to "8", 12 to "12"), current.threadCount, enabled = !busy, onSelect = viewModel::updateCustomThreads)
                        ChoiceSetting(tr("Prompt batch threads"), listOf(1 to "1", 2 to "2", 4 to "4", 6 to "6", 8 to "8", 12 to "12"), current.batchThreadCount, enabled = !busy, onSelect = viewModel::updateCustomBatchThreads)
                        ChoiceSetting(tr("Batch width"), listOf(128 to "128", 256 to "256", 512 to "512", 1024 to "1,024", 2048 to "2,048", 4096 to "4,096"), current.batchWidth, enabled = !busy, onSelect = viewModel::updateCustomBatchWidth)
                        ChoiceSetting(tr("Expert cache budget"), listOf(-1 to tr("Auto"), 512 to tr("512 MB"), 1024 to tr("1 GB"), 1500 to tr("1.5 GB"), 2000 to tr("2 GB"), 3000 to tr("3 GB"), 4000 to tr("4 GB")), current.cacheMb, enabled = !busy, onSelect = viewModel::updateCustomCache)
                        ChoiceSetting(tr("I/O threads"), (1..8).map { it to it.toString() }, current.ioThreads, enabled = !busy, onSelect = viewModel::updateCustomIoThreads)
                        ChoiceSetting(tr("Temperature"), listOf(0f to tr("0 (greedy)"), 0.2f to "0.2", 0.5f to "0.5", 0.7f to "0.7", 1f to "1.0", 1.2f to "1.2", 1.5f to "1.5", 2f to "2.0"), current.temperature, enabled = !busy, onSelect = viewModel::updateCustomTemperature)
                        ChoiceSetting(tr("Top-P"), listOf(0.5f to "0.50", 0.7f to "0.70", 0.8f to "0.80", 0.9f to "0.90", 0.95f to "0.95", 1f to "1.00"), current.topP, enabled = !busy, onSelect = viewModel::updateCustomTopP)
                        ChoiceSetting(tr("Top-K"), listOf(0 to tr("Model default (0)"), 20 to "20", 40 to "40", 60 to "60", 80 to "80", 100 to "100", 200 to "200"), current.topK, enabled = !busy, onSelect = viewModel::updateCustomTopK)
                        ChoiceSetting(tr("Seed"), listOf(-1 to tr("Random each run (-1)"), 0 to "0", 1 to "1", 42 to "42", 123 to "123", 1234 to "1234"), current.seed, enabled = !busy, onSelect = viewModel::updateCustomSeed)
                        if (ui.reloadNeeded && !busy) {
                            Text(tr("The loaded model needs a reload for the changed engine settings."), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = viewModel::reloadModel, modifier = Modifier.fillMaxWidth()) { Text(tr("Reload model now")) }
                        } else if (currentModelPath != null) {
                            Hint(tr("The loaded model already matches the reload-sensitive settings, or only per-request sampling values changed."))
                        }
                    }
                } else {
                    Hint(tr(selectedTier.tagline))
                    Button(
                        onClick = { viewModel.createSaves(selectedTier) },
                        enabled = ui.device != null && facts != null && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("Create {0} Settings", selectedTier.title)) }
                    Hint(tr("Creates one profile for the selected goal. The optimizer chooses supported settings; measured performance is the final judge."))
                }
                val forModel = facts?.let { f -> saves.filter { it.modelName == f.name } }.orEmpty()
                OutlinedButton(
                    onClick = { measureIds = forModel.map { it.id } },
                    enabled = forModel.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tr("Measure real speed of all {0} saves", forModel.size)) }
                Hint(tr("Measuring loads the model for each save, so it may take a while."))
            }

            // ------------------------------------------------------------ saves
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    tr("Your saves"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    enabled = !busy
                ) { Text(tr("Import")) }
            }
            if (saves.isEmpty()) {
                Hint(tr("No saves yet. Create them above, or import a save file."))
            }
            saves.forEach { save ->
                SaveCard(
                    save = save,
                    active = save.id == activeId,
                    enabled = !busy && facts != null,
                    onUse = { viewModel.use(save.id) },
                    onMeasure = { measureIds = listOf(save.id) },
                    onRename = { renameTarget = save },
                    onExport = {
                        pendingExport = save
                        exportLauncher.launch("${save.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")}.json")
                    },
                    onDelete = { deleteTarget = save }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------------------------------------------------------------- dialogs
    renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(tr("Rename save")) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(AutoSaveCodec.MAX_NAME) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = AutoSaveCodec.cleanName(text).isNotEmpty(),
                    onClick = { viewModel.rename(target.id, text); renameTarget = null }
                ) { Text(tr("Rename")) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(tr("Cancel")) } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("Delete save?")) },
            text = { Text(tr("“{0}” will be removed from this phone. Your current settings stay as they are.", target.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target.id); deleteTarget = null }) { Text(tr("Delete")) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(tr("Cancel")) } }
        )
    }

    measureIds?.let { ids ->
        val seconds = viewModel.measureSecondsFor(ids.size)
        AlertDialog(
            onDismissRequest = { measureIds = null },
            title = { Text(tr("This may take a while")) },
            text = {
                Text(
                    tr("Measuring loads the model once for each of the {0} save(s) and generates a short test, {1} in total. Chat is unavailable until it finishes, then your current model is loaded again. Keep the app open (the screen stays on). Saves that need more RAM than your phone can safely give are skipped instead of risking a crash. You can cancel at any time.", ids.size, durationText(seconds))
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.measure(ids); measureIds = null }) { Text(tr("Start")) }
            },
            dismissButton = { TextButton(onClick = { measureIds = null }) { Text(tr("Not now")) } }
        )
    }
}

@Composable
private fun DeviceLines(d: DeviceProfile) {
    val lines = listOf(
        tr("Memory: {0} total, {1} free", gb(d.totalRamMb), gb(d.availRamMb)),
        tr("CPU: {0} cores ({1} fast)  ·  {2}", d.cores, d.fastCores, d.soc),
        String.format(Locale.US, tr("Memory speed: about %.0f GB/s (measured just now)"), d.memBandwidthGBs),
        if (d.npuRuntime) tr("NPU prefill: available") else tr("NPU prefill: not available in this build or on this phone")
    )
    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveCard(
    save: AutoSave,
    active: Boolean,
    enabled: Boolean,
    onUse: () -> Unit,
    onMeasure: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val kind = runCatching { ModelKind.valueOf(save.kind) }.getOrDefault(ModelKind.UNKNOWN)
    val facts = ModelFacts(path = "", name = save.modelName, sizeBytes = 0, kind = kind, expertCount = 0, expertUsed = 0)
    val saveShape = RoundedCornerShape(16.dp)
    val saveBase = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val saveStyled = AppTheme.visualStyle != AppVisualStyle.SOLID
    Surface(
        shape = saveShape,
        color = if (saveStyled) Color.Transparent else saveBase,
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else if (saveStyled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().then(if (saveStyled) appSurfaceModifier(saveShape, saveBase) else Modifier)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (active) tr("{0}  ·  IN USE", save.name) else save.name,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
            )
            Text(tr(save.tier.title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(speedText(save), style = MaterialTheme.typography.bodyMedium)
            Hint(tr(save.tier.tagline))
            AutoTuner.describe(save.values, facts).forEach { Hint("• $it") }
            if (save.modelName.isNotBlank()) Hint(tr("Built for: {0}", save.modelName))
            save.lastError?.let {
                Text(tr("Last measurement failed: {0}", it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onUse, enabled = enabled && !active) { Text(if (active) tr("In use") else tr("Use")) }
                OutlinedButton(onClick = onMeasure, enabled = enabled) { Text(tr("Measure")) }
                OutlinedButton(onClick = onRename, enabled = enabled) { Text(tr("Rename")) }
                OutlinedButton(onClick = onExport, enabled = enabled) { Text(tr("Download")) }
                TextButton(onClick = onDelete, enabled = enabled) { Text(tr("Delete")) }
            }
        }
    }
}

@Composable
private fun Panel(
    color: androidx.compose.ui.graphics.Color,
    border: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val styled = AppTheme.visualStyle != AppVisualStyle.SOLID
    Surface(
        shape = shape,
        color = if (styled) Color.Transparent else color,
        border = if (styled) null else if (border) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = Modifier.fillMaxWidth().then(if (styled) appSurfaceModifier(shape, color) else Modifier)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
