package com.bigmoe.onedge.ui.settings

import com.bigmoe.onedge.ui.theme.AppCard
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bigmoe.onedge.core.DenseWeights
import com.bigmoe.onedge.core.DecisionPrefixCache
import com.bigmoe.onedge.core.KvCacheType
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.CACHE_AUTO_MB
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.AppVisualStyle
import com.bigmoe.onedge.data.local.datastore.WebSearchMode
import com.bigmoe.onedge.data.local.datastore.loadConfigDiffers
import com.bigmoe.onedge.ui.theme.PalettePresets
import com.bigmoe.onedge.ui.theme.PaletteSpec
import com.bigmoe.onedge.ui.theme.PaletteStore
import com.bigmoe.onedge.ui.theme.AppTheme
import com.bigmoe.onedge.ui.theme.appSurfaceModifier
import kotlinx.coroutines.delay
import java.io.File
import com.bigmoe.onedge.i18n.I18n
import com.bigmoe.onedge.i18n.tr

private const val DEBOUNCE_MS = 500L

/** Same choice lists as the original BigMoeOnEdge Android app (AppSettings.*_CHOICES). */
private object Choices {
    val CACHE = listOf(CACHE_AUTO_MB, 0, 500, 1000, 1250, 1500, 1750, 2000, 3000, 4000, 5000, 6000)
    val CACHE_CEIL = listOf(0, 2000, 3000, 4000, 5000, 6000)
    val IO = listOf(1, 2, 4, 8)
    val TOPK = listOf(0, 6, 4, 3, 2)
    val PREFETCH = listOf(0, 1, 2, 4)
    val PREDICT_SPEC = listOf(0, 1, 2, 4)
    val ROUTE_AHEAD = listOf(0, 1, 2, 4)
    val DROP_COLD = listOf(0, 50, 75, 100)
    val SUBSTITUTE = listOf(0, 10, 15, 20, 30)
    // Odd counts matter on 1+4+3 phones (e.g. Snapdragon 8 Gen 2): 5 threads fill the five fast cores exactly.
    val THREADS = listOf(1, 2, 3, 4, 5, 6, 7, 8)
    val CTX_CHECKPOINTS = listOf(0, 1, 2, 3, 4, 6, 8)
    val AUTO_COMPACT_PCT = listOf(40, 50, 60, 70)
    val BATCH_WIDTH = listOf(32, 64, 128, 256, 384, 512, 768, 1024, 1536, 2048)
    val NPU_MIN_TOKENS = listOf(8, 16, 32, 64, 128, 256)
    val NPU_ROUTED_FULL_PCT = listOf(50, 70, 85, 95, 100)
    val NGRAM_MIN = listOf(1, 2, 3, 4, 5, 6, 8, 12)
    val NGRAM_MAX = listOf(3, 4, 6, 8, 12, 16, 24, 32, 48, 64)
    val DRAFT_MAX = listOf(1, 2, 3, 4, 5, 6, 7, 8)
    val NPREDICT = listOf(16, 32, 48, 64, 128, 256, 512, 1024, 2048, 4096, -1)
    val IMAGE_DIM = listOf(256, 384, 512, 768, 1024, 1344)
    val VIDEO_FRAMES = listOf(1, 2, 4, 6, 8, 12, 16)
}

private fun denseLabel(d: DenseWeights) = when (d) {
    DenseWeights.MMAP -> tr("Mmap (baseline)")
    DenseWeights.WARMED -> tr("Warm at load")
    DenseWeights.ANONYMOUS -> tr("Anon (O_DIRECT)")
    DenseWeights.PINNED -> tr("Pinned (dma-buf)")
}

