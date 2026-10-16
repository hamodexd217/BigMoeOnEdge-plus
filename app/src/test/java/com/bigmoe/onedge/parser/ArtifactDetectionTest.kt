package com.bigmoe.onedge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The complete pipeline: model output -> fenced-code parser -> artifact detection -> artifact model. */
class ArtifactDetectionTest {

    private fun detect(raw: String, chunk: Int = Int.MAX_VALUE): List<ArtifactModel> {
        val p = StreamOutputParser { "id" }
        val out = ArrayList<ParsedChunk>()
        for (piece in raw.chunked(minOf(chunk, maxOf(raw.length, 1)))) out += p.processToken(piece)
        out += p.flush()
        return out.filterIsInstance<ParsedChunk.ArtifactDetected>().map { it.artifact }
    }

    // fence tag as a model writes it -> expected canonical language
    private val cases = listOf(
        "html" to "html", "css" to "css", "javascript" to "javascript", "js" to "javascript", "typescript" to "typescript",
        "ts" to "typescript", "json" to "json", "xml" to "xml", "markdown" to "markdown", "md" to "markdown",
        "python" to "python", "py" to "python", "kotlin" to "kotlin", "kt" to "kotlin", "java" to "java", "c" to "c",
        "cpp" to "cpp", "c++" to "cpp", "csharp" to "csharp", "c#" to "csharp", "cs" to "csharp", "rust" to "rust",
        "rs" to "rust", "go" to "go", "golang" to "go", "swift" to "swift", "bash" to "bash", "sh" to "bash",
        "shell" to "bash", "zsh" to "bash", "sql" to "sql", "yaml" to "yaml", "yml" to "yaml",
        "JavaScript" to "javascript", "JSON" to "json"
    )

    @Test
    fun everyRequiredLanguageIsDetectedWhetherTheStreamArrivesWholeOrInTinyPieces() {
        for ((tag, expected) in cases) {
            for (chunk in listOf(Int.MAX_VALUE, 7, 1)) {
                val found = detect("Here:\n```$tag\nsome code here\n```\nDone.", chunk)
                assertEquals("tag=$tag chunk=$chunk", 1, found.size)
                assertEquals("tag=$tag chunk=$chunk", expected, found[0].language)
                assertEquals("some code here", found[0].content)
            }
        }
    }

    @Test
    fun requiredLanguagesAllResolve() {
        for (lang in ArtifactLanguages.REQUIRED) assertEquals(lang, ArtifactLanguages.canonical(lang))
        assertEquals(19, ArtifactLanguages.REQUIRED.size)
    }

    @Test
    fun javascriptArtifactIsACodeArtifactNotAnExecutedOne() {
        val a = detect("```javascript\nconsole.log(\"hello\");\n```").single()
        assertEquals(ArtifactType.CODE, a.type)
        assertEquals("javascript", a.language)
        assertEquals("console.log(\"hello\");", a.content)
        assertEquals("JavaScript artifact", a.title)
    }

    @Test
    fun htmlStaysALivePreviewAndCssIsCode() {
        assertEquals(ArtifactType.HTML, detect("```html\n<h1>x</h1>\n```").single().type)
        assertEquals(ArtifactType.SVG, detect("```svg\n<svg/>\n```").single().type)
        assertEquals(ArtifactType.MERMAID, detect("```mermaid\ngraph TD; A-->B\n```").single().type)
        assertEquals(ArtifactType.CODE, detect("```css\nbody{}\n```").single().type)
    }

    @Test
    fun csharpTagWithHashIsNotMistakenForC() {
        // The old regex stopped at '#', so "c#" was read as language "c" with junk after it.
        assertEquals("csharp", detect("```c#\nclass A {}\n```").single().language)
    }

    @Test
    fun windowsLineEndingsAndIndentedFences() {
        assertEquals("let a = 1;", detect("```javascript\r\nlet a = 1;\r\n```").single().content)
        val indented = detect("- step\n  ```python\n  print(1)\n  ```\n").single()
        assertEquals("python", indented.language)
        assertEquals("print(1)", indented.content)
    }

    @Test
    fun tildeFencesAndLongerOuterFences() {
        assertEquals("rust", detect("~~~rust\nfn main() {}\n~~~").single().language)
        val outer = detect("````markdown\n```js\ninner\n```\n````").single()
        assertEquals("markdown", outer.language)
        assertEquals("```js\ninner\n```", outer.content)
    }

