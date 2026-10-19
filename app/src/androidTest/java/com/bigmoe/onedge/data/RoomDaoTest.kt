package com.bigmoe.onedge.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bigmoe.onedge.core.NewMessage
import com.bigmoe.onedge.core.GenerationStats
import com.bigmoe.onedge.data.local.AppDatabase
import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import com.bigmoe.onedge.data.local.entity.AttachmentEntity
import com.bigmoe.onedge.data.local.entity.ArtifactEntity
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.data.repository.ChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented: needs a device/emulator (Room + SQLite). Run with ./gradlew connectedDebugAndroidTest. */
@RunWith(AndroidJUnit4::class)
class RoomDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
    }

    @After
    fun close() = db.close()

    @Test
    fun messagesComeBackInInsertionOrderEvenWithIdenticalTimestamps() = runBlocking {
        val s = repo.createNewSession("t")
        val same = 1_000L
        listOf("a", "b", "c").forEach {
            db.chatMessageDao().insertMessage(ChatMessageEntity(sessionId = s.id, role = MessageRole.USER, content = it, timestamp = same))
        }
        assertEquals(listOf("a", "b", "c"), repo.getMessages(s.id).map { it.content })
    }

    @Test
    fun addMessagePersistsTelemetryAndBumpsSession() = runBlocking {
        val s = repo.createNewSession("t")
        Thread.sleep(5)
        val id = repo.addMessage(
            s.id,
            NewMessage(MessageRole.ASSISTANT, "hi", "thought", GenerationStats(nGenerated = 7, tokensPerSecond = 3.5, prefillSeconds = 0.25, cacheHitPct = 61.0), note = "Stopped")
        )
        val m = repo.getMessages(s.id).single()
        assertEquals(id, m.id)
        assertEquals("thought", m.reasoning)
        assertEquals(3.5, m.tokensPerSecond!!, 0.0)
        assertEquals(250L, m.prefillMs)
        assertEquals(7, m.generatedTokens)
        assertEquals(61.0, m.cacheHitPct!!, 0.0)
        assertEquals("Stopped", m.note)
        assertTrue(repo.getSession(s.id)!!.updatedAt > s.updatedAt)
    }

    @Test
    fun cacheHitIsNullWhenCacheIsOff() = runBlocking {
        val s = repo.createNewSession("t")
        repo.addMessage(s.id, NewMessage(MessageRole.ASSISTANT, "x", stats = GenerationStats(nGenerated = 1, cacheHitPct = -1.0)))
        assertNull(repo.getMessages(s.id).single().cacheHitPct)
    }

    @Test
    fun toolRoleRoundTrips() = runBlocking {
        val s = repo.createNewSession("t")
        repo.addMessage(s.id, NewMessage(MessageRole.TOOL, "<tool_response>r</tool_response>"))
        assertEquals(MessageRole.TOOL, repo.getMessages(s.id).single().role)
    }

    @Test
    fun deletingASessionCascadesToItsMessages() = runBlocking {
        val a = repo.createNewSession("a")
        val b = repo.createNewSession("b")
        repo.addMessage(a.id, NewMessage(MessageRole.USER, "1"))
        repo.addMessage(b.id, NewMessage(MessageRole.USER, "2"))
        repo.deleteSession(a.id)
        assertEquals(0, db.chatMessageDao().countForSession(a.id))
        assertEquals(1, db.chatMessageDao().countForSession(b.id))
    }

    @Test
    fun sessionsSortedByMostRecentActivity() = runBlocking {
        val old = repo.createNewSession("old")
        Thread.sleep(5)
        val newer = repo.createNewSession("newer")
        Thread.sleep(5)
        repo.addMessage(old.id, NewMessage(MessageRole.USER, "bump"))
        assertEquals(listOf(old.id, newer.id), repo.observeAllSessions().first().map { it.id })
    }

    @Test
    fun attachmentsRoundTripBySession() = runBlocking {
        val s = repo.createNewSession("attachments")
        val messageId = repo.addMessage(s.id, NewMessage(MessageRole.USER, "file"))
        db.attachmentDao().insert(
            AttachmentEntity(
                sessionId = s.id,
                messageId = messageId,
                kind = "FILE",
                name = "report.md",
                mime = "text/markdown",
                path = "uploads/report.md",
                sizeBytes = 42
            )
        )
        val rows = db.attachmentDao().getForSession(s.id)
        assertEquals(1, rows.size)
        assertEquals("report.md", rows.single().name)
        assertEquals("uploads/report.md", rows.single().path)
        assertEquals(messageId, rows.single().messageId)
    }

    @Test
    fun artifactsRoundTripAndDeleteById() = runBlocking {
        val s = repo.createNewSession("artifacts")
        val artifact = ArtifactEntity(
            sessionId = s.id,
            messageId = "",
            title = "hello.kt",
            path = "artifacts/hello.kt",
            language = "kotlin"
        )
        db.artifactDao().insert(artifact)
        assertEquals(artifact, db.artifactDao().getById(artifact.id))
        assertEquals(artifact, db.artifactDao().findByPath(s.id, artifact.path))
        db.artifactDao().deleteById(artifact.id)
        assertNull(db.artifactDao().getById(artifact.id))
    }

    @Test
    fun clearAllRemovesEverything() = runBlocking {
        val s = repo.createNewSession("a")
        repo.addMessage(s.id, NewMessage(MessageRole.USER, "1"))
        repo.clearAllSessions()
        assertTrue(repo.observeAllSessions().first().isEmpty())
        assertEquals(0, db.chatMessageDao().countForSession(s.id))
    }

    @Test
    fun rewriteUserMessageReplacesTextInPlaceAndDropsDependants() = runBlocking {
        val s = repo.createNewSession("t")
        val u1 = repo.addMessage(s.id, NewMessage(MessageRole.USER, "first"))
        val a1 = repo.addMessage(s.id, NewMessage(MessageRole.ASSISTANT, "answer one"))
        val u2 = repo.addMessage(s.id, NewMessage(MessageRole.USER, "second"))
        val c2 = repo.addMessage(s.id, NewMessage(MessageRole.CONTEXT, "file text"))
        val a2 = repo.addMessage(s.id, NewMessage(MessageRole.ASSISTANT, "answer two"))

        assertTrue(repo.rewriteUserMessage(s.id, u2, "second, edited", listOf(c2, a2)))

        val left = repo.getMessages(s.id)
        assertEquals(listOf(u1, a1, u2), left.map { it.id })
        assertEquals(listOf("first", "answer one", "second, edited"), left.map { it.content })
    }

    @Test
    fun rewriteRefusesNonUserMessagesAndEmptyRemoveList() = runBlocking {
        val s = repo.createNewSession("t")
        val u = repo.addMessage(s.id, NewMessage(MessageRole.USER, "q"))
        val a = repo.addMessage(s.id, NewMessage(MessageRole.ASSISTANT, "a"))
        assertTrue(!repo.rewriteUserMessage(s.id, a, "hacked", emptyList()))
        assertEquals("a", repo.getMessages(s.id).last().content)
        assertTrue(repo.rewriteUserMessage(s.id, u, "q2", emptyList()))
        assertEquals(2, repo.getMessages(s.id).size)
    }
}
