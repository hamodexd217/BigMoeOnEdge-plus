package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.ui.theme.AppCard
import org.json.JSONArray
import org.json.JSONObject

/** Safe, declarative UI schema rendered with Compose. It never evaluates model-provided code. */
data class NativeUiMetric(val label: String, val value: String, val detail: String)
data class NativeUiCard(val title: String, val subtitle: String, val body: String)
data class NativeUiBar(val label: String, val value: Double, val valueLabel: String)
data class NativeUiAction(val label: String, val prompt: String)
data class NativeUiListItem(val title: String, val detail: String)
data class NativeUiList(val title: String, val style: String, val items: List<NativeUiListItem>)
data class NativeUiComparisonRow(val label: String, val left: String, val right: String, val highlight: Boolean)
data class NativeUiComparison(val title: String, val leftTitle: String, val rightTitle: String, val rows: List<NativeUiComparisonRow>)
data class NativeUiSlide(val title: String, val subtitle: String, val body: String, val metricLabel: String, val metricValue: String)
data class NativeUiTimelineItem(val time: String, val title: String, val detail: String, val status: String)
data class NativeUiSection(val title: String, val body: String)
data class NativeUiBadge(val label: String, val tone: String)

data class NativeUiSpec(
    val title: String,
    val description: String,
    val metrics: List<NativeUiMetric>,
    val cards: List<NativeUiCard>,
    val bars: List<NativeUiBar>,
    val actions: List<NativeUiAction>,
    val badges: List<NativeUiBadge>,
    val lists: List<NativeUiList>,
    val comparison: NativeUiComparison?,
    val slides: List<NativeUiSlide>,
    val timeline: List<NativeUiTimelineItem>,
    val sections: List<NativeUiSection>
)

object NativeUiParser {
    private const val MAX_JSON_CHARS = 24_000
    private fun clean(o: JSONObject, key: String, max: Int = 240): String = o.optString(key, "").trim().take(max)
    private fun array(root: JSONObject, name: String): JSONArray = root.optJSONArray(name) ?: JSONArray()

    private val KNOWN_TYPES = setOf("dashboard", "cards", "comparison", "chart", "choices", "list", "slides", "timeline", "rich")

    /** Fence languages that mean "this is a native UI block" (small models often misspell the tag). */
    private val UI_LANGUAGES = setOf("bmoe-ui", "bmoe_ui", "bmoeui", "native-ui", "native_ui", "nativeui")

    fun isUiLanguage(language: String): Boolean = language.trim().lowercase() in UI_LANGUAGES

    /**
     * True when a plain ```json block is really a native UI spec: it has to parse AND declare one of the known
     * `type` values, so ordinary JSON the user asked for is never swallowed.
     */
    fun looksLikeSpec(source: String): Boolean = parse(source, requireType = true) != null

