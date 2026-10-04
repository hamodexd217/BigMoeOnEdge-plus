package com.bigmoe.onedge.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.bigmoe.onedge.ui.theme.PalettePresets
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.ui.theme.ColorMath
import com.bigmoe.onedge.ui.theme.PaletteSpec

/** The nine colours of a palette, in editor order. */
private enum class ColorSlot(val label: String) {
    PRIMARY("Primary"),
    SECONDARY("Secondary"),
    BACKGROUND("Background"),
    SURFACE("Surface"),
    TEXT("Text"),
    ACCENT("Accent"),
    USER("User message"),
    AI("AI message"),
    CODE("Code blocks")
}

private fun PaletteSpec.get(slot: ColorSlot): Int = when (slot) {
    ColorSlot.PRIMARY -> primary
    ColorSlot.SECONDARY -> secondary
    ColorSlot.BACKGROUND -> background
    ColorSlot.SURFACE -> surface
    ColorSlot.TEXT -> text
    ColorSlot.ACCENT -> accent
    ColorSlot.USER -> userMessage
    ColorSlot.AI -> aiMessage
    ColorSlot.CODE -> codeBlock
}

private fun PaletteSpec.with(slot: ColorSlot, color: Int): PaletteSpec = when (slot) {
    ColorSlot.PRIMARY -> copy(primary = color)
    ColorSlot.SECONDARY -> copy(secondary = color)
    ColorSlot.BACKGROUND -> copy(background = color)
    ColorSlot.SURFACE -> copy(surface = color)
    ColorSlot.TEXT -> copy(text = color)
    ColorSlot.ACCENT -> copy(accent = color)
    ColorSlot.USER -> copy(userMessage = color)
    ColorSlot.AI -> copy(aiMessage = color)
    ColorSlot.CODE -> copy(codeBlock = color)
}

/** Small swatch card used in the palette picker: four colour dots and the name. */
@Composable
fun PaletteChip(palette: PaletteSpec, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(palette.surface),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp).width(104.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(palette.background, palette.primary, palette.accent, palette.userMessage).forEach {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(Color(it)))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                palette.name,
                style = MaterialTheme.typography.labelMedium,
                color = Color(palette.text),
                maxLines = 1
            )
        }
    }
}

/** Live preview of a palette: background, user bubble, AI bubble and a code block. */
@Composable
private fun PalettePreview(p: PaletteSpec) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(p.background), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.align(Alignment.End).clip(RoundedCornerShape(14.dp)).background(Color(p.userMessage))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Hello!", color = Color(ColorMath.onColor(p.userMessage))) }
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Color(p.aiMessage))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Hi, how can I help?", color = Color(ColorMath.onColor(p.aiMessage))) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(p.codeBlock)).padding(10.dp)
            ) {
                Text("fun main() = println(\"hi\")", fontFamily = FontFamily.Monospace, color = Color(ColorMath.onColor(p.codeBlock)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(p.primary))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Primary", color = Color(ColorMath.onColor(p.primary))) }
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(p.secondary))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Secondary", color = Color(ColorMath.onColor(p.secondary))) }
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(p.accent))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Accent", color = Color(ColorMath.onColor(p.accent))) }
            }
            Text("Body text on background", color = Color(p.text))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(p.surface)).padding(10.dp)) {
                Text("Text on surface", color = Color(p.text))
            }
        }
    }
}

