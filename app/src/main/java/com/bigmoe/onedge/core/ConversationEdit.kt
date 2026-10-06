package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.entity.MessageRole

/**
 * What "edit a user message and regenerate" does to a stored conversation. Pure Kotlin (unit-tested); the
 * view model applies it to the database and then starts the normal generation pipeline.
 */
data class EditPlan(
    val messageId: String,
    /** The text before the edit (used to decide whether the chat title still came from it). */
    val previousText: String,
    /** Messages BEFORE the edited one, oldest first: the conversation the model sees ahead of the new prompt. */
    val history: List<HistoryMessage>,
    /**
     * Everything stored AFTER the edited message: the assistant answers, tool results and hidden attached-file
     * context that were built on the old text. They are removed; later turns depended on the old wording too.
     */
    val removeIds: List<String>,
    val isFirstUserMessage: Boolean
)

object ConversationEdit {

    /**
     * Plan for editing the user message [messageId] of [messages] (oldest first, as stored).
     * Null when the message does not exist or is not a user message.
     */
    fun plan(messages: List<HistoryMessage>, messageId: String): EditPlan? {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val target = messages[index]
        if (target.role != MessageRole.USER) return null
        val before = messages.subList(0, index).toList()
        val after = messages.subList(index + 1, messages.size)
        return EditPlan(
            messageId = messageId,
            previousText = target.content,
            history = before,
            removeIds = after.map { it.id },
            isFirstUserMessage = before.none { it.role == MessageRole.USER }
        )
    }
}
