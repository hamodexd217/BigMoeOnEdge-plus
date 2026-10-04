package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.DiffUtil
import com.bigmoe.onedge.workspace.FileKinds
import com.bigmoe.onedge.workspace.TextSearch
import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceException
import org.json.JSONArray
import org.json.JSONObject

/** Keeps the chat's list of AI-made files in sync with what the file tools do. */
interface ArtifactRegistry {
    suspend fun created(ctx: ToolContext, path: String)
    suspend fun updated(path: String)
    suspend fun renamed(from: String, to: String)
    suspend fun deleted(path: String)

    /** A folder the AI made; it is listed in the chat's files like a file is. */
    suspend fun folderCreated(ctx: ToolContext, path: String) {}
}

internal fun JSONObject.requireString(key: String): String {
    val v = opt(key)
    if (v == null || v == JSONObject.NULL) throw ToolInputException("missing required argument \"$key\"")
    val s = v.toString()
    return s
}

internal fun JSONObject.optBoolLenient(key: String, default: Boolean): Boolean = when (val v = opt(key)) {
    is Boolean -> v
    is String -> v.equals("true", true) || v == "1" || v.equals("yes", true)
    is Number -> v.toInt() != 0
    else -> default
}

internal fun JSONObject.optIntLenient(key: String): Int? = when (val v = opt(key)) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}

private inline fun <T> wsCall(block: () -> T): T = try {
    block()
} catch (e: WorkspaceException) {
    throw ToolInputException(e.message ?: "file error")
}

private fun kb(bytes: Long) = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

class ListFilesTool(private val ws: Workspace) : Tool {
    override val name = "list_files"
    override val category = ToolCategory.FILES
    override val description = "List files and folders in the workspace. Use recursive=true for the whole tree."
    override val parametersSchema = toolSchema(
        listOf(Triple("path", "string", "Folder, relative to the workspace root (default: the root)"), Triple("recursive", "boolean", "Include sub-folders")),
        emptyList()
    )
    override fun describe(arguments: JSONObject) = "Listing ${arguments.optString("path", "").ifBlank { "workspace" }}"
    override suspend fun execute(arguments: JSONObject): String = wsCall {
        val path = arguments.optString("path", "")
        val entries = ws.list(path, arguments.optBoolLenient("recursive", false))
        if (entries.isEmpty()) "The folder is empty."
        else entries.joinToString("\n") { if (it.isDirectory) "${it.path}/" else "${it.path}  (${kb(it.sizeBytes)})" }
    }
}

class ReadFileTool(private val ws: Workspace) : Tool {
    override val name = "read_file"
    override val category = ToolCategory.FILES
    override val description = "Read a text file. Lines are numbered (the numbers are not part of the file). Long files: pass start_line/end_line."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("path", "string", "File path relative to the workspace root"),
            Triple("start_line", "integer", "First line to read (1-based)"),
            Triple("end_line", "integer", "Last line to read")
        ),
        listOf("path")
    )
    override fun describe(arguments: JSONObject) = "Reading ${arguments.optString("path")}"
    override suspend fun execute(arguments: JSONObject): String = wsCall {
        val path = arguments.requireString("path")
        val lines = ws.readText(path, 2_000_000).split("\n")
        val start = (arguments.optIntLenient("start_line") ?: 1).coerceIn(1, maxOf(1, lines.size))
        val requestedEnd = (arguments.optIntLenient("end_line") ?: lines.size).coerceIn(start, lines.size)
        val sb = StringBuilder()
        var last = start - 1
        for (i in start..requestedEnd) {
            val row = String.format(java.util.Locale.US, "%4d| %s\n", i, lines[i - 1])
            if (sb.length + row.length > MAX_CHARS && last >= start) break
            sb.append(row)
            last = i
        }
        val header = "${ws.normalize(path)} — lines $start-$last of ${lines.size}\n"
        val footer = if (last < requestedEnd) "[stopped at line $last to keep the answer short; call read_file again with start_line=${last + 1}]" else ""
        header + sb.toString().trimEnd('\n') + (if (footer.isEmpty()) "" else "\n$footer")
    }

    companion object { const val MAX_CHARS = 5000 }
}

