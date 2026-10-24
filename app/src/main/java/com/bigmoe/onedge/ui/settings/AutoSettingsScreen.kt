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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.AutoSave
import com.bigmoe.onedge.core.AutoSaveCodec
import com.bigmoe.onedge.core.AutoTuner
import com.bigmoe.onedge.core.DeviceProfile
import com.bigmoe.onedge.core.ModelFacts
import com.bigmoe.onedge.core.ModelKind
import java.util.Locale

private fun speedText(s: AutoSave): String {
    val measured = s.measuredTps
    return if (measured != null) String.format(Locale.US, "%.1f tok/s measured  ·  ≈%.1f estimated", measured, s.estTps)
    else String.format(Locale.US, "≈ %.1f tok/s (estimate)", s.estTps)
}

private fun durationText(seconds: Int): String =
    if (seconds < 90) "about $seconds seconds" else "about ${(seconds + 59) / 60} minutes"

private fun kindText(kind: ModelKind): String = when (kind) {
    ModelKind.MOE -> "Mixture-of-Experts (MoE)"
    ModelKind.DENSE -> "Dense"
    ModelKind.UNKNOWN -> "Unknown type (treated as dense)"
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

    var renameTarget by remember { mutableStateOf<AutoSave?>(null) }
    var deleteTarget by remember { mutableStateOf<AutoSave?>(null) }
    var measureIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingExport by remember { mutableStateOf<AutoSave?>(null) }

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
                title = { Text("AUTO SETTINGS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Checks your phone and your model, then builds settings for you: Fastest, Balanced, or Most accurate. " +
                    "Keep the ones you like as named saves and switch between them any time.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ui.busy?.let { text ->
                Panel(MaterialTheme.colorScheme.primaryContainer) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (text.startsWith("Measuring")) {
                        OutlinedButton(onClick = viewModel::cancelMeasure) { Text("Cancel") }
                    }
                }
            }

            ui.message?.let { text ->
                Panel(MaterialTheme.colorScheme.secondaryContainer) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.reloadNeeded && !busy) Button(onClick = viewModel::reloadModel) { Text("Reload model now") }
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                }
            }

            // ------------------------------------------------------------ device
            Panel(MaterialTheme.colorScheme.surfaceContainerLow, border = true) {
                Text("Your phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val d = ui.device
                if (d == null) {
                    Hint("Scanning your phone…")
                } else {
                    DeviceLines(d)
                }
                OutlinedButton(onClick = viewModel::scan, enabled = !ui.scanning && !busy) {
                    Text(if (ui.scanning) "Scanning…" else "Scan again")
                }
            }

            // ------------------------------------------------------------ model
            Panel(MaterialTheme.colorScheme.surfaceContainerLow, border = true) {
                Text("Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (ui.models.isEmpty()) {
                    Hint("No models found. Add or load one on the Models page, then come back.")
                } else {
                    ChoiceSetting(
                        label = "Tune for",
                        options = ui.models.map { it.path to it.displayName },
                        selected = ui.selectedPath ?: "",
                        enabled = !busy
                    ) { viewModel.selectModel(it) }
                    if (facts != null) {
                        Hint("${kindText(facts.kind)}  ·  ${gb(facts.sizeMb)}")
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use the NPU for prompt prefill", style = MaterialTheme.typography.bodyMedium)
                        Hint("Experimental, off by default. Only Fastest and Balanced saves for MoE models, and only if the NPU is available.")
                    }
                    Switch(checked = ui.useNpu, onCheckedChange = viewModel::setUseNpu, enabled = !busy && (ui.device?.npuRuntime == true))
                }
                Button(
                    onClick = viewModel::createSaves,
                    enabled = ui.device != null && facts != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create Fastest · Balanced · Most accurate") }
                Hint("Adds three saves for this model, with an estimated speed for each.")
                val forModel = facts?.let { f -> saves.filter { it.modelName == f.name } }.orEmpty()
                OutlinedButton(
                    onClick = { measureIds = forModel.map { it.id } },
                    enabled = forModel.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Measure real speed of all ${forModel.size} saves") }
                Hint("Measuring loads the model for each save, so it may take a while.")
            }

            // ------------------------------------------------------------ saves
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "Your saves", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    enabled = !busy
                ) { Text("Import") }
            }
            if (saves.isEmpty()) {
                Hint("No saves yet. Create them above, or import a save file.")
            }
            saves.forEach { save ->
                SaveCard(
                    save = save,
                    active = save.id == activeId,
                    enabled = !busy && facts != null,
                    onUse = { viewModel.use(save.id) },
                    onMeasure = { measureIds = listOf(save.id) },
                    onSafer = { viewModel.makeSafer(save.id) },
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
            title = { Text("Rename save") },
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
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete save?") },
            text = { Text("“${target.name}” will be removed from this phone. Your current settings stay as they are.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target.id); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    measureIds?.let { ids ->
        val seconds = viewModel.measureSecondsFor(ids.size)
        AlertDialog(
            onDismissRequest = { measureIds = null },
            title = { Text("This may take a while") },
            text = {
                Text(
                    "Measuring loads the model once for each of the ${ids.size} save(s) and generates a short test, " +
                        "${durationText(seconds)} in total. Chat is unavailable until it finishes, then your current " +
                        "model is loaded again. Keep the app open (the screen stays on). Saves that need more RAM " +
                        "than your phone can safely give are skipped instead of risking a crash. You can cancel at any time."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.measure(ids); measureIds = null }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { measureIds = null }) { Text("Not now") } }
        )
    }
}

@Composable
private fun DeviceLines(d: DeviceProfile) {
    val lines = listOf(
        "Memory: ${gb(d.totalRamMb)} total, ${gb(d.availRamMb)} free",
        "CPU: ${d.cores} cores (${d.fastCores} fast)  ·  ${d.soc}",
        String.format(Locale.US, "Memory speed: about %.0f GB/s (measured just now)", d.memBandwidthGBs),
        if (d.npuRuntime) "NPU prefill: available" else "NPU prefill: not available in this build or on this phone"
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
    onSafer: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val kind = runCatching { ModelKind.valueOf(save.kind) }.getOrDefault(ModelKind.UNKNOWN)
    val facts = ModelFacts(path = "", name = save.modelName, sizeBytes = 0, kind = kind, expertCount = 0, expertUsed = 0)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (active) "${save.name}  ·  IN USE" else save.name,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
            )
            Text(save.tier.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(speedText(save), style = MaterialTheme.typography.bodyMedium)
            Hint(save.tier.tagline)
            AutoTuner.describe(save.values, facts).forEach { Hint("• $it") }
            if (save.modelName.isNotBlank()) Hint("Built for: ${save.modelName}")
            save.lastError?.let {
                Text("Last measurement failed: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onUse, enabled = enabled && !active) { Text(if (active) "In use" else "Use") }
                OutlinedButton(onClick = onMeasure, enabled = enabled) { Text("Measure") }
                OutlinedButton(onClick = onSafer, enabled = enabled) { Text("Lower memory") }
                OutlinedButton(onClick = onRename, enabled = enabled) { Text("Rename") }
                OutlinedButton(onClick = onExport, enabled = enabled) { Text("Download") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color,
        border = if (border) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