    /**
     * Cuts everything around the outermost JSON object (prose, stray fences) and closes what a small model left
     * open: an unfinished string, a trailing comma, missing `]` / `}`. Returns null when there is no object at all.
     */
    internal fun repairJson(source: String): String? {
        val start = source.indexOf('{')
        if (start < 0) return null
        val out = StringBuilder()
        val closers = ArrayList<Char>()
        var inString = false
        var escaped = false
        var i = start
        while (i < source.length) {
            val c = source[i]
            if (inString) {
                out.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> { inString = true; out.append(c) }
                    '{' -> { closers.add('}'); out.append(c) }
                    '[' -> { closers.add(']'); out.append(c) }
                    '}', ']' -> {
                        // drop a trailing comma before the closer
                        var j = out.length - 1
                        while (j >= 0 && out[j].isWhitespace()) j--
                        if (j >= 0 && out[j] == ',') out.deleteCharAt(j)
                        if (closers.isNotEmpty()) closers.removeAt(closers.size - 1)
                        out.append(c)
                        if (closers.isEmpty()) return out.toString() // the root object is complete
                    }
                    else -> out.append(c)
                }
            }
            i++
        }
        // Ran out of text: the model was cut off or forgot the end.
        if (inString) out.append('"')
        var j = out.length - 1
        while (j >= 0 && out[j].isWhitespace()) j--
        if (j >= 0 && (out[j] == ',' || out[j] == ':')) {
            if (out[j] == ':') out.append("null")
            else out.deleteCharAt(j)
        }
        while (closers.isNotEmpty()) out.append(closers.removeAt(closers.size - 1))
        return out.toString()
    }

    private fun alias(o: JSONObject, vararg keys: String, max: Int): String {
        for (k in keys) {
            val v = clean(o, k, max)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    /**
     * @param requireType when true the JSON must declare a known `type` (used for plain ```json blocks); the
     * dedicated `bmoe-ui` fence does not need it, because the fence already says what the block is.
     */
    fun parse(source: String, requireType: Boolean = false): NativeUiSpec? = runCatching {
        if (source.length !in 2..MAX_JSON_CHARS) return null
        val json = repairJson(source) ?: return null
        val root = JSONObject(json)
        val declared = root.optString("type", "").lowercase()
        if (requireType && declared !in KNOWN_TYPES) return null

        val metrics = (0 until array(root, "metrics").length()).take(8).mapNotNull { i ->
            val o = array(root, "metrics").optJSONObject(i) ?: return@mapNotNull null
            NativeUiMetric(clean(o, "label", 48), clean(o, "value", 80), clean(o, "detail", 120))
                .takeIf { it.label.isNotBlank() && it.value.isNotBlank() }
        }
        val cards = (0 until array(root, "cards").length()).take(8).mapNotNull { i ->
            val o = array(root, "cards").optJSONObject(i) ?: return@mapNotNull null
            NativeUiCard(clean(o, "title", 80), clean(o, "subtitle", 100), clean(o, "body", 320))
                .takeIf { it.title.isNotBlank() }
        }
        val bars = (0 until array(root, "bars").length()).take(10).mapNotNull { i ->
            val o = array(root, "bars").optJSONObject(i) ?: return@mapNotNull null
            val value = o.optDouble("value", Double.NaN)
            val label = clean(o, "label", 56)
            if (label.isBlank() || !value.isFinite()) null else {
                val safeValue = value.coerceIn(0.0, 100.0)
                NativeUiBar(label, safeValue, clean(o, "valueLabel", 48).ifBlank { "${safeValue.toInt()}%" })
            }
        }
        val actions = (0 until array(root, "actions").length()).take(6).mapNotNull { i ->
            val o = array(root, "actions").optJSONObject(i) ?: return@mapNotNull null
            NativeUiAction(clean(o, "label", 64), clean(o, "prompt", 600))
                .takeIf { it.label.isNotBlank() && it.prompt.isNotBlank() }
        }
        val badges = (0 until array(root, "badges").length()).take(12).mapNotNull { i ->
            val raw = array(root, "badges").opt(i)
            val item = when (raw) {
                is JSONObject -> NativeUiBadge(clean(raw, "label", 48), clean(raw, "tone", 16).lowercase())
                is String -> NativeUiBadge(raw.trim().take(48), "neutral")
                else -> null
            }
            item?.takeIf { it.label.isNotBlank() }
        }
        val lists = (0 until array(root, "lists").length()).take(5).mapNotNull { i ->
            val o = array(root, "lists").optJSONObject(i) ?: return@mapNotNull null
            val itemsJson = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsJson.length()).take(12).mapNotNull { n ->
                when (val raw = itemsJson.opt(n)) {
                    is JSONObject -> NativeUiListItem(clean(raw, "title", 100), clean(raw, "detail", 180))
                        .takeIf { it.title.isNotBlank() }
                    is String -> NativeUiListItem(raw.trim().take(100), "").takeIf { it.title.isNotBlank() }
                    else -> null
                }
            }
            NativeUiList(clean(o, "title", 80), clean(o, "style", 12).lowercase().ifBlank { "bullet" }, items)
                .takeIf { it.items.isNotEmpty() }
        }
        // Small models sometimes put the rows straight on the root object or call them "items"/"table".
        val comparisonObject = root.optJSONObject("comparison")
            ?: if (root.optJSONArray("rows") != null) root else null
        val comparison = comparisonObject?.let { o ->
            val rowsJson = o.optJSONArray("rows") ?: o.optJSONArray("items") ?: o.optJSONArray("table") ?: JSONArray()
            val rows = (0 until rowsJson.length()).take(12).mapNotNull { i ->
                val r = rowsJson.optJSONObject(i) ?: return@mapNotNull null
                NativeUiComparisonRow(
                    alias(r, "label", "name", "feature", "aspect", "attribute", "category", max = 64),
                    alias(r, "left", "a", "valueA", "value1", "first", max = 100),
                    alias(r, "right", "b", "valueB", "value2", "second", max = 100),
                    r.optBoolean("highlight", false)
                ).takeIf { it.label.isNotBlank() }
            }
            NativeUiComparison(
                clean(o, "title", 80),
                alias(o, "leftTitle", "left_title", "a", "nameA", max = 48).ifBlank { "Option A" },
                alias(o, "rightTitle", "right_title", "b", "nameB", max = 48).ifBlank { "Option B" },
                rows
            ).takeIf { it.rows.isNotEmpty() }
        }
        val slides = (0 until array(root, "slides").length()).take(8).mapNotNull { i ->
            val o = array(root, "slides").optJSONObject(i) ?: return@mapNotNull null
            NativeUiSlide(clean(o, "title", 80), clean(o, "subtitle", 100), clean(o, "body", 480), clean(o, "metricLabel", 48), clean(o, "metricValue", 64))
                .takeIf { it.title.isNotBlank() || it.body.isNotBlank() }
        }
        val timeline = (0 until array(root, "timeline").length()).take(12).mapNotNull { i ->
            val o = array(root, "timeline").optJSONObject(i) ?: return@mapNotNull null
            NativeUiTimelineItem(clean(o, "time", 36), clean(o, "title", 80), clean(o, "detail", 200), clean(o, "status", 16).lowercase())
                .takeIf { it.title.isNotBlank() }
        }
        val sections = (0 until array(root, "sections").length()).take(8).mapNotNull { i ->
            val o = array(root, "sections").optJSONObject(i) ?: return@mapNotNull null
            NativeUiSection(clean(o, "title", 80), clean(o, "body", 700))
                .takeIf { it.title.isNotBlank() && it.body.isNotBlank() }
        }

        // A missing title must not throw a good table away.
        val title = clean(root, "title", 100)
            .ifBlank { comparison?.title.orEmpty() }
            .ifBlank { clean(root, "description", 100) }
            .ifBlank { slides.firstOrNull()?.title.orEmpty() }
            .ifBlank { lists.firstOrNull()?.title.orEmpty() }
            .ifBlank { cards.firstOrNull()?.title?.let { "Overview" }.orEmpty() }
            .ifBlank { if (comparison != null) "Comparison" else "Overview" }

        NativeUiSpec(
            title = title, description = clean(root, "description", 240),
            metrics = metrics, cards = cards, bars = bars, actions = actions, badges = badges,
            lists = lists, comparison = comparison, slides = slides, timeline = timeline, sections = sections
        ).takeIf {
            it.title.isNotBlank() && (it.metrics.isNotEmpty() || it.cards.isNotEmpty() || it.bars.isNotEmpty() ||
                it.actions.isNotEmpty() || it.badges.isNotEmpty() || it.lists.isNotEmpty() || it.comparison != null ||
                it.slides.isNotEmpty() || it.timeline.isNotEmpty() || it.sections.isNotEmpty())
        }
    }.getOrNull()
}

