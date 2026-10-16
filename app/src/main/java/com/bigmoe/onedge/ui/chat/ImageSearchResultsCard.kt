package com.bigmoe.onedge.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun ImageSearchResultsCard(body: String, modifier: Modifier = Modifier) {
    val results = ImageSearchResultParser.parse(body) ?: return
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Image search", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results, key = { it.imageUrl }) { item ->
                    Column(Modifier.width(184.dp)) {
                        ImagePreview(
                            url = item.thumbnailUrl,
                            modifier = Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(10.dp))
                                .clickable { runCatching { uriHandler.openUri(item.pageUrl) } }
                        )
                        Text(
                            text = item.title,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val source = item.source.ifBlank { runCatching { URL(item.pageUrl).host }.getOrDefault("") }
                        Text(
                            text = source,
                            modifier = Modifier.padding(top = 2.dp).clickable { runCatching { uriHandler.openUri(item.pageUrl) } },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Text("Tap a preview to open its source page.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImagePreview(url: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) { downloadPreview(url) }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Image search preview", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("Preview unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Downloads a small, downsampled HTTPS preview only; avoids large remote images exhausting app memory. */
private fun downloadPreview(rawUrl: String): Bitmap? {
    if (!rawUrl.startsWith("https://", ignoreCase = true)) return null
    val connection = runCatching { URL(rawUrl).openConnection() as? HttpURLConnection }.getOrNull() ?: return null
    return try {
        connection.connectTimeout = 3500
        connection.readTimeout = 3500
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BigMoeOnEdgePlus/0.4")
        if (connection.responseCode !in 200..299) return null
        val bytes = connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < MAX_PREVIEW_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, MAX_PREVIEW_BYTES - total))
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Reject pathological dimensions before decode and downsample large-but-reasonable previews aggressively.
        if (bounds.outWidth > 20_000 || bounds.outHeight > 20_000) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_PREVIEW_DIM || bounds.outHeight / sample > MAX_PREVIEW_DIM) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private const val MAX_PREVIEW_BYTES = 1_500_000
private const val MAX_PREVIEW_DIM = 720
