package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.CodeAnalysis
import com.bigmoe.onedge.workspace.TextSearch
import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceException
import org.json.JSONObject

class SearchCodeTool(private val ws: Workspace) : Tool {
    override val name = "search_code"
    override val category = ToolCategory.CODE
    override val description = "Search source code files (not prose or data) with surrounding lines. Supports regular expressions and extension filters."
    override val parametersSchema = toolSchema(
        listOf(
            Triple("query", "string", "Text or regular expression"),
            Triple("path", "string", "Folder or file (default: everything)"),
            Triple("regex", "boolean", "Treat query as a regular expression"),
            Triple("extensions", "string", "Comma separated, e.g. kt,java"),
            Triple("context_lines", "integer", "Lines of context around each match (0-5)")
        ),
        listOf("query")
    )
    override fun describe(arguments: JSONObject) = "Searching code for \"${arguments.optString("query").take(40)}\""
    override suspend fun execute(arguments: JSONObject): String {
        val q = arguments.requireString("query")
        val exts = arguments.optString("extensions", "").split(',').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
        return try {
            TextSearch.format(
                TextSearch.search(
                    ws, q, arguments.optString("path", ""), arguments.optBoolLenient("regex", false), false,
                    codeOnly = true, extensions = exts, contextLines = (arguments.optIntLenient("context_lines") ?: 1).coerceIn(0, 5)
                ), q
            )
        } catch (e: IllegalArgumentException) {
            throw ToolInputException(e.message ?: "bad query")
        } catch (e: WorkspaceException) {
            throw ToolInputException(e.message ?: "bad path")
        }
    }
}

class AnalyzeCodeTool(private val ws: Workspace) : Tool {
    override val name = "analyze_code"
    override val category = ToolCategory.CODE
    override val description = "Static summary of a source file: size, functions and classes with line numbers, imports, TODOs, bracket balance."
    override val parametersSchema = toolSchema(listOf(Triple("path", "string", "File path")), listOf("path"))
    override fun describe(arguments: JSONObject) = "Analyzing ${arguments.optString("path")}"
    override suspend fun execute(arguments: JSONObject): String {
        val path = arguments.requireString("path")
        val text = try { ws.readText(path, 2_000_000) } catch (e: WorkspaceException) { throw ToolInputException(e.message ?: "bad path") }
        return CodeAnalysis.analyze(ws.normalize(path), text)
    }
}

/** Runs JavaScript somewhere safe and returns what it printed. The Android implementation uses an offline WebView. */
fun interface JavaScriptRunner {
    suspend fun run(code: String, timeoutMs: Long): String
}

class RunJavaScriptTool(private val runner: JavaScriptRunner) : Tool {
    override val name = "run_javascript"
    override val category = ToolCategory.CODE
    override val description = "Run JavaScript in an isolated sandbox with no network and no file access. console.log output and the value of the last expression are returned. " +
        "Use it to compute, test algorithms or check output; it cannot run Python or Kotlin."
    override val parametersSchema = toolSchema(listOf(Triple("code", "string", "JavaScript source")), listOf("code"))
    override fun describe(arguments: JSONObject) = "Running JavaScript"
    override suspend fun execute(arguments: JSONObject): String {
        val code = arguments.requireString("code")
        if (code.length > 20_000) throw ToolInputException("code is too long (limit 20000 characters)")
        return runner.run(code, 5_000L)
    }
}

fun registerCodeTools(manager: ToolManager, ws: Workspace, runner: JavaScriptRunner?) {
    manager.registerTool(SearchCodeTool(ws))
    manager.registerTool(AnalyzeCodeTool(ws))
    if (runner != null) manager.registerTool(RunJavaScriptTool(runner))
}

val CODE_TOOL_NAMES = setOf("search_code", "analyze_code", "run_javascript")
