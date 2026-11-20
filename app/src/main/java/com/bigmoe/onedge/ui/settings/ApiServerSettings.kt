package com.bigmoe.onedge.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.ModelInfo
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.server.ApiServerController
import kotlinx.coroutines.delay
import com.bigmoe.onedge.i18n.tr

private const val API_DEBOUNCE_MS = 500L
private val QUEUE_CHOICES = listOf(5, 15, 30, 60, 120, 300)

/** Claude Code sends a very large prompt (system prompt + tool list); below this the first request will not fit. */
private const val COMFORTABLE_CONTEXT = 32768

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, tr("Copied"), Toast.LENGTH_SHORT).show()
}

/**
 * Settings > "Local API server", the last section, collapsed until the header (with its small arrow) is tapped.
 * It only holds what is specific to the server: on/off, port, endpoint (+ copy), key, authentication, "Force
 * server settings" and the queue wait. Everything else comes from the app's own settings: Context size, Max tokens,
 * Thinking, temperature / top_p / top_k and the MTP / guess-ahead settings.
 */
@Composable
internal fun ApiServerSection(
    settings: EngineSettings,
    readyInfo: ModelInfo?,
    viewModel: SettingsViewModel
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            tr("Local API server") + if (settings.serverEnabled) tr("  •  on") else "",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (expanded) "↑" else "↓",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (!expanded) return

    val context = LocalContext.current
    var portText by remember { mutableStateOf(settings.serverPort.toString()) }
    var showKey by remember { mutableStateOf(false) }
    val portValid = portText.toIntOrNull()?.let { it in 1024..65535 } == true

    LaunchedEffect(portText) {
        val v = portText.toIntOrNull()
        if (v != null && v in 1024..65535 && v != settings.serverPort) {
            delay(API_DEBOUNCE_MS)
            viewModel.updateServerPort(v)
        }
    }

    val url = ApiServerController.endpointUrl(settings)
    val authEffective = settings.serverRequireKey || settings.serverBindAll

    Hint(
        tr("Lets other apps on this phone (for example Mobile Harness running Claude Code) use the model that is loaded here, through the Anthropic Messages API (streaming, tool use) and the OpenAI chat API. It uses this app's own settings: Context size, Max tokens, Thinking, temperature / top_p / top_k and the MTP / guess-ahead options.")
    )
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        tr("Server"),
        if (settings.serverEnabled) tr("Running while this switch is on; a notification with a Stop button shows while it runs.")
        else tr("Off. Turning it on generates an API key the first time."),
        settings.serverEnabled
    ) { viewModel.setServerEnabled(it) }
    if (settings.serverEnabled && readyInfo == null) {
        Hint(tr("No model is loaded yet. Requests get HTTP 503 until you load one."))
    }
    if (readyInfo != null && readyInfo.nCtx < COMFORTABLE_CONTEXT) {
        Spacer(Modifier.height(4.dp))
        Hint(
            tr("The loaded model has a context of {0} tokens. Claude Code needs about {1} or more: raise \"Context size\" under Model, then reload the model.", readyInfo.nCtx, COMFORTABLE_CONTEXT)
        )
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = portText,
        onValueChange = { portText = it.filter { c -> c in '0'..'9' }.take(5) },
        label = { Text(tr("Port")) },
        singleLine = true,
        isError = !portValid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Hint(if (portValid) tr("1024 to 65535. Changing the port restarts the server.") else tr("Enter a port between 1024 and 65535."))

    Spacer(Modifier.height(12.dp))
    Text(tr("Endpoint"), style = MaterialTheme.typography.labelMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(url, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { copyToClipboard(context, tr("API endpoint"), url) }) { Text(tr("Copy")) }
    }
    Hint(tr("Anthropic: {0}/v1/messages   OpenAI: {1}/v1/chat/completions   Models: {2}/v1/models", url, url, url))
    if (settings.serverBindAll) Hint(tr("Replace <device-ip> with this phone's address on the network."))

    Spacer(Modifier.height(12.dp))
    Text(tr("API key"), style = MaterialTheme.typography.labelMedium)
    val key = settings.serverApiKey
    Text(
        text = when {
            key.isBlank() -> tr("(generated when the server is first turned on)")
            showKey -> key
            else -> "••••••••••••" + key.takeLast(4)
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showKey = !showKey }, enabled = key.isNotBlank()) { Text(if (showKey) tr("Hide") else tr("Show")) }
        OutlinedButton(onClick = { copyToClipboard(context, tr("API key"), key) }, enabled = key.isNotBlank()) { Text(tr("Copy key")) }
        OutlinedButton(onClick = { viewModel.regenerateServerKey() }) { Text(tr("New key")) }
    }
    Hint(tr("Send it as  x-api-key: <key>  or  Authorization: Bearer <key>. A new key stops working clients until you update them."))

    Spacer(Modifier.height(8.dp))
    SwitchRow(
        tr("Require API key"),
        if (settings.serverBindAll) tr("Always on while the server listens on the network.")
        else tr("Turning this off lets any app on this phone use the model without a key."),
        authEffective,
        enabled = !settings.serverBindAll
    ) { viewModel.updateServerRequireKey(it) }
    if (!authEffective) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                tr("Warning: with the key off, every app on this phone that can reach 127.0.0.1:{0} can run prompts on your model and read what it answers. Only do this if you trust every app installed here.", settings.serverPort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    SwitchRow(
        tr("Listen on the network (all interfaces)"),
        tr("Off = 127.0.0.1 only, reachable just from this phone (recommended). On = other devices on the same network can connect; the key is then mandatory. Restarts the server."),
        settings.serverBindAll
    ) { viewModel.updateServerBindAll(it) }

    Spacer(Modifier.height(8.dp))
    SwitchRow(
        tr("Force server settings"),
        tr("Ignore the temperature / top_p / top_k, thinking and max_tokens that clients send and use this app's own settings instead. Off: client values are used where the engine supports them (stop sequences and stream are always honoured; with MTP / guess-ahead on, sampling is greedy whatever the client asks). Every response lists what was ignored in an X-BigMoe-Ignored header."),
        settings.serverForceSettings
    ) { viewModel.updateServerForceSettings(it) }

    ChoiceSetting(
        tr("Wait in queue before answering 529"),
        QUEUE_CHOICES.map { it to "$it s" },
        selected = settings.serverQueueTimeoutSec
    ) { viewModel.updateServerQueueTimeoutSec(it) }
    Hint(tr("One request runs at a time. The in-app chat has priority: if you send a message while an API request is running, that request is cancelled (HTTP 529 / overloaded)."))
}
