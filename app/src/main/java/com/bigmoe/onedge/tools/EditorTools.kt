package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.DiffUtil
import com.bigmoe.onedge.workspace.FileKinds
import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceException
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Remembers what a file looked like before each AI edit so `undo_edit` can bring it back, like an editor's undo.
 * Memory only: it is gone when the app is closed. Bounded per file and in total.
 */
class EditHistory(private val perFile: Int = 10, private val totalChars: Long = 4_000_000L) {
    private val stacks = LinkedHashMap<String, ArrayDeque<String>>()
    private var chars = 0L

    @Synchronized
    fun record(path: String, before: String) {
        val stack = stacks.getOrPut(path) { ArrayDeque() }
        stack.addLast(before)
        chars += before.length
        while (stack.size > perFile) chars -= stack.removeFirst().length
        // Over the total budget: forget the oldest states of the files that were touched longest ago.
        if (chars > totalChars) {
            val it = stacks.entries.iterator()
            while (chars > totalChars && it.hasNext()) {
                val e = it.next()
                while (chars > totalChars && e.value.isNotEmpty()) chars -= e.value.removeFirst().length
                if (e.value.isEmpty()) it.remove()
            }
        }
    }

    @Synchronized
    fun peek(path: String): String? = stacks[path]?.lastOrNull()

    @Synchronized
    fun pop(path: String): String? {
        val stack = stacks[path] ?: return null
        val s = if (stack.isEmpty()) null else stack.removeLast()
        if (s != null) chars -= s.length
        if (stack.isEmpty()) stacks.remove(path)
        return s
    }

    @Synchronized
    fun depth(path: String): Int = stacks[path]?.size ?: 0
}

/** One change to a file, addressed by line numbers as shown by `read_file`. */
data class LineEdit(val action: String, val start: Int?, val end: Int?, val text: String?)

/** Line-number based edits. Pure functions, unit-tested. */
object LineEdits {
    val ACTIONS = setOf("replace", "insert", "delete")

    /** Reads `edits: [...]` or a single edit given as flat arguments (action, start_line, end_line, text). */
    fun parse(arguments: JSONObject): List<LineEdit> {
        val out = ArrayList<LineEdit>()
        val arr: JSONArray? = arguments.optJSONArray("edits")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: throw ToolInputException("edits[$i] is not an object")
                out.add(parseOne(o, "edit #${i + 1}"))
            }
        } else if (arguments.has("action") || arguments.has("start_line") || arguments.has("line")) {
            out.add(parseOne(arguments, "the edit"))
        }
        if (out.isEmpty()) throw ToolInputException("give action, start_line (and end_line / text), or an edits list")
        return out
    }

    private fun parseOne(o: JSONObject, label: String): LineEdit {
        val start = o.optIntLenient("start_line") ?: o.optIntLenient("line")
        val end = o.optIntLenient("end_line")
        val text = when {
            o.has("text") && !o.isNull("text") -> o.get("text").toString()
            o.has("new_text") && !o.isNull("new_text") -> o.get("new_text").toString()
            o.has("content") && !o.isNull("content") -> o.get("content").toString()
            else -> null
        }
        val action = o.optString("action", "").trim().lowercase().ifEmpty {
            when {
                text != null && start != null -> "replace"
                text != null -> "insert"
                else -> throw ToolInputException("$label: give an action (replace, insert or delete)")
            }
        }
        if (action !in ACTIONS) throw ToolInputException("$label: unknown action \"$action\"; use replace, insert or delete")
        return LineEdit(action, start, end, text)
    }

    private fun textLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.removeSuffix("\n").split("\n")
    }

    /**
     * Applies [edits] to [original]. Line numbers all refer to the ORIGINAL text (what read_file showed), so several
     * edits can be given in any order; overlapping ones are refused. A final line break is kept as it was.
     *  - replace: lines start_line..end_line (end defaults to start) become `text` (empty text removes them)
     *  - delete:  lines start_line..end_line are removed
     *  - insert:  `text` goes after line start_line (0 = top of the file; missing = end of the file)
     */
    fun apply(original: String, edits: List<LineEdit>): String {
        val crlf = original.contains("\r\n")
        val lines = if (original.isEmpty()) ArrayList<String>() else ArrayList(original.split("\n"))
        val trailingNewline = lines.size > 1 && lines.last().isEmpty()
        if (trailingNewline) lines.removeAt(lines.lastIndex)
        val n = lines.size

        class Op(val from: Int, val to: Int, val repl: List<String>)
        val ops = ArrayList<Op>()
        for ((i, e) in edits.withIndex()) {
            val label = "edit #${i + 1}"
            fun fixEnding(l: List<String>) = if (crlf) l.map { it.removeSuffix("\r") + "\r" } else l
            when (e.action) {
                "replace", "delete" -> {
                    val s = e.start ?: throw ToolInputException("$label: start_line is required")
                    val en = e.end ?: s
                    if (n == 0) throw ToolInputException("$label: the file is empty; use insert")
                    if (s < 1 || s > n) throw ToolInputException("$label: start_line $s is outside the file (it has $n lines)")
                    if (en < s || en > n) throw ToolInputException("$label: end_line $en must be between start_line ($s) and $n")
                    val repl = if (e.action == "delete") emptyList() else {
                        val t = e.text ?: throw ToolInputException("$label: replace needs text (use delete to remove lines)")
                        fixEnding(textLines(t))
                    }
                    ops.add(Op(s - 1, en, repl))
                }
                else -> {
                    val after = e.start ?: n
                    if (after < 0 || after > n) throw ToolInputException("$label: start_line $after is outside the file (insert after a line from 0 to $n)")
                    val t = e.text ?: throw ToolInputException("$label: insert needs text")
                    ops.add(Op(after, after, fixEnding(textLines(t))))
                }
            }
        }
        val byPosition = ops.withIndex().sortedWith(compareBy<IndexedValue<Op>>({ it.value.from }, { it.index }))
        for (k in 1 until byPosition.size) {
            val prev = byPosition[k - 1].value
            val cur = byPosition[k].value
            if (prev.to > cur.from) throw ToolInputException("two edits touch the same lines (around line ${cur.from + 1}); merge them into one")
        }
        // Bottom to top, so earlier line numbers stay valid; equal positions keep their given order.
        for (iv in byPosition.sortedWith(compareByDescending<IndexedValue<Op>> { it.value.from }.thenByDescending { it.index })) {
            val op = iv.value
            if (op.to > op.from) lines.subList(op.from, op.to).clear()
            lines.addAll(op.from, op.repl)
        }
        val body = lines.joinToString("\n")
        return if (trailingNewline && lines.isNotEmpty()) body + "\n" else body
    }
}

