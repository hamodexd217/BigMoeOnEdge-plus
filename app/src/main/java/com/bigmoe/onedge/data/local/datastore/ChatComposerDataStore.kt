package com.bigmoe.onedge.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bigmoe.onedge.attachments.PendingAttachment
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.chatComposerStore by preferencesDataStore(name = "chat_composer")

/**
 * Persistent unsent composer state. This is UI state rather than chat history, so DataStore avoids a Room
 * schema migration and does not use the database's destructive fallback.
 */
class ChatComposerDataStore(private val context: Context) {
    private val mutex = Mutex()

    private fun safeKey(prefix: String, sessionKey: String): androidx.datastore.preferences.core.Preferences.Key<String> =
        stringPreferencesKey("$prefix:${sessionKey.replace("%", "%25").replace(":", "%3A")}")

    suspend fun getDraft(sessionKey: String): String = mutex.withLock {
        context.chatComposerStore.data.first()[safeKey("draft", sessionKey)].orEmpty()
    }

    suspend fun saveDraft(sessionKey: String, text: String) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            val key = safeKey("draft", sessionKey)
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
    }

    suspend fun getPending(sessionKey: String): List<PendingAttachment> = mutex.withLock {
        decodePending(context.chatComposerStore.data.first()[safeKey("pending", sessionKey)].orEmpty())
    }

    suspend fun savePending(sessionKey: String, items: List<PendingAttachment>) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            val key = safeKey("pending", sessionKey)
            if (items.isEmpty()) prefs.remove(key) else prefs[key] = encodePending(items)
        }
    }

    suspend fun clearSession(sessionKey: String) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            prefs.remove(safeKey("draft", sessionKey))
            prefs.remove(safeKey("pending", sessionKey))
        }
    }

    suspend fun clearAll() = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("draft:") || it.name.startsWith("pending:") }
                .forEach { prefs.remove(it) }
        }
    }

    private fun encodePending(items: List<PendingAttachment>): String {
        val array = JSONArray()
        items.forEach { a ->
            array.put(JSONObject().apply {
                put("id", a.id)
                put("kind", a.kind.name)
                put("name", a.name)
                put("path", a.path)
                put("mime", a.mime)
                put("sizeBytes", a.sizeBytes)
            })
        }
        return array.toString()
    }

    private fun decodePending(raw: String): List<PendingAttachment> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList(a.length()) {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    add(
                        PendingAttachment(
                            id = o.getString("id"),
                            kind = AttachmentKind.valueOf(o.getString("kind")),
                            name = o.getString("name"),
                            path = o.getString("path"),
                            mime = o.optString("mime", "application/octet-stream"),
                            sizeBytes = o.optLong("sizeBytes", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
