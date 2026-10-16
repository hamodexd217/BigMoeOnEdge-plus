package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtifactDedupeTest {
    private val files = mapOf(
        "artifacts/a.html" to "<p>one</p>\n",
        "artifacts/b.html" to "<p>two</p>"
    )

    @Test fun findsTheFileWithTheSameText() {
        val got = ChatViewModel.pickExistingArtifact(listOf("artifacts/a.html", "artifacts/b.html"), "<p>two</p>") { files[it] }
        assertEquals("artifacts/b.html", got)
    }

    @Test fun ignoresTrailingLineBreaks() {
        val got = ChatViewModel.pickExistingArtifact(listOf("artifacts/a.html"), "<p>one</p>") { files[it] }
        assertEquals("artifacts/a.html", got)
    }

    @Test fun returnsNullWhenNothingMatchesOrAFileCannotBeRead() {
        assertNull(ChatViewModel.pickExistingArtifact(listOf("artifacts/a.html"), "<p>other</p>") { files[it] })
        assertNull(ChatViewModel.pickExistingArtifact(listOf("artifacts/gone.html"), "<p>one</p>") { null })
    }
}