private inline fun <T> editWs(block: () -> T): T = try {
    block()
} catch (e: WorkspaceException) {
    throw ToolInputException(e.message ?: "file error")
}

/** Edit by line number, like an editor: replace, insert or delete lines. */
class EditLinesTool(
    private val ws: Workspace,
    private val registry: ArtifactRegistry?,
    private val history: EditHistory?
) : Tool {
    override val name = "edit_lines"
    override val category = ToolCategory.FILES
    override val description = "Edit a file by line numbers from read_file: replace lines start_line..end_line with text, insert text after start_line (0 = top, none = end), or delete lines. " +
        "Several changes: edits=[{action,start_line,end_line,text}], all numbered as in the file you read. The user approves a diff."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("path", "string", ""),
            Triple("action", "string", "replace | insert | delete"),
            Triple("start_line", "integer", "1-based"),
            Triple("end_line", "integer", "Last line (default start_line)"),
            Triple("text", "string", "New text (not needed for delete)"),
            Triple("edits", "array", "[{action,start_line,end_line,text}]")
        ),
        listOf("path")
    )
    override fun describe(arguments: JSONObject) = "Editing ${arguments.optString("path")}"

    private fun compute(arguments: JSONObject): Pair<String, String> {
        val path = arguments.requireString("path")
        val original = editWs { ws.readText(path) }
        return original to LineEdits.apply(original, LineEdits.parse(arguments))
    }

    override fun confirmation(arguments: JSONObject): ConfirmationRequest {
        val (old, new) = compute(arguments)
        val diff = DiffUtil.unified(old, new)
        if (diff.isEmpty()) throw ToolInputException("the edit would not change the file")
        val st = DiffUtil.stats(DiffUtil.diffLines(DiffUtil.splitLines(old), DiffUtil.splitLines(new)))
        return ConfirmationRequest(
            title = "Edit ${ws.normalize(arguments.requireString("path"))}?",
            message = "+${st.added} / −${st.removed} lines",
            diff = diff,
            destructive = false
        )
    }

    override suspend fun execute(arguments: JSONObject): String {
        val path = arguments.requireString("path")
        val (old, new) = compute(arguments) // recomputed: the file may have changed while the dialog was open
        if (new == old) throw ToolInputException("the edit would not change the file")
        editWs { ws.writeText(path, new, overwrite = true) }
        history?.record(ws.normalize(path), old)
        registry?.updated(ws.normalize(path))
        return "Edited ${ws.normalize(path)} (${new.split("\n").size} lines now). Line numbers below the changes have moved: read_file again before the next edit_lines."
    }
}

