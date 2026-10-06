package com.bigmoe.onedge.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bigmoe.onedge.core.DenseWeights
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.CACHE_AUTO_MB
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.WebSearchMode
import com.bigmoe.onedge.data.local.datastore.loadConfigDiffers
import com.bigmoe.onedge.ui.theme.PalettePresets
import com.bigmoe.onedge.ui.theme.PaletteSpec
import com.bigmoe.onedge.ui.theme.PaletteStore
import kotlinx.coroutines.delay
import java.io.File

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
    val THREADS = listOf(2, 4, 6, 8)
    val NPREDICT = listOf(16, 32, 48, 64, 128, 256, 512, 1024, 2048, 4096, -1)
    val DRAFT = listOf(1, 2, 3, 4, 5)
    val MTP_P_MIN = listOf(0, 40, 60, 80)
    val IMAGE_DIM = listOf(256, 384, 512, 768, 1024, 1344)
    val VIDEO_FRAMES = listOf(1, 2, 4, 6, 8, 12, 16)
}

private fun denseLabel(d: DenseWeights) = when (d) {
    DenseWeights.MMAP -> "Mmap (baseline)"
    DenseWeights.WARMED -> "Warm at load"
    DenseWeights.ANONYMOUS -> "Anon (O_DIRECT)"
    DenseWeights.PINNED -> "Pinned (dma-buf)"
}

