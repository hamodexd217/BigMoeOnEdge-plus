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

    private val samples = mapOf(
        "html" to "<div class=\"a\">x</div>", "css" to "a { color: red; /* c */ }", "javascript" to "const x = () => { return 1 }; // c",
        "typescript" to "let a: number = 1;", "json" to "{\"a\": [1, true, null]}", "xml" to "<a b=\"1\"><c/></a>",
        "markdown" to "# Title\n- item", "python" to "def f():\n    return 1  # c", "kotlin" to "fun main() { println(\"x\") }",
        "java" to "public class A { void f() {} }", "c" to "#include <stdio.h>\nint main(){return 0;}",
        "cpp" to "#include <vector>\nauto x = std::vector<int>{};", "csharp" to "using System; class A {}",
        "rust" to "fn main() { let mut x = 1; }", "go" to "func main() { defer f() }", "swift" to "let a = 1\nfunc f() {}",
        "bash" to "if [ -f x ]; then echo \"hi\"; fi # c", "sql" to "SELECT a FROM t WHERE b = 1; -- c", "yaml" to "a:\n  - b # c"
    )

    @Test
    fun everyRequiredLanguageTokenizesAndTokensRebuildTheInputExactly() {
        for ((lang, code) in samples) {
            val tokens = SyntaxHighlighter.tokenize(lang, code)
            assertEquals(lang, code, tokens.joinToString("") { it.text })
            assertTrue(lang, tokens.isNotEmpty())
        }
        assertEquals(emptySet<String>(), com.bigmoe.onedge.parser.ArtifactLanguages.REQUIRED.toSet() - samples.keys)
    }

    @Test
    fun aliasesShareTheirCanonicalLanguageHighlighting() {
        assertEquals("javascript", SyntaxHighlighter.canonical("JS"))
        assertEquals("csharp", SyntaxHighlighter.canonical("c#"))
        assertEquals("cpp", SyntaxHighlighter.canonical("c++"))
        assertEquals("bash", SyntaxHighlighter.canonical("shell"))
        assertEquals("brainfuck", SyntaxHighlighter.canonical("Brainfuck"))
        assertEquals("text", SyntaxHighlighter.canonical(""))
    }

    @Test
    fun pythonHashAndSqlDashCommentsAreComments() {
        assertTrue(SyntaxHighlighter.tokenize("python", "x = 1 # note").any { it.kind == SyntaxHighlighter.Kind.COMMENT && it.text == "# note" })
        assertTrue(SyntaxHighlighter.tokenize("sql", "select 1 -- note").any { it.kind == SyntaxHighlighter.Kind.COMMENT })
    }

    @Test
    fun partialAndBrokenInputNeverThrows() {
        val broken = listOf("\"unterminated", "'", "\\", "x = \"a\\", "/* open", "<", "<div", "`", "0x", "9" + "9".repeat(10000), "\u0000\u0001")
        for (lang in samples.keys + listOf("brainfuck", "", "text")) {
            for (code in broken) {
                val tokens = SyntaxHighlighter.tokenize(lang, code)
                assertEquals("$lang / $code", code, tokens.joinToString("") { it.text })
            }
        }
    }
}
