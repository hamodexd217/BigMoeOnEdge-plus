package com.bigmoe.onedge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactLanguageTest {
    @Test
    fun commonCodeFencesBecomeArtifactsWithTheirLanguage() {
        val parser = StreamOutputParser { "id" }
        val found = buildList {
            parser.processToken(
                """
                ```kotlin
                fun main() = println("hi")
                ```
                ```python
                print("hi")
                ```
                ```json
                {"ok": true}
                ```
                ```cpp
                int main() { return 0; }
                ```
                """.trimIndent()
            ).forEach { if (it is ParsedChunk.ArtifactDetected) add(it.artifact) }
        }
        assertEquals(4, found.size)
        assertEquals(listOf("kotlin", "python", "json", "cpp"), found.map { it.language })
        assertTrue(found.all { it.type == ArtifactType.CODE })
    }
    @Test
    fun fenceFilenameCanOverrideGenericLanguageMetadata() {
        val parser = StreamOutputParser { "id" }
        val found = parser.processToken(
            """```text filename=main.rs
fn main() {}
```"""
        ).filterIsInstance<ParsedChunk.ArtifactDetected>().single().artifact
        assertEquals("rust", found.language)
        assertEquals("main.rs", found.fileName)
    }

    @Test
    fun usesFenceLanguageBeforeExtensionWhenBothArePresent() {
        val parser = StreamOutputParser { "id" }
        val chunks = parser.processToken("```javascript filename=sample.py\nconsole.log(1);\n```") + parser.flush()
        val artifact = chunks.filterIsInstance<ParsedChunk.ArtifactDetected>().single().artifact
        assertEquals("javascript", artifact.language)
        assertEquals("sample.py", artifact.fileName)
    }
}
