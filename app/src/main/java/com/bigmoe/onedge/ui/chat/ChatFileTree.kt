package com.bigmoe.onedge.ui.chat

import com.bigmoe.onedge.data.repository.ArtifactDomainModel
import com.bigmoe.onedge.workspace.FileKinds

/** One line of the chat's file list. */
sealed interface FileTreeRow {
    val depth: Int

    data class Folder(
        val path: String,
        val name: String,
        override val depth: Int,
        /** Files below this folder, at any depth. */
        val fileCount: Int,
        val expanded: Boolean
    ) : FileTreeRow

    data class File(
        val path: String,
        val title: String,
        override val depth: Int
    ) : FileTreeRow
}

/**
 * Turns the chat's AI-made files and folders (flat workspace paths) into the indented list shown in the
 * chat's files sheet. A folder appears when the AI made it, and also whenever a file lives inside it, so
 * `test/test.html` always shows up under `test/`. Pure Kotlin, unit-tested.
 */
object ChatFileTree {

    fun build(items: List<ArtifactDomainModel>, collapsed: Set<String> = emptySet()): List<FileTreeRow> {
        val folders = LinkedHashSet<String>()
        val files = ArrayList<ArtifactDomainModel>()
        for (item in items) {
            val path = item.path.trim('/')
            if (path.isEmpty()) continue
            if (item.language == FileKinds.FOLDER) {
                folders.add(path)
                addAncestors(path, folders)
            } else {
                files.add(item.copy(path = path))
                addAncestors(path, folders)
            }
        }

        fun parentOf(path: String) = path.substringBeforeLast('/', "")
        val subfolders = folders.groupBy { parentOf(it) }
        val filesByFolder = files.groupBy { parentOf(it.path) }

        fun fileCount(folder: String): Int =
            filesByFolder[folder].orEmpty().size + subfolders[folder].orEmpty().sumOf { fileCount(it) }

        val rows = ArrayList<FileTreeRow>()
        fun emit(parent: String, depth: Int) {
            for (folder in subfolders[parent].orEmpty().sortedBy { it.substringAfterLast('/').lowercase() }) {
                val open = folder !in collapsed
                rows.add(FileTreeRow.Folder(folder, folder.substringAfterLast('/'), depth, fileCount(folder), open))
                if (open) emit(folder, depth + 1)
            }
            for (file in filesByFolder[parent].orEmpty().sortedBy { it.path.substringAfterLast('/').lowercase() }) {
                rows.add(FileTreeRow.File(file.path, file.title.ifBlank { file.path.substringAfterLast('/') }, depth))
            }
        }
        emit("", 0)
        return rows
    }

    private fun addAncestors(path: String, into: MutableSet<String>) {
        var p = path.substringBeforeLast('/', "")
        while (p.isNotEmpty()) {
            into.add(p)
            p = p.substringBeforeLast('/', "")
        }
    }
}
