package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * One small line under the live answer: what the model is doing right now and for how long (reading the prompt,
 * thinking, using a tool), plus prompt-reading speed and generation speed. The clock ticks about four times a second
 * and only while this is on screen.
 */
@Composable
fun LiveStatusLine(timing: LiveTiming, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatLiveStatus(timing, now),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Ticking "12.3 s" for a phase that started at [startedAt]; used by the Thinking header while it is still going. */
@Composable
fun rememberElapsedSince(startedAt: Long?): Long? {
    if (startedAt == null || startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    return maxOf(0L, now - startedAt)
}
