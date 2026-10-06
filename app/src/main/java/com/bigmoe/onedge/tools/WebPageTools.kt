package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.FileContext
import com.bigmoe.onedge.workspace.HtmlText
import com.bigmoe.onedge.workspace.WebFetchException
import com.bigmoe.onedge.workspace.WebFetcher
import org.json.JSONObject

private fun loadPage(fetcher: WebFetcher, url: String): HtmlText.Page {
    val r = try { fetcher.fetch(url) } catch (e: WebFetchException) { throw ToolInputException(e.message ?: "could not open the page") }
    val isHtml = r.contentType.contains("html") || r.body.trimStart().startsWith("<")
    val page = if (isHtml) HtmlText.extract(r.body) else HtmlText.Page("", r.body.trim())
    return page.copy(title = if (page.title.isNotEmpty()) "${page.title}\nURL: ${r.finalUrl}" else "URL: ${r.finalUrl}")
}

class ReadWebpageTool(private val fetcher: WebFetcher = WebFetcher()) : Tool {
    override val name = "read_webpage"
    override val category = ToolCategory.WEB
    override val description = "Open a web page (https) and return its readable text, start of the page first. For a specific fact on a long page use extract_webpage."
    override val parametersSchema = toolSchema(
        listOf(Triple("url", "string", "https:// address"), Triple("max_chars", "integer", "How much text to return (default 4000)")),
        listOf("url")
    )
    override fun describe(arguments: JSONObject) = "Reading ${arguments.optString("url").take(60)}"
    override suspend fun execute(arguments: JSONObject): String {
        val page = loadPage(fetcher, arguments.requireString("url"))
        val limit = (arguments.optIntLenient("max_chars") ?: 4000).coerceIn(500, 5500)
        val text = page.text
        if (text.isBlank()) return "${page.title}\n\n(The page has no readable text; it may need JavaScript.)"
        val body = if (text.length > limit) text.take(limit) + "\n[page continues: ${text.length - limit} more characters; use extract_webpage with a query for specific parts]" else text
        return "${page.title}\n\n$body"
    }
}

class ExtractWebpageTool(private val fetcher: WebFetcher = WebFetcher()) : Tool {
    override val name = "extract_webpage"
    override val category = ToolCategory.WEB
    override val description = "Open a web page and return only the passages most relevant to a question."
    override val parametersSchema = toolSchema(
        listOf(Triple("url", "string", "https:// address"), Triple("query", "string", "What you are looking for")),
        listOf("url", "query")
    )
    override fun describe(arguments: JSONObject) = "Searching ${arguments.optString("url").take(50)}"
    override suspend fun execute(arguments: JSONObject): String {
        val query = arguments.requireString("query")
        val page = loadPage(fetcher, arguments.requireString("url"))
        if (page.text.isBlank()) return "${page.title}\n\n(The page has no readable text.)"
        val passages = HtmlText.passages(page.text)
        val chunks = passages.mapIndexed { i, p -> FileContext.Chunk(i + 1, i + 1, p) }
        val picked = FileContext.select(chunks, query, budgetTokens = 1100)
        val body = picked.joinToString("\n\n---\n\n") { it.text }
        return "${page.title}\n\nMost relevant parts for \"$query\":\n\n$body"
    }
}

fun registerWebPageTools(manager: ToolManager, fetcher: WebFetcher = WebFetcher()) {
    manager.registerTool(ReadWebpageTool(fetcher))
    manager.registerTool(ExtractWebpageTool(fetcher))
}

val WEB_PAGE_TOOL_NAMES = setOf("read_webpage", "extract_webpage")
