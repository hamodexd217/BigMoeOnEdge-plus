package com.bigmoe.onedge.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.graphics.Matrix
import com.bigmoe.onedge.core.ImageData
import java.io.File

/** Turns pictures and video frames into the RGB buffers the vision encoder takes. */
object MediaProcessor {

    /** Largest size with the same aspect ratio whose longer side is at most [maxDim] (never upscales). */
    fun fitSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= maxDim) return width to height
        val scale = maxDim.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** ARGB_8888 pixels (Android's getPixels layout) -> tightly packed RGB bytes. */
    fun rgbFromArgb(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 3)
        var o = 0
        for (p in pixels) {
            out[o++] = ((p shr 16) and 0xFF).toByte()
            out[o++] = ((p shr 8) and 0xFF).toByte()
            out[o++] = (p and 0xFF).toByte()
        }
        return out
    }

    /** Evenly spaced sample times (microseconds) strictly inside the clip. */
    fun frameTimesUs(durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0 || count <= 0) return listOf(0L)
        return (0 until count).map { i -> ((i + 0.5) / count * durationMs * 1000).toLong() }
    }

    fun bitmapToImage(bitmap: Bitmap, maxDim: Int): ImageData {
        val (w, h) = fitSize(bitmap.width, bitmap.height, maxDim)
        val scaled = if (w != bitmap.width || h != bitmap.height) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return ImageData(w, h, rgbFromArgb(pixels))
    }

    /** Decodes [file] (downsampled while reading), applies EXIF rotation, scales to [maxDim]. Null if not an image. */
    fun decodeImage(file: File, maxDim: Int): ImageData? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val rotation = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (rotation != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val image = bitmapToImage(bmp, maxDim)
        bmp.recycle()
        return image
    }

    data class VideoInfo(val durationMs: Long, val frames: List<ImageData>)

    /** [count] frames spread over the clip. Throws if the file is not a readable video. */
    fun extractFrames(file: File, count: Int, maxDim: Int): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = ArrayList<ImageData>()
            for (t in frameTimesUs(duration, count)) {
                val bmp = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                frames.add(bitmapToImage(bmp, maxDim))
                bmp.recycle()
            }
            if (frames.isEmpty()) throw IllegalStateException("no frames could be read from the video")
            return VideoInfo(duration, frames)
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
