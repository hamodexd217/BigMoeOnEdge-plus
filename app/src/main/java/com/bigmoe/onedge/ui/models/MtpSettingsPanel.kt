package com.bigmoe.onedge.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.ui.settings.Hint
import com.bigmoe.onedge.ui.settings.SwitchRow

/** Model-file coupling stays beside the attached sidecar; decode tuning lives under Settings > Engine performance. */
@Composable
internal fun MtpSettingsPanel(settings: EngineSettings, viewModel: ModelViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (settings.spec == SpecSource.MTP) {
            SwitchRow(
                "Use a separate MTP GGUF",
                "Uses the attached MTP-only sidecar while keeping expert streaming enabled for the main MoE model. The draft weights use mmap and add to memory use. This option only works when Guess ahead is set to MTP under Settings → Engine performance.",
                settings.separateMtpDraftEnabled
            ) { viewModel.updateSeparateMtpDraftEnabled(it) }
        } else {
            Text(
                "To tune MTP or n-gram draft behavior, open Settings → Engine performance → Advanced / experimental settings → Speculation and routing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Hint("Keep the attached sidecar on the same model record. Very large target models still need enough RAM for the extra draft weights and runtime buffers.")
    }
}
