package com.bigmoe.onedge.ui

import com.bigmoe.onedge.data.repository.ArtifactDomainModel
import com.bigmoe.onedge.ui.chat.ChatFileTree
import com.bigmoe.onedge.ui.chat.FileTreeRow
import com.bigmoe.onedge.workspace.FileKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFileTreeTest {
    private fun file(path: String) = ArtifactDomainModel(path, "s", "m", path.substringAfterLast('/'), path, "text", 0)
    private fun folder(path: String) = ArtifactDomainModel(path, "s", "m", path.substringAfterLast('/'), path, FileKinds.FOLDER, 0)

    @Test
    fun emptyFolderIsListed() {
        val rows = ChatFileTree.build(listOf(folder("test")))
        assertEquals(1, rows.size)
        val f = rows[0] as FileTreeRow.Folder
        assertEquals("test", f.name)
        assertEquals(0, f.fileCount)
    }

    @Test
    fun fileIsShownInsideItsFolder() {
        val rows = ChatFileTree.build(listOf(folder("test"), file("test/test.html")))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is FileTreeRow.Folder)
        val f = rows[1] as FileTreeRow.File
        assertEquals("test/test.html", f.path)
        assertEquals(1, f.depth)
        assertEquals(1, (rows[0] as FileTreeRow.Folder).fileCount)
    }

    @Test
    fun folderAppearsEvenWhenOnlyAFileInsideWasRegistered() {
        val rows = ChatFileTree.build(listOf(file("a/b/c.txt")))
        assertEquals(listOf("a", "b"), rows.filterIsInstance<FileTreeRow.Folder>().map { it.name })
        assertEquals(2, (rows.last() as FileTreeRow.File).depth)
    }

    @Test
    fun foldersComeBeforeFilesAndAreSorted() {
        val rows = ChatFileTree.build(listOf(file("z.md"), file("a.md"), folder("zeta"), folder("alpha")))
        assertEquals(listOf("alpha", "zeta", "a.md", "z.md"), rows.map {
            when (it) { is FileTreeRow.Folder -> it.name; is FileTreeRow.File -> it.title }
        })
    }

    @Test
    fun collapsedFolderHidesItsContent() {
        val items = listOf(folder("test"), file("test/test.html"), file("top.txt"))
        val rows = ChatFileTree.build(items, collapsed = setOf("test"))
        assertEquals(2, rows.size)
        assertEquals(false, (rows[0] as FileTreeRow.Folder).expanded)
    }
}
