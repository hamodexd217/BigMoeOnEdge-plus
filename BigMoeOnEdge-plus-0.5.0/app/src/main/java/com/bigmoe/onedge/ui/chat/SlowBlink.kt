package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// While the model generates, the CPU is the scarce resource. An infinite Compose animation (rememberInfiniteTransition,
// CircularProgressIndicator) invalidates the UI every display frame (60-120 times a second) for as long as it is
// on screen, which keeps the main thread, RenderThread and the system compositor busy next to the engine's compute
// threads. These helpers give the same "something is happening" cue by flipping state a couple of times a second.

/** A flag that flips every [periodMs] while it is in composition. */
@Composable
internal fun rememberSlowBlink(periodMs: Long = 500L): State<Boolean> {
    val on = remember { mutableStateOf(true) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            on.value = !on.value
        }
    }
    return on
}

/** Small pulsing dot used instead of an indeterminate spinner for live status. Alpha is read in the draw phase only. */
@Composable
internal fun SlowBlinkDot(modifier: Modifier = Modifier) {
    val on = rememberSlowBlink(600L)
    Box(
        modifier = modifier
            .size(10.dp)
            .graphicsLayer { this.alpha = if (on.value) 1f else 0.25f }
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}
