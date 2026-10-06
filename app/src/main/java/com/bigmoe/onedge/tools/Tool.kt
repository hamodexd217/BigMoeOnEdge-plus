package com.bigmoe.onedge.tools

import org.json.JSONObject

enum class ToolCategory { FILES, CODE, WEB, UTILITIES }

/** Which chat message a tool call belongs to (so created files can be linked to it). */
data class ToolContext(val sessionId: String, val messageId: String) {
    companion object {
        val NONE = ToolContext("", "")
    }
}

/** Something the user must approve before a tool runs. [diff] is unified-diff text for edits. */
data class ConfirmationRequest(
    val title: String,
    val message: String,
    val diff: String? = null,
    val destructive: Boolean = false
)

fun interface ConfirmationHandler {
    /** Suspends until the user decides. true = approved. */
    suspend fun confirm(request: ConfirmationRequest): Boolean
}

/** A problem with the model's arguments or the target; the message is given back to the model verbatim. */
class ToolInputException(message: String) : Exception(message)

/**
 * One capability the model can call. To add a tool: implement this, register it in AppContainer
 * (ToolManager.registerTool). Nothing else needs to change: the system prompt, the call parsing, the
 * approval dialog and the "tool is running" indicator are all driven by these members.
 */
interface Tool {
    val name: String
    val description: String
    val parametersSchema: JSONObject
    val category: ToolCategory get() = ToolCategory.FILES

    /** Short text for the UI while the tool runs, e.g. "Reading notes.md". */
    fun describe(arguments: JSONObject): String = name

    /** Non-null = the user must approve first. May read files (called off the main thread). */
    fun confirmation(arguments: JSONObject): ConfirmationRequest? = null

    suspend fun execute(arguments: JSONObject): String

    /** Variant that knows the chat; tools that create or change files override this. */
    suspend fun execute(arguments: JSONObject, ctx: ToolContext): String = execute(arguments)
}

/** Schema helper: `schema("path" to "string", ...)` with required names. */
fun toolSchema(properties: List<Triple<String, String, String>>, required: List<String>): JSONObject {
    val props = JSONObject()
    for ((name, type, desc) in properties) {
        props.put(name, JSONObject().put("type", type).put("description", desc))
    }
    return JSONObject().put("type", "object").put("properties", props)
        .put("required", org.json.JSONArray(required))
}