class SearchFilesTool(private val ws: Workspace) : Tool {
    override val name = "search_files"
    override val category = ToolCategory.FILES
    override val description = "Search text inside the workspace files (case-insensitive unless case_sensitive=true)."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("query", "string", "Text or regular expression to find"),
            Triple("path", "string", "Folder or file to search (default: everything)"),
            Triple("regex", "boolean", "Treat query as a regular expression"),
            Triple("case_sensitive", "boolean", "Match case")
        ),
        listOf("query")
    )
    override fun describe(arguments: JSONObject) = "Searching files for \"${arguments.optString("query").take(40)}\""
    override suspend fun execute(arguments: JSONObject): String = wsCall {
        val q = arguments.requireString("query")
        try {
            TextSearch.format(
                TextSearch.search(
                    ws, q, arguments.optString("path", ""), arguments.optBoolLenient("regex", false),
                    arguments.optBoolLenient("case_sensitive", false)
                ), q
            )
        } catch (e: IllegalArgumentException) {
            throw ToolInputException(e.message ?: "bad query")
        }
    }
}

class CreateFileTool(private val ws: Workspace, private val registry: ArtifactRegistry?) : Tool {
    override val name = "create_file"
    override val category = ToolCategory.FILES
    override val description = "Create a file with the given content (documents, code, pages, configs). The user sees it as a separate file next to the chat. " +
        "To change an existing file use edit_file."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("path", "string", "File path relative to the workspace, e.g. notes/todo.md"),
            Triple("content", "string", "Complete file content"),
            Triple("overwrite", "boolean", "Replace the file if it exists (asks the user)")
        ),
        listOf("path", "content")
    )
    override fun describe(arguments: JSONObject) = "Creating ${arguments.optString("path")}"

    override fun confirmation(arguments: JSONObject): ConfirmationRequest? {
        val path = arguments.requireString("path")
        arguments.requireString("content")
        val exists = try { ws.exists(path) } catch (e: WorkspaceException) { throw ToolInputException(e.message ?: "bad path") }
        if (!exists) return null
        if (ws.isDirectory(path)) throw ToolInputException("$path is a folder")
        if (!arguments.optBoolLenient("overwrite", false)) {
            throw ToolInputException("$path already exists. Use edit_file to change it, or pass overwrite=true to replace it.")
        }
        val old = try { ws.readText(path) } catch (_: WorkspaceException) { "" }
        return ConfirmationRequest(
            title = "Replace ${ws.normalize(path)}?",
            message = "The AI wants to overwrite this file.",
            diff = DiffUtil.unified(old, arguments.requireString("content")).ifEmpty { "(no changes)" },
            destructive = true
        )
    }

    override suspend fun execute(arguments: JSONObject): String = execute(arguments, ToolContext.NONE)

    override suspend fun execute(arguments: JSONObject, ctx: ToolContext): String {
        val path = arguments.requireString("path")
        val content = arguments.requireString("content")
        if (content.length > MAX_CHARS) throw ToolInputException("content is too long (${content.length} chars, limit $MAX_CHARS); split it into several files")
        val existed = wsCall { if (ws.exists(path)) "y" else "" }.isNotEmpty()
        wsCall { ws.writeText(path, content, overwrite = arguments.optBoolLenient("overwrite", false)); "" }
        val norm = ws.normalize(path)
        if (existed) registry?.updated(norm) else registry?.created(ctx, norm)
        return "${if (existed) "Replaced" else "Created"} $norm (${content.split("\n").size} lines, ${content.length} characters). The user can open it from the chat's files."
    }

    companion object { const val MAX_CHARS = 200_000 }
}

