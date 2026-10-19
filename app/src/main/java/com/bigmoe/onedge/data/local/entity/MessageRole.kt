package com.bigmoe.onedge.data.local.entity

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,

    /**
     * A tool result (or a tool-protocol error fed back to the model). [ChatMessageEntity.content] holds
     * exactly the `<tool_response>` text that is sent to the engine as a user turn on replay.
     */
    TOOL,

    /**
     * Hidden context that belongs to the user's message before it (attached file contents, ...). Never
     * shown as a bubble; replayed to the engine as part of the user turn.
     */
    CONTEXT
}