/**
 * Create / edit a custom palette. [initial] is the palette being edited, or a copy of an existing one for
 * "new" (its id is replaced by the caller). Saving returns the edited palette through [onSave].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaletteEditorDialog(
    initial: PaletteSpec,
    onSave: (PaletteSpec) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<ColorSlot?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "New palette" else "Edit palette") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PalettePreview(draft)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(30)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark palette (light status-bar icons)", modifier = Modifier.weight(1f))
                    Switch(checked = draft.dark, onCheckedChange = { draft = draft.copy(dark = it) })
                }
                ColorSlot.values().forEach { slot ->
                    ColorRow(
                        label = slot.label,
                        color = draft.get(slot),
                        onColor = { draft = draft.with(slot, it) },
                        onPick = { picking = slot }
                    )
                }
                val low = ColorMath.contrast(draft.text, draft.background) < 4.5
                if (low) {
                    Text(
                        "Text on background has low contrast; it may be hard to read.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(name = draft.name.trim().ifEmpty { "Custom" }, custom = true)) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    picking?.let { slot ->
        ColorPickerDialog(
            title = slot.label,
            initial = draft.get(slot),
            onPick = { draft = draft.with(slot, it); picking = null },
            onDismiss = { picking = null }
        )
    }
}

@Composable
private fun ColorRow(label: String, color: Int, onColor: (Int) -> Unit, onPick: () -> Unit) {
    var text by remember(color) { mutableStateOf(ColorMath.toHex(color)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Color(color))
                .clickable(onClick = onPick)
        )
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.take(7)
                ColorMath.parseHex(text)?.let(onColor)
            },
            isError = ColorMath.parseHex(text) == null,
            singleLine = true,
            modifier = Modifier.width(118.dp)
        )
    }
}

/** Hue / saturation / brightness sliders with a live swatch. */
@Composable
private fun ColorPickerDialog(title: String, initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember(initial) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(initial, out)
        out
    }
    var h by remember(initial) { mutableStateOf(hsv[0]) }
    var s by remember(initial) { mutableStateOf(hsv[1]) }
    var v by remember(initial) { mutableStateOf(hsv[2]) }
    val current = Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(current))
                Text(ColorMath.toHex(current.toArgb()), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Text("Saturation", style = MaterialTheme.typography.labelMedium)
                Slider(value = s, onValueChange = { s = it }, valueRange = 0f..1f)
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(value = v, onValueChange = { v = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current.toArgb() or (0xFF shl 24)) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * One button that shows the current palette; tapping it opens the list: "System", the built-in palettes, the
 * custom ones, then "New custom palette" and (when a custom palette is selected) "Edit".
 */
@Composable
fun PaletteDropdown(
    selectedId: String,
    presets: List<PaletteSpec>,
    custom: List<PaletteSpec>,
    onSelect: (String) -> Unit,
    onNewCustom: () -> Unit,
    onEditSelected: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val current = (presets + custom).firstOrNull { it.id == selectedId }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            if (current != null) {
                PaletteDots(current)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = current?.name ?: "System (follows the device)",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 460.dp)
        ) {
            DropdownMenuItem(
                text = { PaletteMenuRow(null, "System (follows the device)", selectedId == PalettePresets.SYSTEM_ID) },
                onClick = { open = false; onSelect(PalettePresets.SYSTEM_ID) }
            )
            PaletteMenuHeader("Built-in")
            presets.forEach { palette ->
                DropdownMenuItem(
                    text = { PaletteMenuRow(palette, palette.name, palette.id == selectedId) },
                    onClick = { open = false; onSelect(palette.id) }
                )
            }
            if (custom.isNotEmpty()) {
                PaletteMenuHeader("My palettes")
                custom.forEach { palette ->
                    DropdownMenuItem(
                        text = { PaletteMenuRow(palette, palette.name, palette.id == selectedId) },
                        onClick = { open = false; onSelect(palette.id) }
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New custom palette") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { open = false; onNewCustom() }
            )
            if (onEditSelected != null) {
                DropdownMenuItem(
                    text = { Text("Edit this palette") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { open = false; onEditSelected() }
                )
            }
        }
    }
}

@Composable
private fun PaletteMenuHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun PaletteMenuRow(palette: PaletteSpec?, name: String, selected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (palette != null) {
            PaletteDots(palette)
            Spacer(Modifier.width(10.dp))
        }
        Text(name, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PaletteDots(palette: PaletteSpec) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(palette.background, palette.primary, palette.accent, palette.userMessage).forEach {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(it)))
        }
    }
}
