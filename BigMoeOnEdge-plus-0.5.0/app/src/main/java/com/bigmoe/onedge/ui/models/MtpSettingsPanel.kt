package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.ui.settings.ChoiceSetting
import com.bigmoe.onedge.ui.settings.Hint
import com.bigmoe.onedge.ui.settings.SwitchRow

private val DRAFT_CHOICES = listOf(1, 2, 3, 4, 5)
private val P_MIN_CHOICES = listOf(0, 40, 60, 80)

/**
 * MTP / "Guess ahead" settings. They used to live under Settings > Performance & engine > Experimental and were
 * moved onto the Models page, next to the MTP sidecar they belong to. They are global (not per model) and, like
 * every engine setting, are fixed when a model is opened, so a reload applies a change.
 */
@Composable
internal fun MtpSettingsPanel(settings: EngineSettings, viewModel: ModelViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Applies to every model. Fixed when a model is opened: reload the model after changing it.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChoiceSetting(
            "Guess ahead",
            listOf(SpecSource.OFF to "Off", SpecSource.MTP to "Model's own head (MTP)", SpecSource.NGRAM to "Repeated text (n-gram)"),
            settings.spec, enabled = settings.routeAhead == 0
        ) { viewModel.updateSpec(it) }
        if (settings.routeAhead != 0) {
            Hint("Unavailable while Route-ahead is on (Settings > Performance & engine > Experimental).")
        }
        Hint("Draft the next few tokens, verify the group in one decode, keep only what the model would have produced. Lossless. The MTP head is accurate but only some models carry it (others fail to load); the n-gram lookup works on any model but only fires on repeated text.")
        if (settings.spec != SpecSource.OFF) {
            ChoiceSetting("Tokens guessed per pass", DRAFT_CHOICES.map { it to it.toString() }, settings.draftMax) {
                viewModel.updateDraftMax(it)
            }
        }
        if (settings.spec == SpecSource.MTP) {
            ChoiceSetting(
                "Guess only when confident",
                P_MIN_CHOICES.map { it to if (it == 0) "always draft" else "above $it%" },
                settings.mtpPMinPct
            ) { viewModel.updateMtpPMinPct(it) }
            SwitchRow(
                "Use a separate MTP GGUF",
                "Uses the attached MTP sidecar while keeping Expert Streaming enabled for the main MoE model. The sidecar's own MTP weights use mmap rather than the target expert stream and may add memory use. Very large models still need enough RAM for the MTP head and runtime buffers.",
                settings.separateMtpDraftEnabled
            ) { viewModel.updateSeparateMtpDraftEnabled(it) }
        }
    }
}