private fun denseBlurb(d: DenseWeights) = when (d) {
    DenseWeights.MMAP -> "Left to the kernel, faulted in as touched. The baseline."
    DenseWeights.WARMED -> "Paged in once at load, so the first tokens do not fault. Best when the model fits in RAM."
    DenseWeights.ANONYMOUS -> "Read into the app's own memory. Reclaim compresses them instead of dropping them, so a refault costs no flash read. The default."
    DenseWeights.PINNED -> "As Anon, but reclaim cannot touch it at all, not even to compress. Pays off over a long conversation."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenModels: () -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loaded by viewModel.settingsState.collectAsState()
    val engine by viewModel.engineState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) }
            )
        },
        modifier = modifier
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
    var cacheFloorText by remember { mutableStateOf(settings.cacheFloorMb.toString()) }
    var contextText by remember { mutableStateOf(settings.contextLength.toString()) }
    val contextValid = ContextInput.parse(contextText) != null
    var experimental by remember { mutableStateOf(false) }
    var editingPalette by remember { mutableStateOf<PaletteSpec?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

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

    Column(modifier = modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        // ------------------------------------------------------------ model
        Section("Model")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when (engine) {
                    is EngineState.Ready -> "Loaded: ${engine.info.name}"
                    is EngineState.Loading -> "Loading…"
                    is EngineState.Failed -> "Last load failed"
                    EngineState.Unloaded -> "No model loaded"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onOpenModels) { Text("Manage models") }
        }
        if (readyInfo != null) {
            Hint(
                if (readyInfo.config.mmapBaseline) "Regular (non-MoE) model: loaded the ordinary way, expert streaming is off automatically"
                else "Expert streaming ON • I/O–compute overlap: " + if (readyInfo.overlapActive) "active" else "not active"
            )
        }
        if (reloadNeeded) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Engine settings changed since the model was loaded. The engine fixes them when it opens the model.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { viewModel.reloadModel() }) { Text("Reload model now") }
                }
            }
        }
        Divider()

        // ------------------------------------------------------------ chat (per message)
        Section("Chat")
        Text("System prompt", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Hint("Applies to the next message. Changing it makes the engine re-read the conversation once.")
        Spacer(Modifier.height(12.dp))
        ChoiceSetting("Tokens to generate", Choices.NPREDICT.map { it to if (it < 0) "Unlimited (until the context is full)" else it.toString() },
            selected = if (settings.maxTokens <= 0) -1 else settings.maxTokens) { viewModel.updateMaxTokens(it) }
        Divider()

        // ------------------------------------------------------------ pictures/video
        Section("Pictures & video")
        ChoiceSetting(
            "Picture max dimension",
            Choices.IMAGE_DIM.map { it to "$it px" },
            settings.imageMaxDim
        ) { viewModel.updateImageMaxDim(it) }
        Hint("Higher dimensions preserve more detail but increase vision token and memory use. The active model must have an mmproj.")
        ChoiceSetting(
            "Video sampled frames",
            Choices.VIDEO_FRAMES.map { it to it.toString() },
            settings.videoFrames
        ) { viewModel.updateVideoFrames(it) }
        Hint("Frames are sampled evenly from the attached video. Images/video stay disabled in the chat until a vision projector is loaded.")
        Divider()

        // ------------------------------------------------------------ tool access
        Section("Tool access")
        Hint("What the AI may use on its own when it needs it. Anything that changes or deletes a file still asks you first. The same switches are in the chat, under the wrench button.")
        SwitchRow(
            "Files",
            "Create folders and files, read, search, edit, rename, delete, zip. Everything stays in this chat's files.",
            settings.toolsFilesEnabled
        ) { viewModel.updateToolsFilesEnabled(it) }
        SwitchRow(
            "Code",
            "Search and analyse source files, and run JavaScript in an offline sandbox to compute or test.",
            settings.toolsCodeEnabled
        ) { viewModel.updateToolsCodeEnabled(it) }
        SwitchRow(
            "Utilities",
            "Offline helpers: date and time, calculator, unit converter, random numbers, battery / storage / memory of this phone.",
            settings.toolsUtilitiesEnabled
        ) { viewModel.updateToolsUtilitiesEnabled(it) }
        Hint("Web tools (search, read a page) are controlled by the globe button in the chat.")
        Divider()

        // ------------------------------------------------------------ web search
        Section("Web search")
        ChoiceSetting(
            "When to search",
            listOf(
                WebSearchMode.ALWAYS to "Every message (most reliable)",
                WebSearchMode.AUTO to "Only when the model asks for it"
            ),
            selected = settings.webSearchMode
        ) { viewModel.updateWebSearchMode(it) }
        Hint("Web search is switched on and off with the globe button in the chat.")
        Hint(
            if (settings.webSearchMode == WebSearchMode.ALWAYS)
                "Every message you send is searched first; the results are given to the model together with your message. Works with any model."
            else
                "The model is told it may search and decides by itself. Small models often never do; switch to \"Every message\" if nothing happens."
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = braveKey,
            onValueChange = { braveKey = it },
            label = { Text("Brave Search API key (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = searxUrl,
            onValueChange = { searxUrl = it },
            label = { Text("SearXNG URL (optional)") },
            placeholder = { Text("https://searx.example.org") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Hint("No key needed: without a Brave key or SearXNG address the app uses DuckDuckGo, then Wikipedia. A SearXNG address must be https:// and allow the JSON format.")
        Divider()

        // ------------------------------------------------------------ streaming (engine)
        Section("Streaming")
        Hint("Engine settings: fixed when the model is opened. After changing them, press Reload.")
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            "NPU prefill (Hexagon)",
            if (viewModel.npuPrefillAvailable) {
                "Enable Snapdragon Hexagon/NPU prompt prefill. Decode remains on CPU. Reload the model after changing this."
            } else {
                "Unavailable in this APK: it was built without the optional Hexagon/NPU backend. The switch becomes available in a Hexagon-enabled build."
            },
            checked = settings.npuPrefill,
            enabled = viewModel.npuPrefillAvailable
        ) { viewModel.updateNpuPrefill(it) }
        if (viewModel.npuPrefillAvailable && settings.npuPrefill) {
            ChoiceSetting(
                "NPU prefill loader threads",
                listOf(2 to "2", 4 to "4", 6 to "6", 8 to "8", 12 to "12", 16 to "16"),
                settings.npuLoaders
            ) { viewModel.updateNpuLoaders(it) }
            Hint("Upstream 0.28 defaults to 8 loaders. This setting is applied when the model is reloaded.")
        }
        Spacer(Modifier.height(8.dp))
        Hint("Expert streaming is switched on or off automatically: MoE models stream their experts from flash, regular models load the ordinary way.")
        Spacer(Modifier.height(12.dp))
        ChoiceSetting(
            "Expert cache (MiB)",
            Choices.CACHE.map { it to (if (it == CACHE_AUTO_MB) "Auto" else if (it == 0) "off" else "$it MiB") },
            selected = settings.cacheMb, enabled = stream
        ) { viewModel.updateCacheMb(it) }
        Hint("A resident expert costs no read, so a bigger cache means less waiting on flash, paid for in RAM. Auto sizes it once at load. The smallest rungs sit below the engine's floor and only churn.")
        ChoiceSetting(
            "Auto cache ceiling (MiB)",
            Choices.CACHE_CEIL.map { it to if (it == 0) "no cap" else "$it MiB" },
            selected = settings.cacheCeilMb, enabled = stream && settings.cacheMb == CACHE_AUTO_MB
        ) { viewModel.updateCacheCeilMb(it) }
        Hint("Caps what Auto may claim. The system counts our own mapped weights as free, so uncapped it can ask for more than exists.")
        if (settings.cacheMb == CACHE_AUTO_MB) {
            OutlinedTextField(
                value = cacheFloorText,
                onValueChange = { cacheFloorText = it.filter(Char::isDigit) },
                label = { Text("Auto: keep this much RAM free (MB)") },
                singleLine = true,
                enabled = stream,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
        ChoiceSetting("Parallel I/O lanes", Choices.IO.map { it to it.toString() }, settings.ioThreads, enabled = stream) {
            viewModel.updateIoThreads(it)
        }
        Hint("Expert reads in flight at once. Helps only until the flash saturates.")
        SwitchRow(
            "Direct I/O (O_DIRECT)",
            "Bypass the page cache, so the system keeps no second copy of what the cache already holds. Falls back where unsupported.",
            settings.oDirect, enabled = stream
        ) { viewModel.updateODirect(it) }
        SwitchRow(
            "I/O and compute overlap",
            "Issue the next reads while the current layer computes, so flash latency hides behind the work." +
                if (readyInfo != null && !readyInfo.overlapActive && settings.overlap && stream) " (Not active in the loaded model.)" else "",
            settings.overlap, enabled = stream
        ) { viewModel.updateOverlap(it) }
        ChoiceSetting(
            "Dense weights",
            DenseWeights.values().map { it to denseLabel(it) },
            settings.denseWeights, enabled = stream
        ) { viewModel.updateDenseWeights(it) }
        Hint(denseBlurb(settings.denseWeights))
        SwitchRow(
            "Stream row-gathered tables",
            "A dense table the model only reads a few ROWS from per token (the token embedding) does not need to be in RAM: only the rows are read, from flash. Lossless - the output is identical. On a model where no table qualifies this does nothing.",
            settings.rowStream, enabled = stream
        ) { viewModel.updateRowStream(it) }
        SwitchRow(
            "Release the model mapping",
            "Once every weight has been copied into the app's own memory, the model file does not need to stay mapped. Lossless - the output is identical. Needs Dense weights on Anon or Pinned.",
            settings.releaseMmap,
            enabled = stream && (settings.denseWeights == DenseWeights.ANONYMOUS || settings.denseWeights == DenseWeights.PINNED)
        ) { viewModel.updateReleaseMmap(it) }
        Divider()

        // ------------------------------------------------------------ speed / quality
        Section("Speed / quality")
        ChoiceSetting(
            "Drop cold experts (% of even share)",
            Choices.DROP_COLD.map {
                it to when (it) {
                    0 -> "off"
                    50 -> "50% (barely bites)"
                    75 -> "75% (recommended)"
                    100 -> "100% (fastest, roughest)"
                    else -> "$it%"
                }
            },
            settings.dropColdPct, enabled = cacheOn
        ) { viewModel.updateDropColdPct(it) }
        Hint("Skips a routed expert only when it is a cache miss and the router barely wanted it, so quality is spent only where it buys a read. A resident expert always runs and the top one is never dropped. Changes the reply, and not the same way twice: it depends on what the cache held.")
        val topkNow = readyInfo?.nExpertUsed ?: 0
        if (settings.dropColdPct > 0 && topkNow in 1..4) {
            Text(
                "This model routes very few experts per token, so the same share covers much more of the reply. Check the answers, or turn this off.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
            )
        }
        ChoiceSetting("Active experts (top-k)", Choices.TOPK.map { it to if (it == 0) "model default" else "$it" }, settings.nExpertUsed) {
            viewModel.updateNExpertUsed(it)
        }
        Hint("Consult fewer experts per token than the model asks for. Cuts compute and reads together, and changes the reply.")

        Spacer(Modifier.height(8.dp))
        ExperimentalHeader(expanded = experimental) { experimental = !experimental }
        if (experimental) {
            Spacer(Modifier.height(8.dp))
            ChoiceSetting(
                "Temporal prefetch (layers)",
                Choices.PREFETCH.map { it to if (it == 0) "off" else "$it" },
                settings.prefetchLayers,
                enabled = stream && cacheOn && !settings.predictPrefetch && settings.routeAhead == 0
            ) { viewModel.updatePrefetchLayers(it) }
            Hint("Bets a layer reuses the previous token's experts and reads them on idle lanes. Needs the cache.")
            SwitchRow(
                "Predictive prefetch",
                "Runs the next layer's own router a layer early and prefetches what it names. More accurate than the bet above, and replaces it. Needs the cache.",
                settings.predictPrefetch,
                enabled = stream && cacheOn && settings.prefetchLayers == 0 && settings.routeAhead == 0
            ) { viewModel.updatePredictPrefetch(it) }
            if (settings.predictPrefetch) {
                ChoiceSetting(
                    "Predicted misses to read ahead",
                    Choices.PREDICT_SPEC.map { it to if (it == 0) "retention only" else "$it" },
                    settings.predictSpecMax, enabled = stream && cacheOn
                ) { viewModel.updatePredictSpecMax(it) }
                Hint("Retention only reads nothing and just protects what the prediction names, which is the safe setting.")
            }
            ChoiceSetting(
                "Prefer cached experts (% of score range)",
                Choices.SUBSTITUTE.map { it to if (it == 0) "off" else "$it%" },
                settings.substitutePct, enabled = stream && cacheOn
            ) { viewModel.updateSubstitutePct(it) }
            Hint("When two experts score close, picks the one already in RAM. Same number of experts, fewer flash reads, faster decode. Changes the reply; 15% is the measured sweet spot.")
            if (settings.substitutePct >= 20) {
                Text(
                    "Past 15% the model degrades faster than its replies show. Judge it on answers you can check.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            }
            ChoiceSetting(
                "Guess ahead",
                listOf(SpecSource.OFF to "Off", SpecSource.MTP to "Model's own head (MTP)", SpecSource.NGRAM to "Repeated text (n-gram)"),
                settings.spec, enabled = settings.routeAhead == 0
            ) { viewModel.updateSpec(it) }
            Hint("Draft the next few tokens, verify the group in one decode, keep only what the model would have produced. Lossless. The MTP head is accurate but only some models carry it (others fail to load); the n-gram lookup works on any model but only fires on repeated text.")
            if (settings.spec != SpecSource.OFF) {
                ChoiceSetting("Tokens guessed per pass", Choices.DRAFT.map { it to it.toString() }, settings.draftMax) {
                    viewModel.updateDraftMax(it)
                }
            }
            if (settings.spec == SpecSource.MTP) {
                ChoiceSetting(
                    "Guess only when confident",
                    Choices.MTP_P_MIN.map { it to if (it == 0) "always draft" else "above $it%" },
                    settings.mtpPMinPct
                ) { viewModel.updateMtpPMinPct(it) }
            }
            ChoiceSetting(
                "Route-ahead (layers)",
                Choices.ROUTE_AHEAD.map { it to if (it == 0) "off" else "$it" },
                settings.routeAhead,
                enabled = stream && settings.prefetchLayers == 0 && !settings.predictPrefetch && settings.spec == SpecSource.OFF
            ) { viewModel.updateRouteAhead(it) }
            Hint("Commits each layer's routing that many layers early, so its reads start early and can never be wasted. Lossy: some slots route differently. Excludes the prefetchers and Guess ahead.")
        }
        Divider()

        // ------------------------------------------------------------ compute
        Section("Compute")
        ChoiceSetting("Compute threads", Choices.THREADS.map { it to it.toString() }, settings.threadCount) {
            viewModel.updateThreadCount(it)
        }
        Text("Context (tokens)", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = contextText,
            onValueChange = { v -> contextText = ContextInput.sanitize(v) },
            singleLine = true,
            isError = !contextValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Hint(
            if (contextValid) "Type any number, there is no limit. Prompt plus reply the session can hold. Also memory: the KV cache is sized for it at open, so a very large value needs a lot of RAM and the model may fail to load; lower it if that happens."
            else "Enter a whole number greater than 0. The last valid value (${settings.contextLength}) stays in use."
        )
        Spacer(Modifier.height(8.dp))
        Text("Temperature: ${"%.2f".format(java.util.Locale.US, temp)}")
        Slider(value = temp, onValueChange = { temp = it }, onValueChangeFinished = { viewModel.updateTemperature(temp) }, valueRange = 0f..2f)
        Text("Top P: ${"%.2f".format(java.util.Locale.US, topP)}")
        Slider(value = topP, onValueChange = { topP = it }, onValueChangeFinished = { viewModel.updateTopP(topP) }, valueRange = 0.05f..1f)
        Text("Top K: ${topK.toInt()}")
        Slider(value = topK, onValueChange = { topK = it }, onValueChangeFinished = { viewModel.updateTopK(topK.toInt()) }, valueRange = 0f..100f)
        Hint("Sampling is fixed when the model is opened. Temperature 0 = always the most likely token (what the original app does). The engine has no repetition-penalty.")
        Divider()

        // ------------------------------------------------------------ diagnostics
        Section("Diagnostics")
        SwitchRow(
            "Metrics CSV",
            "One CSV per session: per-token timings, faults, cache budget and where memory sat. Takes effect the next time a model is loaded.",
            settings.metricsCsv
        ) { viewModel.updateMetricsCsv(it) }
        OutlinedButton(onClick = { shareLatestCsv(context) }) { Text("Share latest metrics CSV") }
        Divider()

        // ------------------------------------------------------------ appearance
        Section("Workspace")
        Text("Files attached to chats and files created by the AI live in the private workspace.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenWorkspace) { Text("Workspace files") }
        Divider()

        Section("Appearance")
        val custom = PaletteStore.parse(settings.customPalettesJson)
        Text("Palettes", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        val selectedCustom = custom.firstOrNull { it.id == settings.themeId }
        PaletteDropdown(
            selectedId = settings.themeId,
            presets = PalettePresets.all,
            custom = custom,
            onSelect = { viewModel.selectTheme(it) },
            onNewCustom = {
                val base = PaletteStore.resolve(settings.themeId, custom, false)
                editingPalette = base.copy(id = PaletteStore.newId(custom), name = "My palette", custom = true)
                editingIsNew = true
            },
            onEditSelected = if (selectedCustom != null) ({ editingPalette = selectedCustom; editingIsNew = false }) else null
        )
        Hint("A custom palette starts as a copy of the current one. All nine colours are editable and it is saved on the device.")
        Divider()

        Section("Capabilities")
        Hint("Implemented: file attachments and reading, workspace/artifacts, file/code/utility/web tools, image/video attachment plumbing with mmproj support, thinking and web toggles. Voice input is not part of this project.")
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

private fun shareLatestCsv(context: android.content.Context) {
    val dir = File(context.getExternalFilesDir(null), "metrics")
    val latest = dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }
    if (latest == null) {
        android.widget.Toast.makeText(context, "No metrics CSV yet: load a model with Metrics CSV on and chat first.", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", latest)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share metrics CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Section(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Divider() {
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
                "EXPERIMENTAL", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(if (expanded) "hide" else "show", color = MaterialTheme.colorScheme.onErrorContainer)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SwitchRow(
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
private fun <T> ChoiceSetting(
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