class EditFileTool(private val ws: Workspace, private val registry: ArtifactRegistry?) : Tool {
    override val name = "edit_file"
    override val category = ToolCategory.FILES
    override val description = "Edit a file by replacing exact text. old_text must match the file exactly (copy it from read_file) and be unique unless replace_all=true. " +
        "The user sees a diff and must approve. Several changes: pass edits=[{old_text,new_text},...]."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("path", "string", "File path relative to the workspace"),
            Triple("old_text", "string", "Exact text to replace"),
            Triple("new_text", "string", "Replacement text (empty to delete)"),
            Triple("replace_all", "boolean", "Replace every occurrence"),
            Triple("edits", "array", "Alternative: a list of {old_text, new_text, replace_all} objects, applied in order")
        ),
        listOf("path")
    )
    override fun describe(arguments: JSONObject) = "Editing ${arguments.optString("path")}"

    private data class Edit(val old: String, val new: String, val all: Boolean)

    private fun parseEdits(arguments: JSONObject): List<Edit> {
        val out = ArrayList<Edit>()
        val arr: JSONArray? = arguments.optJSONArray("edits")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: throw ToolInputException("edits[$i] is not an object")
                out.add(Edit(o.requireString("old_text"), o.optString("new_text", ""), o.optBoolLenient("replace_all", false)))
            }
        } else if (arguments.has("old_text")) {
            out.add(Edit(arguments.requireString("old_text"), arguments.optString("new_text", ""), arguments.optBoolLenient("replace_all", false)))
        }
        if (out.isEmpty()) throw ToolInputException("give old_text and new_text, or an edits list")
        return out
    }

    private fun compute(arguments: JSONObject): Pair<String, String> {
        val path = arguments.requireString("path")
        val original = wsCall { ws.readText(path) }
        return original to applyEdits(original, parseEdits(arguments).map { Triple(it.old, it.new, it.all) })
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
        val (_, new) = compute(arguments) // recomputed: the file may have changed while the dialog was open
        wsCall { ws.writeText(path, new, overwrite = true); "" }
        registry?.updated(ws.normalize(path))
        return "Edited ${ws.normalize(path)} (${new.split("\n").size} lines now)."
    }

    companion object {
        /** Applies (old, new, replaceAll) edits in order; every old text must be found (and unique unless replaceAll). */
        fun applyEdits(original: String, edits: List<Triple<String, String, Boolean>>): String {
            var text = original
            for ((i, e) in edits.withIndex()) {
                val (old, new, all) = e
                if (old.isEmpty()) throw ToolInputException("edit #${i + 1}: old_text is empty")
                val count = countOccurrences(text, old)
                if (count == 0) {
                    throw ToolInputException(
                        "edit #${i + 1}: old_text was not found. It must match the file exactly, including spaces and line breaks. " +
                            "Use read_file and copy the text."
                    )
                }
                if (count > 1 && !all) {
                    throw ToolInputException("edit #${i + 1}: old_text appears $count times. Add more surrounding lines to make it unique, or set replace_all=true.")
                }
                text = if (all) text.replace(old, new) else text.replaceFirst(old, new)
            }
            return text
        }

        fun countOccurrences(text: String, sub: String): Int {
            var n = 0
            var i = text.indexOf(sub)
            while (i >= 0) { n++; i = text.indexOf(sub, i + sub.length) }
            return n
        }
    }
}

class RenameFileTool(private val ws: Workspace, private val registry: ArtifactRegistry?) : Tool {
    override val name = "rename_file"
    override val category = ToolCategory.FILES
    override val description = "Rename or move a file or folder inside the workspace. The target must not exist."
    override val parametersSchema = toolSchema(
        listOf(Triple("path", "string", "Current path"), Triple("new_path", "string", "New path")),
        listOf("path", "new_path")
    )
    override fun describe(arguments: JSONObject) = "Renaming ${arguments.optString("path")}"
    override suspend fun execute(arguments: JSONObject): String {
        val from = arguments.requireString("path")
        val to = arguments.requireString("new_path")
        wsCall { ws.rename(from, to); "" }
        registry?.renamed(ws.normalize(from), ws.normalize(to))
        return "Renamed ${ws.normalize(from)} to ${ws.normalize(to)}."
    }
}