@Composable
fun NativeUiComponents(spec: NativeUiSpec, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    var selectedCard by remember(spec.title) { mutableIntStateOf(0) }
    AppCard(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        base = MaterialTheme.colorScheme.surfaceContainerLow, outline = true
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(spec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                        Text("NATIVE UI", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                if (spec.description.isNotBlank()) Text(spec.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (spec.badges.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.badges.forEach { badge -> NativeUiBadgePill(badge) }
                }
            }
            if (spec.metrics.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.metrics.forEach { metric ->
                        Surface(
                            modifier = Modifier.widthIn(min = 108.dp, max = 220.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(metric.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(metric.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                if (metric.detail.isNotBlank()) Text(metric.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (spec.comparison != null) NativeUiComparisonView(spec.comparison)
            if (spec.slides.isNotEmpty()) NativeUiSlides(spec.slides)
            if (spec.lists.isNotEmpty()) spec.lists.forEach { NativeUiListView(it) }
            if (spec.cards.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Options", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    spec.cards.forEachIndexed { index, card ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selectedCard == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(if (selectedCard == index) 1.5.dp else 1.dp, if (selectedCard == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            onClick = { selectedCard = index }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(card.title, fontWeight = FontWeight.SemiBold)
                                    if (card.subtitle.isNotBlank()) Text(card.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                }
                                if (selectedCard == index) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val selected = spec.cards[selectedCard.coerceIn(spec.cards.indices)]
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${selected.title} · Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (selected.subtitle.isNotBlank()) Text(selected.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (selected.body.isNotBlank()) Text(selected.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (spec.bars.isNotEmpty()) NativeUiProgressView(spec.bars)
            if (spec.timeline.isNotEmpty()) NativeUiTimelineView(spec.timeline)
            if (spec.sections.isNotEmpty()) spec.sections.forEach { NativeUiExpandableSection(it) }
            if (spec.actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.actions.forEachIndexed { index, action ->
                        if (index == 0) Button(onClick = { onAction(action.prompt) }) { Text(action.label) }
                        else OutlinedButton(onClick = { onAction(action.prompt) }) { Text(action.label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeUiBadgePill(badge: NativeUiBadge) {
    val tone = badge.tone
    val container = when (tone) {
        "success", "good" -> MaterialTheme.colorScheme.tertiaryContainer
        "warning", "caution" -> MaterialTheme.colorScheme.secondaryContainer
        "danger", "error" -> MaterialTheme.colorScheme.errorContainer
        "info", "primary" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (tone) {
        "success", "good" -> MaterialTheme.colorScheme.onTertiaryContainer
        "warning", "caution" -> MaterialTheme.colorScheme.onSecondaryContainer
        "danger", "error" -> MaterialTheme.colorScheme.onErrorContainer
        "info", "primary" -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, shape = CircleShape) {
        Text(badge.label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun NativeUiComparisonView(comparison: NativeUiComparison) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(comparison.title.ifBlank { "Comparison" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ComparisonCell("Feature", Modifier.width(104.dp), header = true)
                ComparisonCell(comparison.leftTitle, Modifier.width(132.dp), header = true)
                ComparisonCell(comparison.rightTitle, Modifier.width(132.dp), header = true)
            }
            comparison.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ComparisonCell(row.label, Modifier.width(104.dp), header = false, highlight = row.highlight)
                    ComparisonCell(row.left, Modifier.width(132.dp), header = false, highlight = row.highlight)
                    ComparisonCell(row.right, Modifier.width(132.dp), header = false, highlight = row.highlight)
                }
            }
        }
    }
}

@Composable
private fun ComparisonCell(text: String, modifier: Modifier, header: Boolean, highlight: Boolean = false) {
    Surface(
        modifier = modifier,
        color = when {
            header -> MaterialTheme.colorScheme.secondaryContainer
            highlight -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(text.ifBlank { "—" }, modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp), style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall, fontWeight = if (header || highlight) FontWeight.SemiBold else FontWeight.Normal, color = if (header) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun NativeUiSlides(slides: List<NativeUiSlide>) {
    var page by remember(slides) { mutableIntStateOf(0) }
    val slide = slides[page.coerceIn(slides.indices)]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Slides", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${page + 1} / ${slides.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (slide.subtitle.isNotBlank()) Text(slide.subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(slide.title.ifBlank { "Slide ${page + 1}" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (slide.body.isNotBlank()) Text(slide.body, style = MaterialTheme.typography.bodyMedium)
                if (slide.metricValue.isNotBlank()) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(slide.metricValue, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        if (slide.metricLabel.isNotBlank()) Text(slide.metricLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Text("‹ Previous") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                slides.indices.forEach { index ->
                    Box(Modifier.size(if (index == page) 8.dp else 6.dp).clip(CircleShape).background(if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            TextButton(onClick = { page = (page + 1).coerceAtMost(slides.lastIndex) }, enabled = page < slides.lastIndex) { Text("Next ›") }
        }
    }
}

@Composable
private fun NativeUiListView(list: NativeUiList) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (list.title.isNotBlank()) Text(list.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        list.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                val marker = if (list.style == "ordered" || list.style == "numbered") "${index + 1}" else "•"
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                    Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) {
                        Text(marker, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NativeUiProgressView(bars: List<NativeUiBar>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Progress", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        bars.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(item.valueLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((item.value / 100.0).toFloat()).height(9.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Text("Values are supplied by the model; verify measurements before relying on them.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NativeUiTimelineView(items: List<NativeUiTimelineItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = if (item.status in setOf("done", "complete", "completed")) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                        Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) {
                            Text(if (item.status in setOf("done", "complete", "completed")) "✓" else "${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (index < items.lastIndex) Box(Modifier.padding(top = 3.dp).width(2.dp).height(25.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (item.time.isNotBlank()) Text(item.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NativeUiExpandableSection(section: NativeUiSection) {
    var expanded by remember(section.title) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(section.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) Text(section.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