/** Project-wide find and replace with one diff for the user to approve. */
class ReplaceInFilesTool(
    private val ws: Workspace,
    private val registry: ArtifactRegistry?,
    private val history: EditHistory?
) : Tool {
    override val name = "replace_in_files"
    override val category = ToolCategory.FILES
    override val description = "Find and replace text in many files at once (rename a function, change a value). regex=true allows \$1 groups. The user approves the diff."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("find", "string", "Text or regex"),
            Triple("replace", "string", "Replacement (empty deletes)"),
            Triple("path", "string", "Folder or file (default: all)"),
            Triple("regex", "boolean", ""),
            Triple("case_sensitive", "boolean", "Default true"),
            Triple("extensions", "string", "Only these, e.g. kt,java")
        ),
        listOf("find", "replace")
    )
    override fun describe(arguments: JSONObject) = "Replacing \"${arguments.optString("find").take(30)}\" in files"

    class Change(val path: String, val old: String, val new: String, val count: Int)

    private fun compute(arguments: JSONObject): List<Change> {
        val find = arguments.requireString("find")
        if (find.isEmpty()) throw ToolInputException("find is empty")
        val replace = arguments.requireString("replace")
        val regex = arguments.optBoolLenient("regex", false)
        val flags = if (arguments.optBoolLenient("case_sensitive", true)) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val pattern = try {
            if (regex) Pattern.compile(find, flags) else Pattern.compile(Pattern.quote(find), flags)
        } catch (e: PatternSyntaxException) {
            throw ToolInputException("invalid regular expression: ${e.description}")
        }
        val replacement = if (regex) replace else java.util.regex.Matcher.quoteReplacement(replace)
        val exts = arguments.optString("extensions", "").split(',', ' ').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
        val changes = ArrayList<Change>()
        for (f in editWs { ws.walkFiles(arguments.optString("path", ""), maxFiles = 1000) }) {
            if (f.length() > MAX_FILE_BYTES) continue
            if (exts.isNotEmpty() && FileKinds.extension(f.name) !in exts) continue
            val bytes = try { f.readBytes() } catch (_: Exception) { continue }
            if (FileKinds.looksBinary(bytes)) continue
            val text = FileKinds.decode(bytes)
            val m = pattern.matcher(text)
            var count = 0
            while (m.find()) count++
            if (count == 0) continue
            val new = try {
                pattern.matcher(text).replaceAll(replacement)
            } catch (e: IndexOutOfBoundsException) {
                throw ToolInputException("the replacement refers to a group the pattern does not have")
            } catch (e: IllegalArgumentException) {
                throw ToolInputException("bad replacement: ${e.message}")
            }
            if (new != text) changes.add(Change(ws.relative(f), text, new, count))
            if (changes.size > MAX_FILES) throw ToolInputException("more than $MAX_FILES files would change; narrow it with path or extensions")
        }
        if (changes.isEmpty()) throw ToolInputException("nothing to replace: no matches for \"${find.take(60)}\"")
        return changes
    }

    override fun confirmation(arguments: JSONObject): ConfirmationRequest {
        val changes = compute(arguments)
        val diff = StringBuilder()
        for (c in changes) {
            val d = DiffUtil.unified(c.old, c.new, context = 1)
            if (diff.length + d.length > MAX_DIFF_CHARS) { diff.append("\n… ${changes.size - changes.indexOf(c)} more file(s) not shown"); break }
            diff.append("— ").append(c.path).append(" (").append(c.count).append(")\n").append(d).append("\n")
        }
        return ConfirmationRequest(
            title = "Replace in ${changes.size} file(s)?",
            message = "${changes.sumOf { it.count }} replacement(s)",
            diff = diff.toString().trimEnd(),
            destructive = false
        )
    }

    override suspend fun execute(arguments: JSONObject): String {
        val changes = compute(arguments) // recomputed: files may have changed while the dialog was open
        for (c in changes) {
            editWs { ws.writeText(c.path, c.new, overwrite = true) }
            history?.record(ws.normalize(c.path), c.old)
            registry?.updated(ws.normalize(c.path))
        }
        return "Replaced ${changes.sumOf { it.count }} occurrence(s) in ${changes.size} file(s):\n" +
            changes.joinToString("\n") { "- ${it.path} (${it.count})" }
    }

    private companion object {
        const val MAX_FILE_BYTES = 1_000_000
        const val MAX_FILES = 50
        const val MAX_DIFF_CHARS = 12_000
    }
}

/** Puts a file back the way it was before the AI's last edit. */
class UndoEditTool(
    private val ws: Workspace,
    private val registry: ArtifactRegistry?,
    private val history: EditHistory
) : Tool {
    override val name = "undo_edit"
    override val category = ToolCategory.FILES
    override val description = "Undo the last edit_file / edit_lines / replace_in_files / overwrite of a file (repeat for earlier ones). Only edits since the app started are remembered."
    override val parametersSchema = toolSchema(listOf(Triple("path", "string", "")), listOf("path"))
    override fun describe(arguments: JSONObject) = "Undoing the last edit of ${arguments.optString("path")}"

    override fun confirmation(arguments: JSONObject): ConfirmationRequest {
        val path = ws.normalize(arguments.requireString("path"))
        val before = history.peek(path) ?: throw ToolInputException("nothing to undo for $path (undo only remembers edits made since the app started)")
        val now = try { ws.readText(path) } catch (_: WorkspaceException) { "" }
        return ConfirmationRequest(
            title = "Undo the last edit of $path?",
            message = "The file goes back to how it was before that edit.",
            diff = DiffUtil.unified(now, before).ifEmpty { "(no changes)" },
            destructive = false
        )
    }

    override suspend fun execute(arguments: JSONObject): String {
        val path = ws.normalize(arguments.requireString("path"))
        val before = history.pop(path) ?: throw ToolInputException("nothing to undo for $path")
        editWs { ws.writeText(path, before, overwrite = true) }
        registry?.updated(path)
        return "Restored $path to its state before the last edit (${history.depth(path)} older state(s) remain)."
    }
}