    @Test
    fun priorityIsFenceTagThenFileNameThenMetadata() {
        assertEquals("javascript", ArtifactLanguages.resolve("javascript", "sample.py", "kotlin"))
        assertEquals("rust", ArtifactLanguages.resolve("text", "main.rs", "kotlin"))
        assertEquals("rust", ArtifactLanguages.resolve("", "main.rs", null))
        assertEquals("kotlin", ArtifactLanguages.resolve("", null, "kotlin"))
        assertEquals("kotlin", ArtifactLanguages.resolve("unknownlang", null, "kotlin"))
        assertNull(ArtifactLanguages.resolve("unknownlang", "file.zzzz", null))
        assertEquals("text", ArtifactLanguages.resolve("text", null, null))
    }

    @Test
    fun fileNameInInfoStringIsUsedWhenTagIsMissing() {
        val a = detect("```filename=server.go\npackage main\n```").single()
        assertEquals("go", a.language)
        assertEquals("server.go", a.fileName)
        assertEquals("server.go", a.title)
    }

    @Test
    fun malformedInputNeverCrashesAndNeverInventsArtifacts() {
        val inputs = listOf(
            "```", "```js", "```js\n", "```js\nunclosed", "``````", "```js\n```", "```js\n   \n```", "~~~", "`` ` ``",
            "```js code```", "\u0000```js\n\u0000\n```", "```\u0001\n```", "```{.}\nx\n```", "```" + "a".repeat(5000) + "\nx\n```",
            "```javascript\nlet s = \"unterminated\n```", "```javascript\nlet x = '\\", "<tool_call>```js\nx\n```"
        )
        for (raw in inputs) {
            for (chunk in listOf(Int.MAX_VALUE, 3, 1)) {
                val found = detect(raw, chunk)
                assertTrue("input=$raw", found.all { it.content.isNotBlank() })
            }
        }
        assertTrue(detect("```js\nunclosed").isEmpty())
        assertTrue(detect("```js\n   \n```").isEmpty())
        assertTrue(detect("```unknownlang\nx\n```").isEmpty())
    }

    @Test
    fun severalBlocksInOneMessageKeepOrderAndIds() {
        var n = 0
        val p = StreamOutputParser { "id${n++}" }
        val chunks = p.processToken("```python\na=1\n```\ntext\n```go\nvar a = 1\n```\n") + p.flush()
        val found = chunks.filterIsInstance<ParsedChunk.ArtifactDetected>().map { it.artifact }
        assertEquals(listOf("python", "go"), found.map { it.language })
        assertEquals(listOf("id0", "id1"), found.map { it.id })
    }

    @Test
    fun storedMessageYieldsTheSameArtifactsAsTheLiveStream() {
        val raw = "Intro\n```typescript\nconst a: number = 1;\n```\nOutro"
        val stored = MessageContent.artifacts("m1", raw)
        assertEquals(1, stored.size)
        assertEquals("typescript", stored[0].language)
        assertEquals("m1#0", stored[0].id)
        assertEquals(detect(raw).single().content, stored[0].content)
    }

    @Test
    fun parsedTextIsNeverAltered() {
        val raw = "a\n```js\nx\n```\nb"
        val p = StreamOutputParser()
        val text = (raw.chunked(2).flatMap { p.processToken(it) } + p.flush())
            .filterIsInstance<ParsedChunk.Text>().joinToString("") { it.content }
        assertEquals(raw, text)
    }

    @Test
    fun extensionsMatchTheLanguage() {
        val expected = mapOf(
            "html" to "html", "css" to "css", "javascript" to "js", "typescript" to "ts", "json" to "json", "xml" to "xml",
            "markdown" to "md", "python" to "py", "kotlin" to "kt", "java" to "java", "c" to "c", "cpp" to "cpp",
            "csharp" to "cs", "rust" to "rs", "go" to "go", "swift" to "swift", "bash" to "sh", "sql" to "sql", "yaml" to "yml"
        )
        for ((lang, ext) in expected) assertEquals(lang, ext, ArtifactLanguages.extensionFor(lang))
        assertEquals("txt", ArtifactLanguages.extensionFor("nonsense"))
        assertFalse(ArtifactLanguages.isSupported("nonsense"))
        assertNotNull(ArtifactLanguages.fromFileName("a/b/Main.kt"))
    }
}
