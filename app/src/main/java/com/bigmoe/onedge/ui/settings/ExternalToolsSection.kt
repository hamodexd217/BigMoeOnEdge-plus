package com.bigmoe.onedge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.tools.ExternalEntry
import com.bigmoe.onedge.tools.ExternalKind
import com.bigmoe.onedge.tools.ExternalText
import com.bigmoe.onedge.tools.ExternalToolsJson
import com.bigmoe.onedge.tools.MAX_TOOLS_PER_SERVER
import com.bigmoe.onedge.tools.McpClient
import com.bigmoe.onedge.tools.McpToolDef
import kotlinx.coroutines.launch

/** The list of external tools (hand-made HTTP tools and MCP servers) with add / edit / delete. */
@Composable
internal fun ExternalToolsSection(json: String, onSave: (String) -> Unit) {
    val entries = remember(json) { ExternalToolsJson.parse(json) }
    var editing by remember { mutableStateOf<ExternalEntry?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ExternalEntry?>(null) }

    fun commit(list: List<ExternalEntry>) = onSave(ExternalToolsJson.toJson(list))

    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (entries.isEmpty()) {
            Hint("No external tools yet. Add an HTTP tool (one web address the AI may call) or an MCP server (it announces its own tools).")
        }
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    Hint(
                        when (e.kind) {
                            ExternalKind.HTTP -> "HTTP · ${e.method} · ${ExternalText.hostOf(e.url)}"
                            ExternalKind.MCP -> "MCP · ${ExternalText.hostOf(e.url)} · ${e.mcpTools.count { it.selected }} of ${e.mcpTools.size} tools"
                        } + if (e.askFirst) " · asks first" else " · runs without asking"
                    )
                }
                Switch(checked = e.enabled, onCheckedChange = { on -> commit(entries.map { if (it.id == e.id) it.copy(enabled = on) else it }) })
                TextButton(onClick = { editingIsNew = false; editing = e }) { Text("Edit") }
                TextButton(onClick = { deleting = e }) { Text("Delete") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                editingIsNew = true
                editing = ExternalEntry(kind = ExternalKind.HTTP, name = "", url = "")
            }) { Text("Add HTTP tool") }
            OutlinedButton(onClick = {
                editingIsNew = true
                editing = ExternalEntry(kind = ExternalKind.MCP, name = "", url = "")
            }) { Text("Add MCP server") }
        }
        Hint("Everything an external tool sends leaves this phone. Keep \"asks first\" on unless you trust the address. Headers (API keys) are stored on the phone like the search key.")
    }

    editing?.let { current ->
        ExternalEntryEditor(
            initial = current,
            onDismiss = { editing = null },
            onSave = { saved ->
                val list = if (editingIsNew) entries + saved else entries.map { if (it.id == saved.id) saved else it }
                commit(list)
                editing = null
            }
        )
    }
    deleting?.let { victim ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${victim.name}\"?") },
            text = { Text("The AI will no longer be able to use it.") },
            confirmButton = { TextButton(onClick = { commit(entries.filter { it.id != victim.id }); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ExternalEntryEditor(initial: ExternalEntry, onDismiss: () -> Unit, onSave: (ExternalEntry) -> Unit) {
    val isMcp = initial.kind == ExternalKind.MCP
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var url by remember { mutableStateOf(initial.url) }
    var method by remember { mutableStateOf(initial.method) }
    var headers by remember { mutableStateOf(initial.headers) }
    var paramsText by remember { mutableStateOf(ExternalText.paramsToText(initial.params)) }
    var askFirst by remember { mutableStateOf(initial.askFirst) }
    val tools = remember { mutableStateListOf<McpToolDef>().also { it.addAll(initial.mcpTools) } }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cleanUrl = url.trim()
    val urlOk = McpClient.isHttpUrl(cleanUrl)
    val canSave = name.isNotBlank() && urlOk && (!isMcp || tools.any { it.selected })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isMcp) "MCP server" else "HTTP tool") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (isMcp) "Server name (prefix of its tools)" else "Tool name, e.g. get_weather") }
                )
                if (!isMcp) {
                    OutlinedTextField(
                        value = description, onValueChange = { description = it.take(240) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("What it does (the AI reads this)") }
                    )
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = url.isNotBlank() && !urlOk,
                    label = { Text(if (isMcp) "Server address (https://…/mcp)" else "Address, e.g. https://api.example.com/weather?city={city}") }
                )
                if (!isMcp) {
                    Text("Method", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("GET", "POST", "PUT", "DELETE").forEach { m ->
                            RadioButton(selected = method == m, onClick = { method = m })
                            Text(m, modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(
                        value = paramsText, onValueChange = { paramsText = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Arguments, one per line") }
                    )
                    Hint("Format: name: what it is. Add ? after the name for an optional one. {name} in the address is replaced by the value; the rest go in the query (GET / DELETE) or as a JSON body (POST / PUT).")
                }
                OutlinedTextField(
                    value = headers, onValueChange = { headers = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Headers, one per line (optional)") }
                )
                Hint("Example: Authorization: Bearer YOUR_KEY")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = askFirst, onCheckedChange = { askFirst = it })
                    Text("  Ask me before every call", style = MaterialTheme.typography.bodyMedium)
                }
                if (isMcp) {
                    OutlinedButton(
                        enabled = urlOk && !loading,
                        onClick = {
                            loading = true
                            status = "Connecting…"
                            scope.launch {
                                try {
                                    val found = McpClient(cleanUrl, ExternalText.parseHeaders(headers)).listTools()
                                    val previous = tools.associateBy { it.name }
                                    tools.clear()
                                    found.forEachIndexed { i, t ->
                                        tools.add(t.copy(selected = previous[t.name]?.selected ?: (i < MAX_TOOLS_PER_SERVER)))
                                    }
                                    status = if (found.isEmpty()) "Connected, but the server lists no tools." else "Found ${found.size} tools."
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    status = "Could not fetch tools: ${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    ) { Text(if (tools.isEmpty()) "Connect and fetch tools" else "Refresh tools") }
                    if (status.isNotBlank()) Hint(status)
                    tools.forEachIndexed { i, t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = t.selected, onCheckedChange = { on -> tools[i] = t.copy(selected = on) })
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.bodyMedium)
                                if (t.description.isNotBlank()) Hint(t.description.replace('\n', ' ').take(120))
                            }
                        }
                    }
                    if (tools.count { it.selected } > MAX_TOOLS_PER_SERVER) {
                        Hint("Only the first $MAX_TOOLS_PER_SERVER selected tools are given to the AI, to keep the prompt short.")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(), description = description.trim(), url = cleanUrl,
                            method = method, headers = headers.trim(),
                            params = if (isMcp) emptyList() else ExternalText.parseParams(paramsText),
                            askFirst = askFirst, mcpTools = if (isMcp) tools.toList() else emptyList()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