class DeleteFileTool(private val ws: Workspace, private val registry: ArtifactRegistry?) : Tool {
    override val name = "delete_file"
    override val category = ToolCategory.FILES
    override val description = "Delete a file or a folder (with everything in it). The user must approve."
    override val parametersSchema = toolSchema(listOf(Triple("path", "string", "Path to delete")), listOf("path"))
    override fun describe(arguments: JSONObject) = "Deleting ${arguments.optString("path")}"

    override fun confirmation(arguments: JSONObject): ConfirmationRequest {
        val path = arguments.requireString("path")
        val norm = ws.normalize(path)
        val f = try { ws.resolve(path) } catch (e: WorkspaceException) { throw ToolInputException(e.message ?: "bad path") }
        if (!f.exists()) throw ToolInputException("No such file or folder: $norm")
        val what = if (f.isDirectory) "the folder $norm and ${ws.walkFiles(norm).size} file(s) in it" else "the file $norm"
        return ConfirmationRequest("Delete $norm?", "The AI wants to permanently delete $what.", null, destructive = true)
    }

    override suspend fun execute(arguments: JSONObject): String {
        val path = arguments.requireString("path")
        wsCall { ws.delete(path); "" }
        registry?.deleted(ws.normalize(path))
        return "Deleted ${ws.normalize(path)}."
    }
}

class CreateFolderTool(private val ws: Workspace, private val registry: ArtifactRegistry? = null) : Tool {
    override val name = "create_folder"
    override val category = ToolCategory.FILES
    override val description = "Create a folder (and missing parent folders). It appears in this chat's files, where the user sees it and the files inside it."
    override val parametersSchema = toolSchema(listOf(Triple("path", "string", "Folder path")), listOf("path"))
    override fun describe(arguments: JSONObject) = "Creating folder ${arguments.optString("path")}"

    override suspend fun execute(arguments: JSONObject): String = execute(arguments, ToolContext.NONE)

    override suspend fun execute(arguments: JSONObject, ctx: ToolContext): String {
        val path = arguments.requireString("path")
        wsCall { ws.mkdir(path); "" }
        val norm = ws.normalize(path)
        if (norm.isEmpty()) throw ToolInputException("give a folder name")
        registry?.folderCreated(ctx, norm)
        return "Created folder $norm/. The user can see it in the chat's files."
    }
}


/** Builds a whole multi-file project in one call (e.g. index.html + style.css + app.js). */
class CreateProjectTool(private val ws: Workspace, private val registry: ArtifactRegistry?) : Tool {
    override val name = "create_project"
    override val category = ToolCategory.FILES
    override val description = "Create several files at once (a small project). Fails without changing anything if one of the files already exists."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("folder", "string", "Optional folder to put all files in"),
            Triple("files", "array", "List of {path, content} objects")
        ),
        listOf("files")
    )
    override fun describe(arguments: JSONObject) = "Building project (${arguments.optJSONArray("files")?.length() ?: 0} files)"

    private fun parse(arguments: JSONObject): List<Pair<String, String>> {
        val arr = arguments.optJSONArray("files") ?: throw ToolInputException("missing required argument \"files\" (a list of {path, content})")
        if (arr.length() == 0) throw ToolInputException("\"files\" is empty")
        if (arr.length() > MAX_FILES) throw ToolInputException("too many files (${arr.length()}, limit $MAX_FILES)")
        val folder = arguments.optString("folder", "").let { ws.normalize(it) }
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw ToolInputException("files[$i] is not an object")
            val p = ws.normalize(o.requireString("path"))
            val content = o.requireString("content")
            if (content.length > CreateFileTool.MAX_CHARS) throw ToolInputException("files[$i] is too long")
            out.add((if (folder.isEmpty()) p else "$folder/$p") to content)
        }
        if (out.map { it.first }.toSet().size != out.size) throw ToolInputException("two files have the same path")
        return out
    }

    override fun confirmation(arguments: JSONObject): ConfirmationRequest? {
        val files = parse(arguments)
        val existing = files.filter { try { ws.exists(it.first) } catch (e: WorkspaceException) { throw ToolInputException(e.message ?: "bad path") } }
        if (existing.isNotEmpty()) throw ToolInputException("already exist: ${existing.joinToString { it.first }}. Use edit_file, or create_file with overwrite=true.")
        return null
    }

    override suspend fun execute(arguments: JSONObject): String = execute(arguments, ToolContext.NONE)

    override suspend fun execute(arguments: JSONObject, ctx: ToolContext): String {
        val files = parse(arguments)
        wsCall {
            for ((p, c) in files) ws.writeText(p, c, overwrite = false)
            ""
        }
        for ((p, _) in files) registry?.created(ctx, p)
        return "Created ${files.size} files:\n" + files.joinToString("\n") { "- ${it.first} (${it.second.split("\n").size} lines)" }
    }

    companion object { const val MAX_FILES = 40 }
}

