package com.bigmoe.onedge.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.bigmoe.onedge.core.ImageData
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import com.bigmoe.onedge.workspace.FileKinds
import com.bigmoe.onedge.workspace.Workspace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class AttachmentException(message: String) : IOException(message)

/** A file the user picked but has not sent yet. [path] is relative to the workspace. */
data class PendingAttachment(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    val path: String,
    val mime: String,
    val sizeBytes: Long
)

/** What the model gets for pictures / video, and the one-line description kept in the chat history. */
data class PreparedMedia(val images: List<ImageData>, val notes: List<com.bigmoe.onedge.workspace.AttachmentContext.Media>)

/** Copies picked documents into the workspace (`uploads/`) and prepares them for a message. */
class AttachmentManager(
    private val workspace: Workspace,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun import(resolver: ContentResolver, uri: Uri, kind: AttachmentKind): PendingAttachment = withContext(io) {
        val (rawName, size) = queryNameAndSize(resolver, uri)
        val name = FileKinds.safeName(rawName ?: "attachment", "attachment")
        validateName(name, kind)
        val limit = when (kind) {
            AttachmentKind.FILE -> MAX_FILE_BYTES
            AttachmentKind.IMAGE -> MAX_IMAGE_BYTES
            AttachmentKind.VIDEO -> MAX_VIDEO_BYTES
        }
        if (size > limit) throw AttachmentException("$name is too large (${size / (1024 * 1024)} MB, limit ${limit / (1024 * 1024)} MB)")

        val rel = workspace.uniquePath("uploads/$name")
        val target = workspace.resolve(rel)
        target.parentFile?.mkdirs()
        try {
            val input = resolver.openInputStream(uri) ?: throw AttachmentException("Could not open $name")
            input.use { src ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw AttachmentException("$name is too large (limit ${limit / (1024 * 1024)} MB)")
                        out.write(buf, 0, n)
                    }
                }
            }
            validateContent(target, name, kind)
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        PendingAttachment(UUID.randomUUID().toString(), kind, rel.substringAfterLast('/'), rel, FileKinds.mimeFor(name), target.length())
    }

    private fun validateName(name: String, kind: AttachmentKind) {
        when (kind) {
            AttachmentKind.FILE -> {
                if (FileKinds.isImageName(name)) throw AttachmentException("$name is a picture: attach it with the Image option")
                if (FileKinds.isVideoName(name)) throw AttachmentException("$name is a video: attach it with the Video option")
            }
            else -> Unit
        }
    }

    private fun validateContent(file: File, name: String, kind: AttachmentKind) {
        when (kind) {
            AttachmentKind.FILE -> {
                val head = file.inputStream().use { s -> ByteArray(8192).let { b -> b.copyOf(maxOf(0, s.read(b))) } }
                if (FileKinds.looksBinary(head)) {
                    throw AttachmentException("$name is not a text or code file. Supported: plain text, source code, logs, JSON, CSV, Markdown, HTML, XML, YAML and similar.")
                }
            }
            AttachmentKind.IMAGE -> if (MediaProcessor.decodeImage(file, 64) == null) throw AttachmentException("$name is not a readable picture")
            AttachmentKind.VIDEO -> try {
                MediaProcessor.extractFrames(file, 1, 64)
            } catch (e: Exception) {
                throw AttachmentException("$name is not a readable video: ${e.message}")
            }
        }
    }

    /** The text of an attached document. */
    suspend fun readText(att: PendingAttachment): String = withContext(io) { workspace.readText(att.path, MAX_FILE_BYTES.toInt()) }

    /** Decodes pictures and samples video frames for the engine. */
    suspend fun prepareMedia(items: List<PendingAttachment>, maxDim: Int, videoFrames: Int): PreparedMedia = withContext(io) {
        val images = ArrayList<ImageData>()
        val notes = ArrayList<com.bigmoe.onedge.workspace.AttachmentContext.Media>()
        for (a in items) {
            val f = workspace.resolve(a.path)
            when (a.kind) {
                AttachmentKind.IMAGE -> {
                    val img = MediaProcessor.decodeImage(f, maxDim) ?: throw AttachmentException("${a.name} could not be decoded")
                    images.add(img)
                    notes.add(com.bigmoe.onedge.workspace.AttachmentContext.Media(a.name, "image", "size=\"${img.width}x${img.height}\""))
                }
                AttachmentKind.VIDEO -> {
                    val v = MediaProcessor.extractFrames(f, videoFrames, maxDim)
                    images.addAll(v.frames)
                    notes.add(
                        com.bigmoe.onedge.workspace.AttachmentContext.Media(
                            a.name, "video", "frames=\"${v.frames.size}\" duration_seconds=\"${v.durationMs / 1000}\" note=\"the frames are sampled evenly across the clip\""
                        )
                    )
                }
                AttachmentKind.FILE -> Unit
            }
        }
        PreparedMedia(images, notes)
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
        const val MAX_VIDEO_BYTES = 500L * 1024 * 1024
    }
}
