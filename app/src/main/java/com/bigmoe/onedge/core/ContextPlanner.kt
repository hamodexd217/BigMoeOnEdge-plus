package com.bigmoe.onedge.core

/**
 * Pure logic (no Android, no JNI) that decides what to hand the engine. See docs/prompt-and-kv-design.md.
 *
 * Why this exists: bmoe::Session in chat mode owns the conversation. It re-renders the model's chat
 * template over its own running history every turn and reuses the KV prefix. The app therefore never
 * builds a prompt string; it only (a) supplies the newest user message, and (b) when the engine's
 * conversation is not the one on screen, resets it and replays the saved turns.
 */
object ContextPlanner {

    /** Result of [prepare]. */
    data class Prepared(
        val history: List<ChatTurn>,
        val prompt: String,
        /** True when older turns had to be dropped to fit the context. */
        val trimmed: Boolean
    )

    /**
     * Key describing "the engine holds exactly this conversation": the chat, the last saved message
     * (empty for a brand new chat) and the system prompt that was in force. Any change (another chat,
     * a deleted/edited message, a different system prompt or tool set) yields a different key and
     * therefore a reset + replay on the next turn.
     */
    fun stateKey(sessionId: String, lastMessageId: String?, system: String): String =
        "$sessionId|${lastMessageId.orEmpty()}|${system.hashCode()}"

    /** Conservative token estimate that never needs the tokenizer: ~3 UTF-8 bytes per token. */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return bytes / 3 + 4
    }

    /**
     * Cleans saved turns into something every Jinja chat template accepts (strict user/assistant
     * alternation starting with a user turn): empty turns are dropped, consecutive turns of one role
     * are merged, and any leading assistant turn is dropped.
     */
    fun normalize(turns: List<ChatTurn>): List<ChatTurn> {
        val out = ArrayList<ChatTurn>()
        for (t in turns) {
            val role = if (t.role == ChatTurn.ASSISTANT) ChatTurn.ASSISTANT else ChatTurn.USER
            val content = t.content.trim()
            if (content.isEmpty()) continue
            if (out.isNotEmpty() && out.last().role == role) {
                val prev = out.removeAt(out.size - 1)
                out.add(ChatTurn(role, prev.content + "\n\n" + content))
            } else {
                out.add(ChatTurn(role, content))
            }
        }
        while (out.isNotEmpty() && out.first().role != ChatTurn.USER) out.removeAt(0)
        return out
    }

    /**
     * Builds the replay payload: normalises [history], folds a trailing unanswered user turn into
     * [prompt] (so roles still alternate once the prompt is appended), and drops the OLDEST whole
     * user/assistant pairs until the estimate fits [historyBudgetTokens].
     */
    fun prepare(history: List<ChatTurn>, prompt: String, historyBudgetTokens: Int): Prepared {
        var turns = normalize(history)
        var effectivePrompt = prompt
        if (turns.isNotEmpty() && turns.last().role == ChatTurn.USER) {
            effectivePrompt = turns.last().content + "\n\n" + prompt
            turns = turns.dropLast(1)
        }
        var total = turns.sumOf { estimateTokens(it.content) + 8 }
        var trimmed = false
        var start = 0
        while (total > historyBudgetTokens && start < turns.size) {
            total -= estimateTokens(turns[start].content) + 8
            start++
            trimmed = true
        }
        // Keep alternation: a trimmed history must again start with a user turn.
        while (start < turns.size && turns[start].role != ChatTurn.USER) {
            start++
            trimmed = true
        }
        return Prepared(turns.subList(start.coerceAtMost(turns.size), turns.size).toList(), effectivePrompt, trimmed)
    }

    /**
     * Tokens available for replayed history. [maxTokens] <= 0 ("unlimited") reserves a quarter of the
     * context for the answer; the total prompt never takes more than ~70% of the context.
     */
    fun historyBudget(nCtx: Int, systemTokens: Int, promptTokens: Int, maxTokens: Int): Int {
        val reserve = if (maxTokens > 0) minOf(maxTokens, nCtx / 2) else nCtx / 4
        val byReserve = nCtx - reserve - systemTokens - promptTokens - 32
        val byCap = (nCtx * 7) / 10 - systemTokens - promptTokens
        return maxOf(0, minOf(byReserve, byCap))
    }

    /** Explicit answer limits are clamped so prompt + answer can never trivially overflow the context. */
    fun effectiveMaxTokens(maxTokens: Int, nCtx: Int): Int =
        if (maxTokens <= 0) 0 else minOf(maxTokens, maxOf(1, nCtx / 2))

    /** True for the engine's "prompt + n_predict exceeds the session n_ctx" failure. */
    fun isContextOverflow(error: String): Boolean = error.contains("n_ctx")
}
