package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.Workspace
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileToolsTest {
    private fun root() = File.createTempFile("bmoe-tools", "").apply { delete(); mkdirs(); deleteOnExit() }

    @Test
    fun createEditRenameDeleteUseWorkspaceAndConfirmation() = runBlocking {
        val root = root()
        try {
            val ws = Workspace(root)
            val create = CreateFileTool(ws, null)
            val args = JSONObject().put("path", "notes.txt").put("content", "hello\nworld")
            create.execute(args)
            assertEquals("hello\nworld", ws.readText("notes.txt"))

            val edit = EditFileTool(ws, null)
            val editArgs = JSONObject().put("path", "notes.txt").put("old_text", "world").put("new_text", "there")
            val approval = edit.confirmation(editArgs)
            assertTrue(approval.diff!!.contains("-world"))
            edit.execute(editArgs)
            assertEquals("hello\nthere", ws.readText("notes.txt"))

            RenameFileTool(ws, null).execute(JSONObject().put("path", "notes.txt").put("new_path", "renamed.txt"))
            val del = DeleteFileTool(ws, null)
            assertTrue(del.confirmation(JSONObject().put("path", "renamed.txt")).destructive)
            del.execute(JSONObject().put("path", "renamed.txt"))
            assertTrue(!ws.exists("renamed.txt"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun editReportsNotFoundAndAmbiguousOldText() {
        assertTrue(runCatching { EditFileTool.applyEdits("abc", listOf(Triple("x", "y", false))) }.exceptionOrNull()?.message!!.contains("not found"))
        assertTrue(runCatching { EditFileTool.applyEdits("aaa aaa", listOf(Triple("aaa", "x", false))) }.exceptionOrNull()?.message!!.contains("appears 2 times"))
    }

    @Test
    fun createFolderIsRegisteredAsAChatFolder() = runBlocking {
        val root = root()
        try {
            val ws = Workspace(root)
            val seen = ArrayList<String>()
            val registry = object : ArtifactRegistry {
                override suspend fun created(ctx: ToolContext, path: String) {}
                override suspend fun updated(path: String) {}
                override suspend fun renamed(from: String, to: String) {}
                override suspend fun deleted(path: String) {}
                override suspend fun folderCreated(ctx: ToolContext, path: String) { seen.add(ctx.sessionId + ":" + path) }
            }
            val out = CreateFolderTool(ws, registry).execute(JSONObject().put("path", "/test/"), ToolContext("chat1", "m1"))
            assertTrue(ws.isDirectory("test"))
            assertEquals(listOf("chat1:test"), seen)
            assertTrue(out.contains("test/"))
            try {
                CreateFolderTool(ws, registry).execute(JSONObject().put("path", "  "), ToolContext("chat1", "m1"))
                org.junit.Assert.fail("an empty folder name must be refused")
            } catch (_: ToolInputException) {
            }
        } finally { root.deleteRecursively() }
    }
}
