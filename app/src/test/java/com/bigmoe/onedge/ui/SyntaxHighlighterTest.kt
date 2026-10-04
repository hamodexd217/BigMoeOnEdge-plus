package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.SyntaxHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {
    @Test
    fun detectsCommonLanguages() {
        assertEquals("python", SyntaxHighlighter.detectLanguage("def hello():\n    return 1"))
        assertEquals("javascript", SyntaxHighlighter.detectLanguage("const x = 1; console.log(x)"))
        assertEquals("json", SyntaxHighlighter.detectLanguage("{\"a\": 1}"))
        assertEquals("cpp", SyntaxHighlighter.detectLanguage("#include <vector>"))
    }

    @Test
    fun tokenizesKeywordsStringsCommentsNumbersAndFunctions() {
        val tokens = SyntaxHighlighter.tokenize("kotlin", "fun hello(x: Int) { // note\n  val s = \"ok\"\n  return 42\n}")
        assertTrue(tokens.any { it.text == "fun" && it.kind == SyntaxHighlighter.Kind.KEYWORD })
        assertTrue(tokens.any { it.text == "hello" && it.kind == SyntaxHighlighter.Kind.FUNCTION })
        assertTrue(tokens.any { it.kind == SyntaxHighlighter.Kind.COMMENT })
        assertTrue(tokens.any { it.text == "\"ok\"" && it.kind == SyntaxHighlighter.Kind.STRING })
        assertTrue(tokens.any { it.text == "42" && it.kind == SyntaxHighlighter.Kind.NUMBER })
    }
}
