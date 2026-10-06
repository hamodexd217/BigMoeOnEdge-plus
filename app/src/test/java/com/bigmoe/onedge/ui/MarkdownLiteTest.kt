package com.bigmoe.onedge.ui

import com.bigmoe.onedge.ui.chat.MarkdownBlock
import com.bigmoe.onedge.ui.chat.MarkdownLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {
    @Test
    fun parsesProseAndClosedCode() {
        val blocks = MarkdownLite.parse("Hello\n\n```kotlin\nfun x() = 1\n```")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun x() = 1", code.code)
        assertTrue(code.closed)
    }

    @Test
    fun streamingOpenFenceStaysVisible() {
        val code = MarkdownLite.parse("before\n```python\nprint(1)").last() as MarkdownBlock.Code
        assertEquals("python", code.language)
        assertFalse(code.closed)
    }

    @Test
    fun parsesDelimitedPromptAsCopyableBlock() {
        val blocks = MarkdownLite.parse("before\n---\nYou are an assistant.\nStart with the exact phrase.\n---\nafter")
        assertTrue(blocks.any { it is MarkdownBlock.Prompt && it.text.contains("Start with the exact phrase") })
    }

    @Test
    fun codeBlockCopyTextIsOnlyTheRawCode() {
        val code = MarkdownLite.parse("```javascript\nconsole.log(\"hello\");\n```").single() as MarkdownBlock.Code
        assertEquals("console.log(\"hello\");", code.code)
        assertEquals("javascript", code.language)
    }

    @Test
    fun everyFencedBlockIsACodeBlockWithItsOwnRawCode() {
        val md = "a\n```python\nprint(1)\n```\nb\n```go\nvar x = 1\n```\n```\nno tag\n```\nc"
        val codes = MarkdownLite.parse(md).filterIsInstance<MarkdownBlock.Code>()
        assertEquals(listOf("print(1)", "var x = 1", "no tag"), codes.map { it.code })
        assertEquals(listOf("python", "go", ""), codes.map { it.language })
    }

    @Test
    fun tagsWithSymbolsAndIndentedAndTildeFences() {
        assertEquals("c#", (MarkdownLite.parse("```c#\nclass A {}\n```").single() as MarkdownBlock.Code).language)
        val indented = MarkdownLite.parse("- item\n  ```kotlin\n  val a = 1\n  ```").filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("val a = 1", indented.code)
        assertEquals("rust", (MarkdownLite.parse("~~~rust\nfn main() {}\n~~~").single() as MarkdownBlock.Code).language)
    }

    @Test
    fun codeInsideALongerFenceKeepsInnerFences() {
        val code = MarkdownLite.parse("````markdown\n```js\nx\n```\n````").single() as MarkdownBlock.Code
        assertEquals("```js\nx\n```", code.code)
    }

    @Test
    fun renderedBlocksAgreeWithArtifactDetection() {
        val raw = "x\n```typescript\nlet a: number;\n```\n- y\n  ```sql\n  select 1;\n  ```\n"
        val shown = MarkdownLite.parse(raw).filterIsInstance<MarkdownBlock.Code>().map { it.code }
        val artifacts = com.bigmoe.onedge.parser.MessageContent.artifacts("m", raw).map { it.content }
        assertEquals(shown, artifacts)
    }

    @Test
    fun fencedPromptTemplateAndInstructionBlocksAreCopyableWhole() {
        val md = "```prompt\nYou are a helper.\n\nAlways answer briefly.\n```\n```template\nDear {{name}},\n```"
        val blocks = MarkdownLite.parse(md)
        val p = blocks[0] as MarkdownBlock.Prompt
        assertEquals("You are a helper.\n\nAlways answer briefly.", p.text)
        assertEquals("Prompt", p.label)
        assertEquals("Template", (blocks[1] as MarkdownBlock.Prompt).label)
    }

    @Test
    fun malformedInputDoesNotCrash() {
        for (s in listOf("```", "``````", "```js", "---", "---\n", "```\n---\n```", "\u0000", "````\n```")) {
            MarkdownLite.parse(s)
        }
        assertTrue(MarkdownLite.parse("").isEmpty())
    }
}
