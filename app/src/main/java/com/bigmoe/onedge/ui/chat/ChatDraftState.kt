package com.bigmoe.onedge.ui.chat

/** Pure per-chat composer state used by the ViewModel and JVM tests. */
class ChatDraftState {
    private val drafts = linkedMapOf<String, String>()

    fun draftFor(key: String): String = drafts[key].orEmpty()

    fun setDraft(key: String, text: String) {
        if (text.isEmpty()) drafts.remove(key) else drafts[key] = text
    }

    fun clearDraft(key: String) {
        drafts.remove(key)
    }

    fun switch(to: String): String = draftFor(to)

    companion object {
        const val NEW_CHAT_KEY = "__new_chat__"
    }
}
