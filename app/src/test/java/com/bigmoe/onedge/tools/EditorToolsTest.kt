package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.Workspace
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class EditorToolsTest {
    private fun root() = File.createTempFile("bmoe-editor", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun edit(action: String, start: Int? = null, end: Int? = null, text: String? = null) =
        LineEdit(action, start, end, text)

    private fun failure(block: () -> Unit): String {
        try { block() } catch (e: ToolInputException) { return e.message.orEmpty() }
        fail("expected ToolInputException")
        return ""
    }

    // ---- LineEdits ----

    @Test
    fun replaceInsertAndDeleteUseTheNumbersOfTheOriginalFile() {
        val src = "a\nb\nc\nd\ne\n"
        assertEquals("a\nB\nc\nd\ne\n", LineEdits.apply(src, listOf(edit("replace", 2, text = "B"))))
        assertEquals("a\nX\nY\nd\ne\n", LineEdits.apply(src, listOf(edit("replace", 2, 3, "X\nY"))))
        assertEquals("a\nb\nc\nd\ne\n", LineEdits.apply(src, listOf(edit("replace", 2, 3, "b\nc"))))
        assertEquals("a\ne\n", LineEdits.apply(src, listOf(edit("delete", 2, 4))))
        assertEquals("a\nb\nNEW\nc\nd\ne\n", LineEdits.apply(src, listOf(edit("insert", 2, text = "NEW"))))
        assertEquals("TOP\na\nb\nc\nd\ne\n", LineEdits.apply(src, listOf(edit("insert", 0, text = "TOP"))))
        assertEquals("a\nb\nc\nd\ne\nEND\n", LineEdits.apply(src, listOf(edit("insert", text = "END"))))
    }

    @Test
    fun severalEditsAreAppliedBottomToTopSoNumbersStayValid() {
        val src = "1\n2\n3\n4\n5\n"
        val out = LineEdits.apply(src, listOf(edit("delete", 5), edit("replace", 2, text = "two"), edit("insert", 0, text = "zero"), edit("delete", 4)))
        assertEquals("zero\n1\ntwo\n3\n", out)
        // two inserts at the same place keep the order they were given in
        assertEquals("1\nA\nB\n2\n3\n4\n5\n", LineEdits.apply(src, listOf(edit("insert", 1, text = "A"), edit("insert", 1, text = "B"))))
    }

    @Test
    fun aFinalLineBreakIsKeptOrAbsentAsInTheOriginal() {
        assertEquals("a\nb", LineEdits.apply("a\nb", listOf(edit("replace", 2, text = "b"))))
        assertEquals("a\nz", LineEdits.apply("a\nb", listOf(edit("replace", 2, text = "z\n")))) // text's own trailing newline is not doubled
        assertEquals("a\nb\nc", LineEdits.apply("a\nb", listOf(edit("insert", text = "c"))))
    }

    @Test
    fun crlfFilesStayCrlf() {
        assertEquals("a\r\nX\r\nc\r\n", LineEdits.apply("a\r\nb\r\nc\r\n", listOf(edit("replace", 2, text = "X"))))
    }

    @Test
    fun badLineNumbersAndOverlapsAreExplained() {
        val src = "a\nb\nc\n"
        assertTrue(failure { LineEdits.apply(src, listOf(edit("replace", 9, text = "x"))) }.contains("outside the file (it has 3 lines)"))
        assertTrue(failure { LineEdits.apply(src, listOf(edit("delete", 3, 2))) }.contains("end_line"))
        assertTrue(failure { LineEdits.apply(src, listOf(edit("replace", 1))) }.contains("needs text"))
        assertTrue(failure { LineEdits.apply(src, listOf(edit("insert", 7, text = "x"))) }.contains("0 to 3"))
        assertTrue(failure { LineEdits.apply(src, listOf(edit("replace", 1, 2, "x"), edit("delete", 2))) }.contains("same lines"))
        assertTrue(failure { LineEdits.apply("", listOf(edit("delete", 1))) }.contains("empty"))
        assertEquals("first", LineEdits.apply("", listOf(edit("insert", text = "first"))))
    }

    @Test
    fun argumentsParseFlatOrAsAList() {
        val flat = LineEdits.parse(JSONObject().put("path", "f").put("start_line", 3).put("end_line", 4).put("text", "x"))
        assertEquals(listOf(LineEdit("replace", 3, 4, "x")), flat)
        val appended = LineEdits.parse(JSONObject().put("action", "insert").put("text", "tail"))
        assertEquals(listOf(LineEdit("insert", null, null, "tail")), appended)
        val list = LineEdits.parse(
            JSONObject().put("edits", JSONArray().put(JSONObject().put("action", "delete").put("start_line", "2")).put(JSONObject().put("action", "insert").put("line", 0).put("new_text", "t")))
        )
        assertEquals(listOf(LineEdit("delete", 2, null, null), LineEdit("insert", 0, null, "t")), list)
        assertTrue(failure { LineEdits.parse(JSONObject().put("path", "f")) }.contains("edits list"))
        assertTrue(failure { LineEdits.parse(JSONObject().put("action", "explode").put("start_line", 1)) }.contains("unknown action"))
    }

    // ---- history ----

    @Test
    fun historyIsLastInFirstOutAndBounded() {
        val h = EditHistory(perFile = 3)
        for (i in 1..5) h.record("f", "v$i")
        assertEquals(3, h.depth("f"))
        assertEquals("v5", h.pop("f"))
        assertEquals("v4", h.pop("f"))
        assertEquals("v3", h.pop("f"))
        assertNull(h.pop("f"))
        val small = EditHistory(perFile = 10, totalChars = 10)
        small.record("a", "123456")
        small.record("b", "123456") // over budget: the oldest state goes
        assertNull(small.peek("a"))
        assertEquals("123456", small.peek("b"))
    }

    // ---- tools on a real workspace ----

    @Test
    fun editLinesEditsAsksWithADiffAndCanBeUndone() = runBlocking {
        val root = root()
        try {
            val ws = Workspace(root)
            val history = EditHistory()
            ws.writeText("src/App.kt", "fun main() {\n    println(\"hi\")\n}\n", overwrite = false)
            val tool = EditLinesTool(ws, null, history)
            val args = JSONObject().put("path", "src/App.kt").put("action", "replace").put("start_line", 2).put("text", "    println(\"hello\")")
            val approval = tool.confirmation(args)
            assertTrue(approval.diff!!.contains("-    println(\"hi\")"))
            assertTrue(approval.diff!!.contains("+    println(\"hello\")"))
            tool.execute(args)
            assertEquals("fun main() {\n    println(\"hello\")\n}\n", ws.readText("src/App.kt"))

            val undo = UndoEditTool(ws, null, history)
            assertTrue(undo.confirmation(JSONObject().put("path", "src/App.kt")).diff!!.contains("println(\"hi\")"))
            undo.execute(JSONObject().put("path", "src/App.kt"))
            assertEquals("fun main() {\n    println(\"hi\")\n}\n", ws.readText("src/App.kt"))
            assertTrue(failure { undo.confirmation(JSONObject().put("path", "src/App.kt")) }.contains("nothing to undo"))
            assertTrue(failure { tool.confirmation(JSONObject().put("path", "src/App.kt").put("action", "replace").put("start_line", 1).put("text", "fun main() {")) }.contains("would not change"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun editFileAndOverwriteAreUndoableToo() = runBlocking {
        val root = root()
        try {
            val ws = Workspace(root)
            val history = EditHistory()
            ws.writeText("a.txt", "one", overwrite = false)
            EditFileTool(ws, null, history).execute(JSONObject().put("path", "a.txt").put("old_text", "one").put("new_text", "two"))
            CreateFileTool(ws, null, history).execute(JSONObject().put("path", "a.txt").put("content", "three").put("overwrite", true))
            assertEquals("three", ws.readText("a.txt"))
            val undo = UndoEditTool(ws, null, history)
            undo.execute(JSONObject().put("path", "a.txt"))
            assertEquals("two", ws.readText("a.txt"))
            undo.execute(JSONObject().put("path", "a.txt"))
            assertEquals("one", ws.readText("a.txt"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun replaceInFilesChangesManyFilesAndShowsOneDiff() = runBlocking {
        val root = root()
        try {
            val ws = Workspace(root)
            val history = EditHistory()
            ws.writeText("a.kt", "fun oldName() {}\nval x = oldName()\n", overwrite = false)
            ws.writeText("sub/b.kt", "oldName()\n", overwrite = false)
            ws.writeText("notes.md", "oldName is documented here\n", overwrite = false)
            ws.writeText("c.kt", "nothing here\n", overwrite = false)
            val tool = ReplaceInFilesTool(ws, null, history)
            val args = JSONObject().put("find", "oldName").put("replace", "newName").put("extensions", "kt")
            val approval = tool.confirmation(args)
            assertEquals("Replace in 2 file(s)?", approval.title)
            assertEquals("3 replacement(s)", approval.message)
            assertTrue(approval.diff!!.contains("+val x = newName()"))
            assertFalse(approval.diff!!.contains("notes.md"))
            tool.execute(args)
            assertEquals("fun newName() {}\nval x = newName()\n", ws.readText("a.kt"))
            assertEquals("newName()\n", ws.readText("sub/b.kt"))
            assertEquals("oldName is documented here\n", ws.readText("notes.md")) // filtered out by extensions
            UndoEditTool(ws, null, history).execute(JSONObject().put("path", "a.kt"))
            assertEquals("fun oldName() {}\nval x = oldName()\n", ws.readText("a.kt"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun replaceInFilesSupportsRegexGroupsAndExplainsProblems() {
        val root = root()
        try {
            val ws = Workspace(root)
            ws.writeText("a.txt", "v1 v22\n", overwrite = false)
            val tool = ReplaceInFilesTool(ws, null, null)
            val d = tool.confirmation(JSONObject().put("find", "v(\\d+)").put("replace", "ver$1").put("regex", true)).diff!!
            assertTrue(d.contains("+ver1 ver22"))
            assertTrue(failure { tool.confirmation(JSONObject().put("find", "zzz").put("replace", "y")) }.contains("no matches"))
            assertTrue(failure { tool.confirmation(JSONObject().put("find", "(").put("replace", "y").put("regex", true)) }.contains("invalid regular expression"))
            assertTrue(failure { tool.confirmation(JSONObject().put("find", "v1").put("replace", "$3").put("regex", true)) }.isNotEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun renameCanCopyFilesAndFolders() = runBlocking {
        val root = root()
        try {
            val ws = Workspace(root)
            ws.writeText("app/a.txt", "A", overwrite = false)
            ws.writeText("app/sub/b.txt", "B", overwrite = false)
            val seen = ArrayList<String>()
            val registry = object : ArtifactRegistry {
                override suspend fun created(ctx: ToolContext, path: String) { seen.add(path) }
                override suspend fun updated(path: String) {}
                override suspend fun renamed(from: String, to: String) {}
                override suspend fun deleted(path: String) {}
            }
            val tool = RenameFileTool(ws, registry)
            tool.execute(JSONObject().put("path", "app").put("new_path", "app2").put("copy", true))
            assertEquals("A", ws.readText("app/a.txt")) // original stays
            assertEquals("B", ws.readText("app2/sub/b.txt"))
            assertEquals(setOf("app2/a.txt", "app2/sub/b.txt"), seen.toSet())
            assertTrue(failure { runBlocking { tool.execute(JSONObject().put("path", "app").put("new_path", "app/inner").put("copy", true)) } }.contains("into itself"))
            assertTrue(failure { runBlocking { tool.execute(JSONObject().put("path", "app").put("new_path", "app2").put("copy", true)) } }.contains("already exists"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun searchCanShowContextLines() = runBlocking {
        val root = root()
        try {
            val ws = Workspace(root)
            ws.writeText("a.txt", "one\ntwo\nthree\nfour\n", overwrite = false)
            val out = SearchFilesTool(ws).execute(JSONObject().put("query", "three").put("context", 1))
            assertTrue(out.contains("a.txt-2- two"))
            assertTrue(out.contains("a.txt:3: three"))
            assertTrue(out.contains("a.txt-4- four"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun allEditorToolsAreRegisteredAndNamed() {
        val manager = ToolManager()
        registerFileTools(manager, Workspace(root()), null)
        for (n in listOf("edit_lines", "replace_in_files", "undo_edit")) {
            assertTrue("$n registered", n in manager.toolNames())
            assertTrue("$n listed", n in FILE_TOOL_NAMES)
        }
        assertEquals(FILE_TOOL_NAMES, manager.toolNames().toSet())
    }
}
