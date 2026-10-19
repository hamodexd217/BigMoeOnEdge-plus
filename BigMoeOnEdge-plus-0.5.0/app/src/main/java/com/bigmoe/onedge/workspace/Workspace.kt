package com.bigmoe.onedge.workspace

import java.io.File
import java.io.IOException

class WorkspaceException(message: String) : IOException(message)

data class WorkspaceEntry(val path: String, val isDirectory: Boolean, val sizeBytes: Long, val modifiedMs: Long)

/**
 * The only place the AI's file tools (and the viewer) may touch: a private directory tree. Every path is a
 * RELATIVE path inside it; anything that would leave the root (`..`, absolute paths, symlinks) is refused.
 * Pure java.io, so it is unit-tested on the JVM.
 */
class Workspace(root: File) {
    val rootDir: File = root.apply { mkdirs() }.canonicalFile

    /** Normalises user/model input: backslashes, leading "./" and "/", repeated separators. */
    fun normalize(path: String): String {
        val cleaned = path.trim().replace('\\', '/')
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        return parts.joinToString("/")
    }

    /** Resolves a relative path to a file inside the root, or throws. "" / "." is the root itself. */
    fun resolve(path: String): File {
        val norm = normalize(path)
        if (norm.split('/').any { it == ".." }) throw WorkspaceException("Path escapes the workspace: $path")
        val f = if (norm.isEmpty()) rootDir else File(rootDir, norm)
        val canonical = try { f.canonicalFile } catch (e: IOException) { throw WorkspaceException("Bad path: $path") }
        val rootPath = rootDir.path
        if (canonical.path != rootPath && !canonical.path.startsWith(rootPath + File.separator)) {
            throw WorkspaceException("Path escapes the workspace: $path")
        }
        return canonical
    }

    /** Workspace-relative form of [file] ("" for the root). */
    fun relative(file: File): String {
        val p = file.canonicalFile.path
        return if (p == rootDir.path) "" else p.removePrefix(rootDir.path + File.separator).replace(File.separatorChar, '/')
    }

    fun exists(path: String): Boolean = resolve(path).exists()
    fun isDirectory(path: String): Boolean = resolve(path).isDirectory

    /** Directory listing, directories first then by name; [recursive] walks the tree (capped by [maxEntries]). */
    fun list(path: String = "", recursive: Boolean = false, maxEntries: Int = 300): List<WorkspaceEntry> {
        val dir = resolve(path)
        if (!dir.exists()) throw WorkspaceException("No such folder: ${normalize(path).ifEmpty { "/" }}")
        if (!dir.isDirectory) throw WorkspaceException("Not a folder: ${normalize(path)}")
        val out = ArrayList<WorkspaceEntry>()
        fun walk(d: File) {
            val children = d.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (c in children) {
                if (out.size >= maxEntries) return
                out.add(WorkspaceEntry(relative(c), c.isDirectory, if (c.isFile) c.length() else 0L, c.lastModified()))
                if (recursive && c.isDirectory) walk(c)
            }
        }
        walk(dir)
        return out
    }

    /** All regular files below [path] (recursive), capped. */
    fun walkFiles(path: String = "", maxFiles: Int = 2000): List<File> {
        val start = resolve(path)
        if (!start.exists()) return emptyList()
        if (start.isFile) return listOf(start)
        val out = ArrayList<File>()
        fun walk(d: File) {
            val children = d.listFiles()?.sortedBy { it.name.lowercase() } ?: return
            for (c in children) {
                if (out.size >= maxFiles) return
                if (c.isDirectory) walk(c) else if (c.isFile) out.add(c)
            }
        }
        walk(start)
        return out
    }

    fun readBytes(path: String, maxBytes: Int = 4_000_000): ByteArray {
        val f = resolve(path)
        if (!f.exists()) throw WorkspaceException("No such file: ${normalize(path)}")
        if (!f.isFile) throw WorkspaceException("Not a file: ${normalize(path)}")
        if (f.length() > maxBytes) throw WorkspaceException("File too large (${f.length()} bytes, limit $maxBytes): ${normalize(path)}")
        return f.readBytes()
    }

    /** Reads a text file; refuses binary content. */
    fun readText(path: String, maxBytes: Int = 4_000_000): String {
        val bytes = readBytes(path, maxBytes)
        if (FileKinds.looksBinary(bytes)) throw WorkspaceException("Not a text file: ${normalize(path)}")
        return FileKinds.decode(bytes)
    }

    /** Writes [content] (UTF-8), creating parent folders. With [overwrite] false an existing file is an error. */
    fun writeText(path: String, content: String, overwrite: Boolean): File {
        val f = resolve(path)
        if (f == rootDir) throw WorkspaceException("Need a file name")
        if (f.isDirectory) throw WorkspaceException("A folder with that name exists: ${normalize(path)}")
        if (f.exists() && !overwrite) throw WorkspaceException("File already exists: ${normalize(path)}")
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    fun writeBytes(path: String, bytes: ByteArray): File {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    fun mkdir(path: String): File {
        val f = resolve(path)
        if (f.isFile) throw WorkspaceException("A file with that name exists: ${normalize(path)}")
        if (!f.exists() && !f.mkdirs()) throw WorkspaceException("Could not create folder: ${normalize(path)}")
        return f
    }

    /** Moves/renames; the target must not exist. */
    fun rename(from: String, to: String): File {
        val src = resolve(from)
        val dst = resolve(to)
        if (src == rootDir) throw WorkspaceException("Cannot rename the workspace root")
        if (!src.exists()) throw WorkspaceException("No such file or folder: ${normalize(from)}")
        if (dst.exists()) throw WorkspaceException("Target already exists: ${normalize(to)}")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw WorkspaceException("Could not rename ${normalize(from)} to ${normalize(to)}")
        return dst
    }

    /** Deletes a file or a folder with everything in it. */
    fun delete(path: String) {
        val f = resolve(path)
        if (f == rootDir) throw WorkspaceException("Cannot delete the workspace root")
        if (!f.exists()) throw WorkspaceException("No such file or folder: ${normalize(path)}")
        if (!f.deleteRecursively()) throw WorkspaceException("Could not delete: ${normalize(path)}")
    }

    /** "dir/a.txt" -> "dir/a.txt" if free, else "dir/a (1).txt", "dir/a (2).txt", ... */
    fun uniquePath(path: String): String {
        val norm = normalize(path)
        if (!resolve(norm).exists()) return norm
        val dir = norm.substringBeforeLast('/', "")
        val name = norm.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = (if (dir.isEmpty()) "" else "$dir/") + "$stem ($n)$ext"
            if (!resolve(candidate).exists()) return candidate
            n++
        }
    }
}
