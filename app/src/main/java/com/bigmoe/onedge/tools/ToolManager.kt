package com.bigmoe.onedge.tools

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class ToolManager(
    private val toolTimeoutMs: Long = 20_000L,
    private val maxResultChars: Int = 6_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val registeredTools = LinkedHashMap<String, Tool>()

    /** Shows the approval dialog. Without one, tools that need approval are refused (never run silently). */
    @Volatile
    var confirmationHandler: ConfirmationHandler? = null

    fun registerTool(tool: Tool) {
        registeredTools[tool.name] = tool
    }

    fun getTool(name: String): Tool? = registeredTools[name]

    fun hasTools(): Boolean = registeredTools.isNotEmpty()

    fun toolNames(): Set<String> = registeredTools.keys.toSet()

    /** Compact one-line signature: `read_file(path, start_line?, end_line?)`. */
    fun signature(tool: Tool): String {
        val props = tool.parametersSchema.optJSONObject("properties") ?: JSONObject()
        val required = HashSet<String>()
        tool.parametersSchema.optJSONArray("required")?.let { arr -> for (i in 0 until arr.length()) required.add(arr.optString(i)) }
        val names = props.keys().asSequence().toList()
        return tool.name + "(" + names.joinToString(", ") { if (it in required) it else "$it?" } + ")"
    }

    /**
     * Text appended to the system prompt: the tools in [names] (all when null), the call format and a few
     * rules. Empty when no tool is selected.
     */
    fun getToolsDescriptionPrompt(names: Set<String>? = null): String {
        val selected = registeredTools.values.filter { names == null || it.name in names }
        if (selected.isEmpty()) return ""
        val b = StringBuilder("You can call tools. Available tools:\n")
        selected.forEach { tool ->
            b.append("- ${signature(tool)}: ${tool.description}\n")
            val props = tool.parametersSchema.optJSONObject("properties")
            if (props != null) {
                props.keys().forEach { k ->
                    val p = props.optJSONObject(k)
                    b.append("    ").append(k).append(" (").append(p?.optString("type")).append("): ")
                        .append(p?.optString("description")).append('\n')
                }
            }
        }
        b.append(
            "\nTo use a tool, reply with ONLY this and nothing else:\n" +
                "<tool_call>{\"name\": \"tool_name\", \"arguments\": {...}}</tool_call>\n" +
                "The result comes back in a <tool_response> message; then continue. Rules: " +
                "do not invent tool results; do not call a tool when you can answer directly; " +
                "never say a file was created, changed or deleted unless the tool result confirms it; " +
                "read a file before editing it; file paths are relative to the user's workspace."
        )
        if (selected.any { it.name == "create_file" }) {
            b.append(
                " When the user asks you to write a document, program, page or config, create it with create_file " +
                    "instead of pasting it into the chat, then tell them briefly what you made."
            )
        }
        if (selected.any { it.category == ToolCategory.UTILITIES }) {
            b.append(
                " Use calculate for any arithmetic beyond the trivial instead of working it out in your head, " +
                    "get_datetime whenever the answer depends on today's date or the time, and convert_units for unit conversions."
            )
        }
        return b.toString()
    }

    /** Never throws (except coroutine cancellation): failures come back as "Error: ..." text for the model. */
    suspend fun executeToolCall(
        toolName: String,
        argumentsJson: String,
        ctx: ToolContext = ToolContext.NONE
    ): String {
        val tool = registeredTools[toolName] ?: return "Error: Tool '$toolName' not found. " +
            "Available tools: ${registeredTools.keys.joinToString(", ")}."
        return try {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)

            // 1. approval (outside the timeout: the user may take as long as they like)
            val request = withContext(ioDispatcher) { tool.confirmation(args) }
            if (request != null) {
                val handler = confirmationHandler
                    ?: return "Error: this action needs the user's approval, but no approval screen is available."
                if (!handler.confirm(request)) return "The user declined this action. Nothing was changed."
            }

            // 2. run
            val result = withContext(ioDispatcher) { withTimeout(toolTimeoutMs) { tool.execute(args, ctx) } }
            if (result.length > maxResultChars) result.take(maxResultChars) + "\n[truncated]" else result
        } catch (e: TimeoutCancellationException) {
            "Error: tool '$toolName' timed out after ${toolTimeoutMs / 1000}s."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ToolInputException) {
            "Error: ${e.message}"
        } catch (e: Exception) {
            "Error executing tool '$toolName': ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** UI label for a call, e.g. "Reading notes.md". Never throws. */
    fun describeCall(toolName: String, argumentsJson: String): String {
        val tool = registeredTools[toolName] ?: return toolName
        return try { tool.describe(if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)) } catch (_: Exception) { toolName }
    }
}
