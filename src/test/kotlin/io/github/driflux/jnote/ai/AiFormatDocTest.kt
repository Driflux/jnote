package io.github.driflux.jnote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AiFormatDocTest {
    private val doc = Files.readString(Path.of("docs/ai-format.md"))

    @Test fun everyJsonExampleDecodesWithoutSkippedItems() {
        val examples = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(doc).map { it.groupValues[1] }.toList()
        val complete = examples.filter { "\"notes\": [ ... ]" !in it }
        assertTrue("expected the four full examples, found ${complete.size}", complete.size >= 4)
        for (example in complete) {
            val result = NoteFormatCodec.decode(example)
            assertTrue("$result\n$example", result is DecodeResult.Decoded)
            result as DecodeResult.Decoded
            assertEquals(example, emptyList<SkippedItem>(), result.skipped)
            assertTrue(example, result.notes.isNotEmpty())
            for (anchor in result.notes.mapNotNull { it.anchor }) {
                val snapshotLines = anchor.snapshot?.lines()?.size ?: continue
                assertEquals("snapshot must cover the anchored lines", anchor.endLine - anchor.startLine + 1, snapshotLines)
            }
        }
    }

    @Test fun formatVersionMatchesTheCodec() {
        assertTrue(doc.contains("\"format_version\": ${NoteFormatCodec.FORMAT_VERSION}"))
    }

    @Test fun promptsCoverTheOriginlessFallbackAndTheRawOrigin() {
        val prompts = doc.substringAfter("## Prompt examples").substringBefore("## Validation reference")
        assertEquals(2, Regex("project_path").findAll(prompts).count())
        assertTrue(prompts.contains("git config --get remote.origin.url"))
        assertTrue(doc.contains("git config --get remote.origin.url"))
        assertTrue(!prompts.contains("get-url"))
    }
}
