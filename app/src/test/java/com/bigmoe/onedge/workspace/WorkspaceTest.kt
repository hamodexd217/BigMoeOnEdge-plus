package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceTest {
    private fun ws(): Pair<Workspace, File> {
        val root = File.createTempFile("bmoe-ws", "").apply { delete(); mkdirs(); deleteOnExit() }
        return Workspace(root) to root
    }

    @Test
    fun rejectsEscapingPathsAndSymlinkTargets() {
        val (w, root) = ws()
        try {
            assertTrue(w.resolve("safe/file.txt").path.startsWith(root.canonicalPath))
            try { w.resolve("../escape.txt"); throw AssertionError("escape accepted") } catch (_: WorkspaceException) { }
            val outside = File.createTempFile("bmoe-outside", ".txt")
            try {
                val link = File(root, "link.txt")
                try { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) } catch (_: UnsupportedOperationException) { return } catch (_: java.nio.file.FileSystemException) { return }
                try { w.resolve("link.txt").writeText("x"); throw AssertionError("symlink write accepted") } catch (_: Exception) { }
                outside.delete()
            } finally { outside.delete() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun uniquePathAddsNumberBeforeExtension() {
        val (w, root) = ws()
        try {
            w.writeText("a.txt", "a", overwrite = true)
            w.writeText("a (1).txt", "b", overwrite = true)
            assertEquals("a (2).txt", w.uniquePath("a.txt"))
            assertEquals("new.txt", w.uniquePath("new.txt"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun listIsDirectoriesFirstAndWalksFiles() {
        val (w, root) = ws()
        try {
            w.writeText("z.txt", "z", overwrite = true)
            w.writeText("dir/a.kt", "a", overwrite = true)
            val entries = w.list("")
            assertTrue(entries.first().isDirectory)
            assertFalse(w.list("dir").first().isDirectory)
            assertEquals(listOf("dir/a.kt", "z.txt"), w.walkFiles().map(w::relative).sorted())
        } finally { root.deleteRecursively() }
    }
}
