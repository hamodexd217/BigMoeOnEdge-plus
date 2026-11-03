package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.ui.theme.appSurfaceModifier
import com.bigmoe.onedge.ui.theme.AppTheme
import com.bigmoe.onedge.data.local.datastore.AppVisualStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyChatView(
    onSuggestionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = listOf(
        "Tell me a creative bedtime story about a curious star.",
        "Tell me about the solar system and how planets were formed.",
        "Write a clean binary search function in Python.",
        "Explain quantum physics in simple terms like I am 5..."
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "What can I help with?",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(48.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2
        ) {
            suggestions.forEach { suggestion ->
                val cardShape = RoundedCornerShape(16.dp)
                val cardStyled = AppTheme.visualStyle != AppVisualStyle.SOLID
                Surface(
                    modifier = Modifier
                        .widthIn(max = 160.dp)
                        .clip(cardShape)
                        .then(if (cardStyled) appSurfaceModifier(cardShape, MaterialTheme.colorScheme.surface) else Modifier)
                        .clickable { onSuggestionSelected(suggestion) },
                    color = if (cardStyled) Color.Transparent else MaterialTheme.colorScheme.surface,
                    border = if (cardStyled) null else androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    ),
                    shape = cardShape
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }
}