/** Packs a file or folder into a .zip the user can download or share. */
class ExportZipTool(private val ws: Workspace, private val registry: ArtifactRegistry?) : Tool {
    override val name = "export_zip"
    override val category = ToolCategory.FILES
    override val description = "Pack a folder (or file) into a .zip archive under exports/, so the user can download it."
    override val parametersSchema = toolSchema(
        listOf(Triple("path", "string", "Folder or file to pack"), Triple("name", "string", "Archive name without extension")),
        listOf("path")
    )
    override fun describe(arguments: JSONObject) = "Zipping ${arguments.optString("path")}"

    override suspend fun execute(arguments: JSONObject): String = execute(arguments, ToolContext.NONE)

    override suspend fun execute(arguments: JSONObject, ctx: ToolContext): String {
        val path = arguments.requireString("path")
        val src = wsCall { ws.resolve(path).let { f -> if (!f.exists()) throw WorkspaceException("No such file or folder: ${ws.normalize(path)}"); f } }
        val files = ws.walkFiles(ws.normalize(path), maxFiles = 500)
        if (files.isEmpty()) throw ToolInputException("nothing to pack in ${ws.normalize(path)}")
        val base = FileKinds.safeName(arguments.optString("name", "").ifBlank { src.name.ifBlank { "workspace" } }, "archive")
        val outRel = ws.uniquePath("exports/$base.zip")
        val out = wsCall { ws.resolve(outRel).also { it.parentFile?.mkdirs() }; "" }.let { ws.resolve(outRel) }
        val root = if (src.isDirectory) src else src.parentFile ?: ws.rootDir
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in files) {
                val entry = f.relativeTo(root).path.replace(java.io.File.separatorChar, '/')
                zip.putNextEntry(java.util.zip.ZipEntry(entry))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        registry?.created(ctx, outRel)
        return "Created $outRel (${files.size} files, ${kb(out.length())}). The user can download it from the chat's files."
    }
}

fun registerFileTools(manager: ToolManager, ws: Workspace, registry: ArtifactRegistry?) {
    manager.registerTool(ListFilesTool(ws))
    manager.registerTool(ReadFileTool(ws))
    manager.registerTool(SearchFilesTool(ws))
    manager.registerTool(CreateFileTool(ws, registry))
    manager.registerTool(EditFileTool(ws, registry))
    manager.registerTool(RenameFileTool(ws, registry))
    manager.registerTool(DeleteFileTool(ws, registry))
    manager.registerTool(CreateFolderTool(ws, registry))
    manager.registerTool(CreateProjectTool(ws, registry))
    manager.registerTool(ExportZipTool(ws, registry))
}

val FILE_TOOL_NAMES = setOf("list_files", "read_file", "search_files", "create_file", "edit_file", "rename_file", "delete_file", "create_folder", "create_project", "export_zip")
