package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.tools.ImageSearchTool
import org.json.JSONObject

/** Parser for the explicit marker block emitted by [ImageSearchTool]. */
object ImageSearchResultParser {
    fun parse(body: String): List<ImageSearchResult>? {
        val start = body.indexOf(ImageSearchTool.START_MARKER)
        if (start < 0) return null
        val jsonStart = start + ImageSearchTool.START_MARKER.length
        val end = body.indexOf(ImageSearchTool.END_MARKER, jsonStart)
        if (end < 0) return null
        return runCatching {
            val json = JSONObject(body.substring(jsonStart, end).trim())
            val array = json.optJSONArray("results") ?: return null
            val parsed = ArrayList<ImageSearchResult>()
            for (i in 0 until minOf(array.length(), 4)) {
                val item = array.optJSONObject(i) ?: continue
                val image = item.optString("image").takeIf { it.startsWith("https://", true) } ?: continue
                val page = item.optString("url").takeIf { it.startsWith("https://", true) } ?: image
                val thumb = item.optString("thumbnail").takeIf { it.startsWith("https://", true) } ?: image
                parsed.add(ImageSearchResult(
                    title = item.optString("title").ifBlank { "Image result ${i + 1}" }.take(180),
                    imageUrl = image, thumbnailUrl = thumb, pageUrl = page, source = item.optString("source").take(80)
                ))
            }
            parsed.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

data class ImageSearchResult(
    val title: String,
    val imageUrl: String,
    val thumbnailUrl: String,
    val pageUrl: String,
    val source: String
)