private fun denseBlurb(d: DenseWeights) = when (d) {
    DenseWeights.MMAP -> tr("Left to the kernel, faulted in as touched. The baseline.")
    DenseWeights.WARMED -> tr("Paged in once at load, so the first tokens do not fault. Best when the model fits in RAM.")
    DenseWeights.ANONYMOUS -> tr("Read into the app's own memory. Reclaim compresses them instead of dropping them, so a refault costs no flash read. The default.")
    DenseWeights.PINNED -> tr("As Anon, but reclaim cannot touch it at all, not even to compress. Pays off over a long conversation.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenModels: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenAutoSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loaded by viewModel.settingsState.collectAsState()
    val engine by viewModel.engineState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (AppTheme.visualStyle == AppVisualStyle.SOLID) MaterialTheme.colorScheme.surface else Color.Transparent
                ),
                title = { Text(tr("SETTINGS"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) }
            )
        },
        modifier = modifier,
        containerColor = if (AppTheme.visualStyle == AppVisualStyle.SOLID) MaterialTheme.colorScheme.background else Color.Transparent
    ) { innerPadding ->
        val settings = loaded
        if (settings == null) {
            Column(Modifier.fillMaxSize().padding(innerPadding)) {}
        } else {
            SettingsContent(
                settings = settings,
                engine = engine,
                viewModel = viewModel,
                onOpenModels = onOpenModels,
                onOpenWorkspace = onOpenWorkspace,
                onOpenAutoSettings = onOpenAutoSettings,
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsContent(
    settings: EngineSettings,
    engine: EngineState,
    viewModel: SettingsViewModel,
    onOpenModels: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenAutoSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val readyInfo = (engine as? EngineState.Ready)?.info
    val reloadNeeded = readyInfo != null && loadConfigDiffers(readyInfo.config, settings)
    // Streaming settings only apply when the loaded model is a Mixture-of-Experts one (chosen automatically on load).
    val stream = readyInfo?.config?.mmapBaseline != true
    val cacheOn = stream && settings.cacheMb != 0

    // Text fields keep their own state, seeded ONCE from the stored value, and write back debounced.
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var braveKey by remember { mutableStateOf(settings.braveApiKey) }
    var searxUrl by remember { mutableStateOf(settings.searxngUrl) }
    var temp by remember { mutableFloatStateOf(settings.temperature) }
    var topP by remember { mutableFloatStateOf(settings.topP) }
    var topK by remember { mutableFloatStateOf(settings.topK.toFloat()) }
    var minP by remember { mutableFloatStateOf(settings.minP) }
    var repeatPenalty by remember { mutableFloatStateOf(settings.repeatPenalty) }
    var repeatLastN by remember { mutableFloatStateOf(settings.repeatLastN.toFloat()) }
    var frequencyPenalty by remember { mutableFloatStateOf(settings.frequencyPenalty) }
    var presencePenalty by remember { mutableFloatStateOf(settings.presencePenalty) }
    var seedText by remember { mutableStateOf(settings.seed.toString()) }
    var cacheFloorText by remember { mutableStateOf(settings.cacheFloorMb.toString()) }
    var contextText by remember { mutableStateOf(settings.contextLength.toString()) }
    val contextValid = ContextInput.parse(contextText) != null
    var experimental by remember { mutableStateOf(false) }
    var performanceExpanded by remember { mutableStateOf(false) }
    var toolsMediaExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var appearanceExpanded by remember { mutableStateOf(false) }
    var chatExpanded by remember { mutableStateOf(false) }
    var mediaExpanded by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var workspaceExpanded by remember { mutableStateOf(false) }
    var editingPalette by remember { mutableStateOf<PaletteSpec?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    // Reveal the reload action automatically whenever a load-sensitive setting diverges from the live session.
    LaunchedEffect(reloadNeeded) { if (reloadNeeded) modelExpanded = true }

    LaunchedEffect(systemPrompt) {
        if (systemPrompt != settings.systemPrompt) { delay(DEBOUNCE_MS); viewModel.updateSystemPrompt(systemPrompt) }
    }
    LaunchedEffect(braveKey) {
        if (braveKey != settings.braveApiKey) { delay(DEBOUNCE_MS); viewModel.updateBraveApiKey(braveKey) }
    }
    LaunchedEffect(searxUrl) {
        if (searxUrl != settings.searxngUrl) { delay(DEBOUNCE_MS); viewModel.updateSearxngUrl(searxUrl) }
    }
    LaunchedEffect(contextText) {
        val v = ContextInput.parse(contextText)
        if (v != null && v != settings.contextLength) { delay(DEBOUNCE_MS); viewModel.updateContextLength(v) }
    }
    LaunchedEffect(cacheFloorText) {
        val v = cacheFloorText.toIntOrNull()
        if (v != null && v != settings.cacheFloorMb) { delay(DEBOUNCE_MS); viewModel.updateCacheFloorMb(v) }
    }
    LaunchedEffect(seedText) {
        val v = seedText.toIntOrNull()
        if (v != null && v >= -1 && v != settings.seed) { delay(DEBOUNCE_MS); viewModel.updateSeed(v) }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        AutoSettingsEntry(onClick = onOpenAutoSettings)
        Spacer(Modifier.height(10.dp))
        LanguageChooser()
        Spacer(Modifier.height(10.dp))
        // Settings are grouped in the requested order: appearance, model, engine, tools/media, diagnostics.
        SettingsFoldout(
            title = tr("Appearance"), subtitle = tr("Theme palettes and custom colours"), expanded = appearanceExpanded,
            onToggle = { appearanceExpanded = !appearanceExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = appearanceExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ appearance
            val custom = PaletteStore.parse(settings.customPalettesJson)
            val selectedCustom = custom.firstOrNull { it.id == settings.themeId }
            Text(tr("Surface style"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(tr("Choose how major surfaces look. Gradient tints every surface from your palette. Glass uses translucent frosted panels with light-catching edges over soft colour glows."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    AppVisualStyle.SOLID to tr("Solid colors"),
                    AppVisualStyle.GRADIENT to tr("Gradient"),
                    AppVisualStyle.LIQUID_GLASS to tr("Glass")
                ).forEach { (style, label) ->
                    if (settings.visualStyle == style) {
                        Button(onClick = { viewModel.updateVisualStyle(style) }, modifier = Modifier.weight(1f)) { Text(label, textAlign = TextAlign.Center) }
                    } else {
                        OutlinedButton(onClick = { viewModel.updateVisualStyle(style) }, modifier = Modifier.weight(1f)) { Text(label, textAlign = TextAlign.Center) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val recommendedIds = listOf("paper", "pearl", "mint", "graphite", "ocean")
            val recommended = recommendedIds.mapNotNull { PalettePresets.byId(it) }
            val otherPresets = PalettePresets.all.filterNot { p -> recommendedIds.contains(p.id) }
            Text(tr("Recommended"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recommended.forEach { palette ->
                    PaletteChip(
                        palette = palette, selected = settings.themeId == palette.id,
                        onClick = { viewModel.selectTheme(palette.id) },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(tr("More palettes"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                otherPresets.forEach { palette ->
                    PaletteChip(
                        palette = palette, selected = settings.themeId == palette.id,
                        onClick = { viewModel.selectTheme(palette.id) },
                        modifier = Modifier.width(150.dp)
                    )
                }
                custom.forEach { palette ->
                    PaletteChip(
                        palette = palette, selected = settings.themeId == palette.id,
                        onClick = { viewModel.selectTheme(palette.id) },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val base = PaletteStore.resolve(settings.themeId, custom, false)
                    editingPalette = base.copy(id = PaletteStore.newId(custom), name = tr("My palette"), custom = true)
                    editingIsNew = true
                }) { Text(tr("+ New custom palette")) }
                if (selectedCustom != null) {
                    OutlinedButton(onClick = { editingPalette = selectedCustom; editingIsNew = false }) { Text(tr("Edit selected")) }
                }
            }
            Hint(tr("Recommended: Paper, Pearl, Mint, Graphite and Ocean. Custom palettes stay saved on this device."))
            Divider()


            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = tr("Model & loading"), subtitle = tr("Current model status and model-management shortcuts"), expanded = modelExpanded,
            onToggle = { modelExpanded = !modelExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = modelExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ model
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (engine) {
                        is EngineState.Ready -> tr("Loaded: {0}", engine.info.name)
                        is EngineState.Loading -> tr("Loading…")
                        is EngineState.Failed -> tr("Last load failed")
                        EngineState.Unloaded -> tr("No model loaded")
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = onOpenModels) { Text(tr("Manage models")) }
            }
            if (readyInfo != null) {
                Hint(
                    if (readyInfo.config.mmapBaseline) tr("Regular (non-MoE) model: loaded the ordinary way, expert streaming is off automatically")
                    else tr("Expert streaming ON • I/O–compute overlap: {0}", if (readyInfo.overlapActive) tr("active") else tr("not active"))
                )
            }
            if (reloadNeeded) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            tr("Engine settings changed since the model was loaded. The engine fixes them when it opens the model."),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { viewModel.reloadModel() }) { Text(tr("Reload model now")) }
                    }
                }
            }
            Divider()


        SettingsFoldout(
            title = tr("Chat"), subtitle = tr("System prompt and response length"), expanded = chatExpanded,
            onToggle = { chatExpanded = !chatExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = chatExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ chat (per message)
            Text(tr("System prompt"), style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Hint(tr("Applies to the next message. Changing it makes the engine re-read the conversation once."))
            Spacer(Modifier.height(12.dp))
            ChoiceSetting(tr("Tokens to generate"), Choices.NPREDICT.map { it to if (it < 0) tr("Unlimited (until the context is full)") else it.toString() },
                selected = if (settings.maxTokens <= 0) -1 else settings.maxTokens) { viewModel.updateMaxTokens(it) }
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = tr("Engine performance"),
            subtitle = tr("Expert streaming, cache, compute and context controls"),
            expanded = performanceExpanded,
            onToggle = { performanceExpanded = !performanceExpanded }
        )
        Spacer(Modifier.height(8.dp))
        if (performanceExpanded) {
            // ------------------------------------------------------------ accelerator / NPU
            PerformanceGroup(tr("Accelerator · NPU prompt prefill"), tr("Qualcomm Hexagon prompt acceleration")) {
            Hint(tr("These options apply only when the APK includes the Hexagon backend. Prompt prefill can use the NPU; token-by-token decode remains on CPU."))
            val npuCombinationAllowed = !settings.rowStream
            // Keep a stale saved-on switch interactive so an unavailable NPU preference can be turned off;
            // all other HTP controls remain disabled until the runtime probe really opens HTP0.
            val npuToggleEnabled = (viewModel.npuPrefillAvailable && npuCombinationAllowed) || settings.npuPrefill
            val loadedModel = (engine as? EngineState.Ready)?.info
            SwitchRow(
                tr("NPU prefill (Snapdragon Hexagon)"),
                when {
                    settings.npuPrefill && !viewModel.npuPrefillAvailable -> tr("This preference is saved as on, but HTP is unavailable. Turn this switch off to allow CPU-only model loading.")
                    !npuCombinationAllowed -> tr("Unavailable while Row-stream tables are enabled. Turn that option off before enabling NPU prefill.")
                    loadedModel?.npuPrefillReady == true && loadedModel.config.npuPrefill && settings.npuPrefill -> tr("Enabled for the loaded model: HTP0 opened during model load. Actual execution is confirmed only when logcat reports NPU prefill device_nodes greater than zero.")
                    loadedModel?.npuPrefillReady == true && !settings.npuPrefill -> tr("The currently loaded model still has HTP prefill active; reload it to apply this saved-off preference.")
                    settings.npuPrefill -> tr("Selected for the next model load. Prompt graphs may run on HTP; token-by-token decode remains on CPU.")
                    else -> tr("Uses the HTP device for eligible wide prompt graphs. This changes only after the model is reloaded; execution must be confirmed from device-node telemetry.")
                },
                checked = settings.npuPrefill,
                enabled = npuToggleEnabled
            ) {
                viewModel.updateNpuPrefill(it)
            }
            Hint(viewModel.npuPrefillStatus)
            ChoiceSetting(
                    tr("NPU prefill loader threads"),
                    listOf(2 to "2", 4 to "4", 6 to "6", 8 to "8", 12 to "12", 16 to "16"),
                    settings.npuLoaders,
                    enabled = npuToggleEnabled && settings.npuPrefill
                ) { viewModel.updateNpuLoaders(it) }
                // With Guess ahead the CPU verify batch is draftMax + 1 wide; only thresholds above it are offered.
                val npuMinChoices = Choices.NPU_MIN_TOKENS.filter {
                    it <= settings.toLoadConfig().nUbatch && it >= settings.toLoadConfig().npuMinTokensFloor
                }.ifEmpty { listOf(settings.toLoadConfig().npuMinTokensFloor) }
                ChoiceSetting(
                    tr("Minimum prompt tokens sent to NPU"),
                    npuMinChoices.map { it to tr("{0} tokens", it) },
                    (npuMinChoices.lastOrNull { it <= settings.npuMinTokens } ?: npuMinChoices.first()),
                    enabled = npuToggleEnabled && settings.npuPrefill
                ) { viewModel.updateNpuMinTokens(it) }
                SwitchRow(
                    tr("Routed expert reads during NPU prefill"),
                    tr("Predict which experts a prompt graph needs and load those instead of reading every expert in the layer. Falls back to a full-layer read when the predicted routing is dense."),
                    settings.npuRoutedPrefill,
                    enabled = npuToggleEnabled && settings.npuPrefill
                ) { viewModel.updateNpuRoutedPrefill(it) }
                if (settings.npuRoutedPrefill) {
                    ChoiceSetting(
                        tr("Full-layer fallback threshold"),
                        Choices.NPU_ROUTED_FULL_PCT.map { it to tr("{0}% of experts", it) },
                        settings.npuRoutedFullPct,
                        enabled = npuToggleEnabled && settings.npuPrefill
                    ) { viewModel.updateNpuRoutedFullPct(it) }
                    Hint(tr("At higher thresholds, more layers use routed reads; a lower threshold chooses a full-layer read sooner. This is a memory-I/O trade-off, not a quality setting."))
                }
            if (!viewModel.npuPrefillAvailable) {
                Hint(tr("Loader, threshold and routed-read preferences do not activate hardware by themselves. They stay disabled until the runtime can open HTP0."))
            }
            } // NPU group
            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------------------ streaming (engine)
            PerformanceGroup(tr("MoE streaming & I/O"), tr("Expert cache, flash reads and memory residency")) {
            Hint(tr("Engine settings are fixed when a model opens. After changing them, press Reload. MoE expert streaming is selected automatically for supported MoE models; dense models use the standard load path."))
            Spacer(Modifier.height(12.dp))
            ChoiceSetting(
                tr("Expert cache (MiB)"),
                Choices.CACHE.map { it to (if (it == CACHE_AUTO_MB) tr("Auto") else if (it == 0) tr("off") else tr("{0} MiB", it)) },
                selected = settings.cacheMb, enabled = stream
            ) { viewModel.updateCacheMb(it) }
            Hint(tr("A resident expert costs no read, so a bigger cache means less waiting on flash, paid for in RAM. Auto sizes it once at load. The smallest rungs sit below the engine's floor and only churn."))
            if (settings.cacheMb in 1 until 1500) {
                SwitchRow(
                    tr("Allow a small expert cache (<1500 MiB)"),
                    tr("Small caches may thrash and reduce performance. Enabled by default to preserve BigMoeOnEdge+'s existing behavior when one of the small cache sizes is selected."),
                    settings.forceCache,
                    enabled = stream
                ) { viewModel.updateForceCache(it) }
            }
            ChoiceSetting(
                tr("Auto cache ceiling (MiB)"),
                Choices.CACHE_CEIL.map { it to if (it == 0) tr("no cap") else tr("{0} MiB", it) },
                selected = settings.cacheCeilMb, enabled = stream && settings.cacheMb == CACHE_AUTO_MB
            ) { viewModel.updateCacheCeilMb(it) }
            Hint(tr("Caps what Auto may claim. The system counts our own mapped weights as free, so uncapped it can ask for more than exists."))
            if (settings.cacheMb == CACHE_AUTO_MB) {
                OutlinedTextField(
                    value = cacheFloorText,
                    onValueChange = { cacheFloorText = it.filter(Char::isDigit) },
                    label = { Text(tr("Auto: keep this much RAM free (MB)")) },
                    singleLine = true,
                    enabled = stream,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }
            ChoiceSetting(tr("Parallel I/O lanes"), Choices.IO.map { it to it.toString() }, settings.ioThreads, enabled = stream) {
                viewModel.updateIoThreads(it)
            }
            Hint(tr("Expert reads in flight at once. Helps only until the flash saturates."))
            SwitchRow(
                tr("Direct I/O (O_DIRECT)"),
                tr("Bypass the page cache, so the system keeps no second copy of what the cache already holds. Falls back where unsupported."),
                settings.oDirect, enabled = stream
            ) { viewModel.updateODirect(it) }
            SwitchRow(
                tr("I/O and compute overlap"),
                "Enabled by default. Issue expert reads asynchronously while the CPU computes the current layer, hiding flash latency. It only runs for streamed MoE models in an APK built with the expert-ready hook." +
                    if (readyInfo != null && !readyInfo.overlapActive && settings.overlap && stream) tr(" The loaded model reports overlap is not active.") else "",
                settings.overlap, enabled = stream
            ) { viewModel.updateOverlap(it) }
            ChoiceSetting(
                tr("Dense weights"),
                DenseWeights.values().map { it to denseLabel(it) },
                settings.denseWeights, enabled = stream
            ) { viewModel.updateDenseWeights(it) }
            Hint(denseBlurb(settings.denseWeights))
            SwitchRow(
                tr("Stream row-gathered tables"),
                tr("A dense table the model only reads a few ROWS from per token (the token embedding) does not need to be in RAM: only the rows are read, from flash. Lossless - the output is identical. On a model where no table qualifies this does nothing. This is incompatible with NPU prefill."),
                settings.rowStream, enabled = stream && !settings.npuPrefill
            ) { viewModel.updateRowStream(it) }
            ChoiceSetting(
                tr("Row-stream resident window"),
                listOf(16 to tr("16 MiB"), 32 to tr("32 MiB"), 64 to tr("64 MiB"), 128 to tr("128 MiB"), 256 to tr("256 MiB")),
                settings.rowStreamMb,
                enabled = stream && settings.rowStream
            ) { viewModel.updateRowStreamMb(it) }
            Hint(tr("RAM window used to keep recently accessed rows. Larger windows reduce repeated reads but leave less RAM for experts and the OS."))
            SwitchRow(
                tr("Release the model mapping"),
                tr("Once every weight has been copied into the app's own memory, the model file does not need to stay mapped. Lossless - the output is identical. Needs Dense weights on Anon or Pinned."),
                settings.releaseMmap,
                enabled = stream && (settings.denseWeights == DenseWeights.ANONYMOUS || settings.denseWeights == DenseWeights.PINNED)
            ) { viewModel.updateReleaseMmap(it) }
            Divider()
            } // streaming group
            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------------------ speed / quality
            PerformanceGroup(tr("Speed / quality"), tr("Decode throughput, routing trade-offs and advanced controls")) {
            ChoiceSetting(
                tr("Drop cold experts (% of even share)"),
                Choices.DROP_COLD.map {
                    it to when (it) {
                        0 -> tr("off")
                        50 -> tr("50% (barely bites)")
                        75 -> tr("75% (recommended)")
                        100 -> tr("100% (fastest, roughest)")
                        else -> "$it%"
                    }
                },
                settings.dropColdPct, enabled = cacheOn
            ) { viewModel.updateDropColdPct(it) }
            Hint(tr("Skips a routed expert only when it is a cache miss and the router barely wanted it, so quality is spent only where it buys a read. A resident expert always runs and the top one is never dropped. Changes the reply, and not the same way twice: it depends on what the cache held."))
            val topkNow = readyInfo?.nExpertUsed ?: 0
            if (settings.dropColdPct > 0 && topkNow in 1..4) {
                Text(
                    tr("This model routes very few experts per token, so the same share covers much more of the reply. Check the answers, or turn this off."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            }
            ChoiceSetting(tr("Active experts (top-k)"), Choices.TOPK.map { it to if (it == 0) tr("model default") else "$it" }, settings.nExpertUsed) {
                viewModel.updateNExpertUsed(it)
            }
            Hint(tr("Consult fewer experts per token than the model asks for. Cuts compute and reads together, and changes the reply."))

            Spacer(Modifier.height(8.dp))
            ExperimentalHeader(expanded = experimental) { experimental = !experimental }
            if (experimental) {
                Spacer(Modifier.height(8.dp))
                Section(tr("I/O scheduling"))
                SwitchRow(
                    tr("Start reads in two waves"),
                    tr("Publishes the first projection's expert reads before queuing the remaining projections, allowing I/O lanes to start earlier. Experimental; requires expert streaming, cache, and overlap. Leave off if it is slower on your device."),
                    settings.ioTwoWave,
                    enabled = stream && cacheOn && settings.overlap
                ) { viewModel.updateIoTwoWave(it) }
                Spacer(Modifier.height(8.dp))
                Section(tr("Speculation and routing"))
                ChoiceSetting(
                    tr("Guess ahead"),
                    listOf(SpecSource.OFF to tr("Off"), SpecSource.MTP to tr("Model's own head (MTP)"), SpecSource.NGRAM to tr("Repeated text (n-gram)")),
                    settings.spec,
                    enabled = settings.routeAhead == 0 && !settings.rowStream
                ) { viewModel.updateSpec(it) }
                Hint(tr("Draft several likely next tokens and verify them together. It can improve tokens/s but may use more memory. MTP requires a compatible embedded head or an attached sidecar on the Models page; n-gram only helps when text repeats. Guess ahead forces greedy decoding (temperature 0)."))
                if (settings.spec != SpecSource.OFF) {
                    ChoiceSetting(tr("Tokens guessed per pass"), Choices.DRAFT_MAX.map { it to it.toString() }, settings.draftMax) {
                        viewModel.updateDraftMax(it)
                    }
                }
                if (settings.spec == SpecSource.MTP) {
                    ChoiceSetting(
                        tr("MTP confidence floor"),
                        listOf(0 to tr("Always draft"), 40 to tr("Above 40%"), 60 to tr("Above 60%"), 80 to tr("Above 80%")),
                        settings.mtpPMinPct
                    ) { viewModel.updateMtpPMinPct(it) }
                    Hint(tr("Stops drafting when the MTP head's probability for its next token is below this threshold. Higher values spend less time on weak guesses, but may draft fewer tokens."))
                    Hint(tr("The separate MTP GGUF attachment and its enable switch remain under Models → MTP draft."))
                }
                if (settings.spec == SpecSource.NGRAM) {
                    ChoiceSetting(
                        tr("N-gram minimum match"),
                        Choices.NGRAM_MIN.filter { it <= settings.ngramMaxMatch }.map { it to tr("{0} tokens", it) },
                        settings.ngramMinMatch.coerceAtMost(settings.ngramMaxMatch)
                    ) {
                        viewModel.updateNgramMinMatch(it)
                        if (it > settings.ngramMaxMatch) viewModel.updateNgramMaxMatch(it)
                    }
                    ChoiceSetting(
                        tr("N-gram maximum match"),
                        Choices.NGRAM_MAX.filter { it >= settings.ngramMinMatch }.map { it to tr("{0} tokens", it) },
                        settings.ngramMaxMatch.coerceAtLeast(settings.ngramMinMatch)
                    ) {
                        viewModel.updateNgramMaxMatch(it)
                        if (it < settings.ngramMinMatch) viewModel.updateNgramMinMatch(it)
                    }
                    Hint(tr("Minimum controls how exact a repeated suffix must be before drafting. Maximum bounds the lookup work; long matches are not always more useful."))
                }
                ChoiceSetting(
                    tr("Temporal prefetch (layers)"),
                    Choices.PREFETCH.map { it to if (it == 0) tr("off") else "$it" },
                    settings.prefetchLayers,
                    enabled = stream && cacheOn && !settings.predictPrefetch && settings.routeAhead == 0
                ) { viewModel.updatePrefetchLayers(it) }
                Hint(tr("Bets a layer reuses the previous token's experts and reads them on idle lanes. Needs the cache."))
                SwitchRow(
                    tr("Predictive prefetch"),
                    tr("Runs the next layer's own router a layer early and prefetches what it names. More accurate than the bet above, and replaces it. Needs the cache."),
                    settings.predictPrefetch,
                    enabled = stream && cacheOn && settings.prefetchLayers == 0 && settings.routeAhead == 0
                ) { viewModel.updatePredictPrefetch(it) }
                if (settings.predictPrefetch) {
                    ChoiceSetting(
                        tr("Predicted misses to read ahead"),
                        Choices.PREDICT_SPEC.map { it to if (it == 0) tr("retention only") else "$it" },
                        settings.predictSpecMax, enabled = stream && cacheOn
                    ) { viewModel.updatePredictSpecMax(it) }
                    Hint(tr("Retention only reads nothing and just protects what the prediction names, which is the safe setting."))
                }
                ChoiceSetting(
                    tr("Prefer cached experts (% of score range)"),
                    Choices.SUBSTITUTE.map { it to if (it == 0) tr("off") else "$it%" },
                    settings.substitutePct, enabled = stream && cacheOn
                ) { viewModel.updateSubstitutePct(it) }
                Hint(tr("When two experts score close, picks the one already in RAM. Same number of experts, fewer flash reads, faster decode. Changes the reply; 15% is the measured sweet spot."))
                if (settings.substitutePct >= 20) {
                    Text(
                        tr("Past 15% the model degrades faster than its replies show. Judge it on answers you can check."),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                    )
                }
                Hint(tr("Guess ahead (MTP / n-gram) and the MTP sidecar moved to the Models page: open a model and expand \"MTP draft\"."))
                ChoiceSetting(
                    tr("Route-ahead (layers)"),
                    Choices.ROUTE_AHEAD.map { it to if (it == 0) tr("off") else "$it" },
                    settings.routeAhead,
                    enabled = stream && settings.prefetchLayers == 0 && !settings.predictPrefetch && settings.spec == SpecSource.OFF
                ) { viewModel.updateRouteAhead(it) }
                Hint(tr("Commits each layer's routing that many layers early, so its reads start early and can never be wasted. Lossy: some slots route differently. Excludes the prefetchers and Guess ahead."))
                Spacer(Modifier.height(8.dp))
                Section(tr("Scored-choice API"))
                ChoiceSetting(
                    tr("Decision prefix cache"),
                    listOf(
                        DecisionPrefixCache.AUTO to tr("Auto"),
                        DecisionPrefixCache.ON to tr("On"),
                        DecisionPrefixCache.OFF to tr("Off")
                    ),
                    settings.decisionPrefixCache
                ) { viewModel.updateDecisionPrefixCache(it) }
                Hint(tr("Controls prefix-state reuse for the app's scored-choice / decision API only; it does not change normal chat generation. Auto chooses the best policy for the current backend."))
                Spacer(Modifier.height(8.dp))
                Section(tr("Quality controls"))
                SwitchRow(
                    tr("Renormalise expert weights after dropping"),
                    tr("Rescales the surviving expert weights after cold experts are skipped. Usually keep this enabled; disabling it can shift the layer output more."),
                    settings.dropRenorm,
                    enabled = stream && cacheOn && settings.dropColdPct > 0
                ) { viewModel.updateDropRenorm(it) }
                SwitchRow(
                    tr("Drop cold experts during prompt prefill"),
                    tr("Applies cache-aware expert dropping while the prompt is being processed. Off is recommended: the cache is cold during prefill, so many experts may be skipped and answer quality can fall."),
                    settings.dropInPrefill,
                    enabled = stream && cacheOn && settings.dropColdPct > 0
                ) { viewModel.updateDropInPrefill(it) }
                Spacer(Modifier.height(8.dp))
                Section(tr("Diagnostics / A-B only"))
                SwitchRow(
                    tr("Prediction probe"),
                    tr("Measures how well next-layer routing can be predicted. Adds a router GEMV per layer per token and can slow generation; do not use for normal speed benchmarks."),
                    settings.predictionProbe,
                    enabled = stream && settings.routeAhead == 0 && !settings.predictPrefetch && settings.prefetchLayers == 0
                ) { viewModel.updatePredictionProbe(it) }
                SwitchRow(
                    tr("Load all experts (A/B baseline)"),
                    tr("Reads every expert each token instead of only the selected experts. Very slow and I/O-heavy for large MoE models; use only for controlled comparisons."),
                    settings.loadAllExpertsForBenchmark,
                    enabled = stream
                ) { viewModel.updateLoadAllExpertsForBenchmark(it) }
            }
            Divider()
            } // speed / quality group
            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------------------ compute
            PerformanceGroup(tr("CPU compute & memory"), tr("Prompt evaluation, context and sampling")) {
            ChoiceSetting(tr("Decode compute threads"), Choices.THREADS.map { it to it.toString() }, settings.threadCount) {
                viewModel.updateThreadCount(it)
            }
            Hint(tr("CPU threads used for single-token decode. On Snapdragon 8 Gen 2, 4–5 is a sensible starting point; compare sustained speed and heat instead of assuming more threads are faster."))
            ChoiceSetting(
                tr("Prompt / batch threads"),
                Choices.THREADS.map { it to it.toString() },
                settings.batchThreadCount
            ) { viewModel.updateBatchThreadCount(it) }
            Hint(tr("Threads used for multi-token prompt evaluation. Increasing this may speed up long prompts but competes for memory bandwidth and can raise heat."))
            ChoiceSetting(
                tr("Batch width (ubatch)"),
                Choices.BATCH_WIDTH.map { it to tr("{0} tokens", it) },
                settings.batchWidth
            ) { viewModel.updateBatchWidth(it) }
            Hint(tr("Upper bound for tokens computed in one graph while processing prompts; the engine caps this at the context size. Smaller values reduce reserved compute RAM and can leave more memory for expert cache; larger values can prefill faster but may cause memory pressure. Decode speed is generally unaffected."))
            ChoiceSetting(
                tr("KV cache: keys"),
                KvCacheType.values().map { it to tr(it.label) },
                settings.cacheTypeK
            ) { viewModel.updateCacheTypeK(it) }
            ChoiceSetting(
                tr("KV cache: values"),
                KvCacheType.values().map { it to tr(it.label) },
                settings.cacheTypeV
            ) { viewModel.updateCacheTypeV(it) }
            Hint(tr("The attention cache grows with every token of context. q8_0 stores it in about half the memory of f16 for a loss below the noise floor, and that memory goes to the expert cache or a longer context. A quantized values cache needs flash attention: if the backend refuses it, values fall back to f16 on their own. With NPU prefill on, the engine keeps f16. Applies on the next model load."))
            ChoiceSetting(
                tr("Prompt checkpoints"),
                Choices.CTX_CHECKPOINTS.map { it to if (it == 0) tr("off") else "$it" },
                settings.ctxCheckpoints
            ) { viewModel.updateCtxCheckpoints(it) }
            Hint(tr("Hybrid and recurrent models (Qwen3.5, Nemotron-H, LFM...) cannot drop the end of a conversation from their memory, so a chat turn that edits earlier text re-reads the whole prompt. A checkpoint saves the prompt's state so only the part after it is re-read. Each one is a copy of that state, so fewer is lighter on RAM. Models that can cut anywhere never keep any."))
            Text(tr("Context (tokens)"), style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = contextText,
                onValueChange = { v -> contextText = ContextInput.sanitize(v) },
                singleLine = true,
                isError = !contextValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Hint(
                if (contextValid) tr("Type any number, there is no limit. Prompt plus reply the session can hold. Also memory: the KV cache is sized for it at open, so a very large value needs a lot of RAM and the model may fail to load; lower it if that happens.")
                else tr("Enter a whole number greater than 0. The last valid value ({0}) stays in use.", settings.contextLength)
            )
            SwitchRow(
                tr("Auto-compact long chats"),
                tr("When a chat nears the context size, the model summarizes its oldest messages and continues from the summary plus the newest messages, instead of silently forgetting the oldest ones. Your messages stay on screen; only what the model reads changes. Costs one short extra generation each time."),
                settings.autoCompactEnabled
            ) { viewModel.updateAutoCompactEnabled(it) }
            ChoiceSetting(
                tr("Compact at"),
                Choices.AUTO_COMPACT_PCT.map { it to tr("{0}% of the context", it) },
                settings.autoCompactThresholdPct,
                enabled = settings.autoCompactEnabled
            ) { viewModel.updateAutoCompactThresholdPct(it) }
            Hint(tr("Lower starts sooner and leaves more room for the reply; higher keeps more of the chat word for word. Chats in a context smaller than 1,536 tokens are never compacted. Applies to the next message."))
            Spacer(Modifier.height(8.dp))
            Text(tr("Temperature: {0}", "%.2f".format(java.util.Locale.US, temp)))
            Slider(value = temp, onValueChange = { temp = it }, onValueChangeFinished = { viewModel.updateTemperature(temp) }, valueRange = 0f..2f)
            Text(tr("Top P: {0}", "%.2f".format(java.util.Locale.US, topP)))
            Slider(value = topP, onValueChange = { topP = it }, onValueChangeFinished = { viewModel.updateTopP(topP) }, valueRange = 0.05f..1f)
            Text(tr("Top K: {0}", topK.toInt()))
            Slider(value = topK, onValueChange = { topK = it }, onValueChangeFinished = { viewModel.updateTopK(topK.toInt()) }, valueRange = 0f..100f)
            Text(tr("Min P: {0}", if (minP <= 0f) tr("off") else "%.2f".format(java.util.Locale.US, minP)))
            Slider(value = minP, onValueChange = { minP = it }, onValueChangeFinished = { viewModel.updateMinP(minP) }, valueRange = 0f..0.5f)
            Text(tr("Repetition penalty: {0}", if (repeatPenalty == 1f) tr("off") else "%.2f".format(java.util.Locale.US, repeatPenalty)))
            Slider(value = repeatPenalty, onValueChange = { repeatPenalty = (Math.round(it * 20f) / 20f) }, onValueChangeFinished = { viewModel.updateRepeatPenalty(repeatPenalty) }, valueRange = 1f..1.5f)
            Text(tr("Repeat window: {0}", if (repeatLastN <= 0f) tr("off") else "${repeatLastN.toInt()} tokens"))
            Slider(value = repeatLastN, onValueChange = { repeatLastN = (Math.round(it / 16f) * 16f).coerceIn(0f, 1024f) }, onValueChangeFinished = { viewModel.updateRepeatLastN(repeatLastN.toInt()) }, valueRange = 0f..1024f)
            Text(tr("Frequency penalty: {0}", "%.2f".format(java.util.Locale.US, frequencyPenalty)))
            Slider(value = frequencyPenalty, onValueChange = { frequencyPenalty = (Math.round(it * 20f) / 20f) }, onValueChangeFinished = { viewModel.updateFrequencyPenalty(frequencyPenalty) }, valueRange = 0f..1.5f)
            Text(tr("Presence penalty: {0}", "%.2f".format(java.util.Locale.US, presencePenalty)))
            Slider(value = presencePenalty, onValueChange = { presencePenalty = (Math.round(it * 20f) / 20f) }, onValueChangeFinished = { viewModel.updatePresencePenalty(presencePenalty) }, valueRange = 0f..1.5f)
            OutlinedTextField(
                value = seedText,
                onValueChange = { input -> seedText = input.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') } },
                label = { Text(tr("Sampling seed (-1 = random)")) },
                supportingText = { Text(tr("Use -1 for a fresh random seed per run, or a non-negative number to make runs repeatable. Sampling values are applied to the next request without reloading the model.")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth()
            )
            Hint(tr("Temperature, Top-P, Top-K and seed are applied per request and do not require a model reload. Temperature 0 = greedy decoding (always the most likely token). Min P drops very unlikely tokens relative to the best one (needs temperature above 0). Repetition penalty above 1 makes tokens from the last \"Repeat window\" tokens less likely (1 = off; 1.05 to 1.15 is typical, higher can make answers odd). Frequency penalty grows with each repeat, presence penalty applies once. Penalties also work at temperature 0, but are switched off while Guess ahead is on, because it relies on plain greedy decoding."))
            Divider()
            } // compute group

        } else {
            Divider()
        }

        SettingsFoldout(
            title = tr("Tools, web search, pictures & videos"),
            subtitle = tr("Multimodal input, online search and tool permissions"),
            expanded = toolsMediaExpanded,
            onToggle = { toolsMediaExpanded = !toolsMediaExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = toolsMediaExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsFoldout(
            title = tr("Pictures & video"), subtitle = tr("Image dimensions and sampled video frames"), expanded = mediaExpanded,
            onToggle = { mediaExpanded = !mediaExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = mediaExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ pictures/video
            ChoiceSetting(
                tr("Picture max dimension"),
                Choices.IMAGE_DIM.map { it to tr("{0} px", it) },
                settings.imageMaxDim
            ) { viewModel.updateImageMaxDim(it) }
            Hint(tr("Higher dimensions preserve more detail but increase vision token and memory use. The active model must have an mmproj."))
            ChoiceSetting(
                tr("Video sampled frames"),
                Choices.VIDEO_FRAMES.map { it to it.toString() },
                settings.videoFrames
            ) { viewModel.updateVideoFrames(it) }
            Hint(tr("Frames are sampled evenly from the attached video. Images/video stay disabled in the chat until a vision projector is loaded."))
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = tr("Tools"), subtitle = tr("Permissions and tool-result behaviour"), expanded = toolsExpanded,
            onToggle = { toolsExpanded = !toolsExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = toolsExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ tool access
            Hint(tr("What the AI may use on its own when it needs it. Anything that changes or deletes a file still asks you first. The same switches are in the chat, under the wrench button."))
            SwitchRow(
                tr("Files"),
                tr("Create folders and files, read, search, edit, rename, delete, zip. Everything stays in this chat's files."),
                settings.toolsFilesEnabled
            ) { viewModel.updateToolsFilesEnabled(it) }
            SwitchRow(
                tr("Code"),
                tr("Search and analyse source files, and run JavaScript in an offline sandbox to compute or test."),
                settings.toolsCodeEnabled
            ) { viewModel.updateToolsCodeEnabled(it) }
            SwitchRow(
                tr("Utilities"),
                tr("Offline helpers: date and time, calculator, unit converter, random numbers, battery / storage / memory of this phone."),
                settings.toolsUtilitiesEnabled
            ) { viewModel.updateToolsUtilitiesEnabled(it) }
            SwitchRow(
                tr("Native UI components"),
                tr("Lets the AI answer with compact in-chat visuals when your request calls for one (compare, pros and cons, timeline, steps, key numbers, slides): comparison tables, navigable slide cards, lists, metrics, badges, progress bars, timelines, expandable sections, selectable cards and action buttons. Rendered directly with Jetpack Compose from a small JSON block — no HTML, CSS, JavaScript or WebView. Off by default: it adds about 450 tokens to the system prompt. Also under the wrench button in the chat."),
                settings.interactiveUiEnabled
            ) { viewModel.updateInteractiveUiEnabled(it) }
            SwitchRow(
                tr("External tools"),
                tr("Lets the AI call the HTTP tools and MCP servers you add below. Each call can ask you first. Off by default. Also under the wrench button in the chat."),
                settings.toolsExternalEnabled
            ) { viewModel.updateToolsExternalEnabled(it) }
            ExternalToolsSection(settings.externalToolsJson) { viewModel.updateExternalToolsJson(it) }
            Hint(tr("This setting uses native Android UI components only. It does not generate or execute HTML/CSS/JavaScript. Interactive Artifacts are a separate feature for standalone HTML, SVG or Mermaid previews."))
            ChoiceSetting(
                tr("Longest tool result"),
                listOf(
                    1500 to tr("1,500 characters (fastest)"),
                    2500 to tr("2,500 characters"),
                    3500 to tr("3,500 characters (default)"),
                    6000 to tr("6,000 characters (slowest)")
                ),
                selected = settings.toolResultChars
            ) { viewModel.updateToolResultChars(it) }
            Hint(
                tr("The model reads everything a tool returns before it can continue, at the speed of prompt processing (tens of tokens per second on a phone). A shorter limit makes each tool round much faster. When a result is cut, the model is told how much was left so it can ask for the rest.")
            )
            SwitchRow(
                tr("Think after tool results"),
                tr("Off (default): once the model has a tool result it answers directly, which saves minutes on a slow device. On: it reasons again after every result."),
                settings.thinkAfterTools
            ) { viewModel.updateThinkAfterTools(it) }
            Hint(tr("Web search, image search, and web-page reading are controlled by the globe button in the chat. These tools are off by default."))
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = tr("Web search"), subtitle = tr("Search provider and automatic-search behaviour"), expanded = searchExpanded,
            onToggle = { searchExpanded = !searchExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = searchExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ web search
            ChoiceSetting(
                tr("When to search"),
                listOf(
                    WebSearchMode.ALWAYS to tr("Every message (most reliable)"),
                    WebSearchMode.AUTO to tr("Only when the model asks for it")
                ),
                selected = settings.webSearchMode
            ) { viewModel.updateWebSearchMode(it) }
            Hint(tr("Web search itself is switched on and off with the globe button in the chat."))
            SwitchRow(
                tr("Allow image search"),
                tr("Lets the model use image_search while web search is enabled. Turn off to keep image-search calls out of the model's available tools."),
                settings.imageSearchEnabled
            ) { viewModel.updateImageSearchEnabled(it) }
            SwitchRow(
                tr("Search images after web search"),
                tr("After the app finishes a web search, automatically fetch a few related images and include their preview cards in the conversation. Adds network time and tool-result tokens; requires Allow image search."),
                settings.autoImageSearchAfterWebSearch,
                enabled = settings.imageSearchEnabled
            ) { viewModel.updateAutoImageSearchAfterWebSearch(it) }
            Hint(
                if (settings.webSearchMode == WebSearchMode.ALWAYS)
                    tr("Every message you send is searched first; the results are given to the model together with your message. Works with any model.")
                else
                    tr("The model is told it may search and decides by itself. Small models often never do; switch to \"Every message\" if nothing happens.")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = braveKey,
                onValueChange = { braveKey = it },
                label = { Text(tr("Brave Search API key (optional)")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searxUrl,
                onValueChange = { searxUrl = it },
                label = { Text(tr("SearXNG URL (optional)")) },
                placeholder = { Text("https://searx.example.org") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Hint("No key needed: without a Brave key or SearXNG address the app uses DuckDuckGo, then Wikipedia. A SearXNG address must be https:// and allow the JSON format.")
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = tr("Diagnostics & workspace"), subtitle = tr("Metrics, workspace files, capabilities and local API server"), expanded = workspaceExpanded,
            onToggle = { workspaceExpanded = !workspaceExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = workspaceExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ diagnostics
            Section(tr("Diagnostics"))
            SwitchRow(
                tr("Metrics CSV"),
                tr("Optional per-token timings, faults, cache and memory snapshots. Adds profiling and file-I/O work, so leave off for normal use and enable when diagnosing a run. Takes effect the next time a model is loaded."),
                settings.metricsCsv
            ) { viewModel.updateMetricsCsv(it) }
            OutlinedButton(onClick = { shareLatestCsv(context) }) { Text(tr("Share latest metrics CSV")) }
            Divider()

            // ------------------------------------------------------------ workspace
            Section(tr("Workspace"))
            Text(tr("Files attached to chats and files created by the AI live in the private workspace."), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenWorkspace) { Text(tr("Workspace files")) }
            Divider()

            Section(tr("Capabilities"))
            Hint(tr("Implemented: file attachments and reading, workspace/artifacts, file/code/utility/web tools, image/video attachment plumbing with mmproj support, thinking and web toggles. Voice input is not part of this project."))
            Divider()

            // ------------------------------------------------------------ local API server (last, collapsed by default)
            ApiServerSection(settings = settings, readyInfo = readyInfo, viewModel = viewModel)
            }
        }
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(120.dp)) // clear of the floating navigation bar
    }

    editingPalette?.let { palette ->
        PaletteEditorDialog(
            initial = palette,
            onSave = { viewModel.saveCustomPalette(it); editingPalette = null },
            onDelete = if (editingIsNew) null else ({ viewModel.deleteCustomPalette(palette.id); editingPalette = null }),
            onDismiss = { editingPalette = null }
        )
    }
}

@Composable
private fun PerformanceGroup(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        base = MaterialTheme.colorScheme.surfaceContainerLow,
        outline = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                content()
            }
        )
    }
}

@Composable
private fun AutoSettingsEntry(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    tr("Auto settings"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    tr("Best settings for your phone and model: Fastest, Balanced or Most accurate, with saves you can switch between"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = tr("Open Auto settings"),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SettingsFoldout(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val styled = AppTheme.visualStyle != AppVisualStyle.SOLID
    Surface(
        modifier = Modifier.fillMaxWidth()
            .then(if (styled) appSurfaceModifier(shape, MaterialTheme.colorScheme.surfaceContainerLow) else Modifier)
            .clickable(onClick = onToggle),
        shape = shape,
        color = if (styled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (styled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) tr("Collapse performance settings") else tr("Expand performance settings"),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun shareLatestCsv(context: android.content.Context) {
    val dir = File(context.getExternalFilesDir(null), "metrics")
    // Newest NON-EMPTY file: a model load opens a fresh session file that stays empty until something is generated, and
    // picking that one shared a 0-byte CSV while the useful one sat right behind it.
    val latest = dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") && f.length() > 0L }?.maxByOrNull { it.lastModified() }
    if (latest == null) {
        android.widget.Toast.makeText(context, tr("No metrics CSV yet: load a model with Metrics CSV on and chat first."), android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", latest)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, tr("Share metrics CSV")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
internal fun Section(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Divider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ExperimentalHeader(expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("EXPERIMENTAL"), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(if (expanded) tr("hide") else tr("show"), color = MaterialTheme.colorScheme.onErrorContainer)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun <T> ChoiceSetting(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label, style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
                }
            }
        }
    }
}

/**
 * Interface language: "Same as the phone" or one of the languages the app has a file for ([I18n.languages]), named in
 * its own script so it can be found whatever language is showing. The activity is recreated so every screen, and the
 * layout direction, follow the choice.
 */
@Composable
private fun LanguageChooser() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picked = remember { mutableStateOf(I18n.savedLanguage(context)) }
    val options = listOf("" to tr("Same as the phone")) + I18n.languages.map { it.tag to it.name }
    ChoiceSetting(tr("Language"), options, picked.value) { tag ->
        if (tag != picked.value) {
            picked.value = tag
            I18n.setLanguage(context, tag)
            var c: android.content.Context = context
            while (c is android.content.ContextWrapper) {
                if (c is android.app.Activity) {
                    c.recreate()
                    break
                }
                c = c.baseContext
            }
        }
    }
}
