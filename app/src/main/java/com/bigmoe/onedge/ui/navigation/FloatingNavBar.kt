package com.bigmoe.onedge.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class TopTab(val label: String, val icon: ImageVector) {
    MODELS("Models", Icons.Default.Memory),
    CHATS("Chats", Icons.Default.ChatBubble),
    SETTINGS("Settings", Icons.Default.Settings)
}

/** Floating pill with three tabs (the selected one shows its label) and a "+" button for a new chat. */
@Composable
fun FloatingNavBar(
    selected: TopTab,
    onSelect: (TopTab) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TopTab.values().forEach { tab ->
                    val active = tab == selected
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                            .clickable { onSelect(tab) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(22.dp)
                        )
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "New chat", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
