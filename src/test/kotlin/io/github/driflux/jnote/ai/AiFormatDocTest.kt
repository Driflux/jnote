package io.github.driflux.jnote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** 명세 문서의 예시가 실제 코덱으로 그대로 읽히는지 확인한다. 문서가 스킬과 복사용 안내의 원본이기 때문이다. */
class AiFormatDocTest {
    private val doc = Files.readString(Path.of("docs/ai-format.md"))

    @Test fun everyJsonExampleDecodesWithoutSkippedItems() {
        val examples = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(doc).map { it.groupValues[1] }.toList()
        val complete = examples.filter { "\"notes\": [ ... ]" !in it }
        assertTrue("expected the three full examples, found ${complete.size}", complete.size >= 3)
        for (example in complete) {
            val result = NoteFormatCodec.decode(example)
            assertTrue("$result\n$example", result is DecodeResult.Decoded)
            result as DecodeResult.Decoded
            assertEquals(example, emptyList<SkippedItem>(), result.skipped)
            assertTrue(example, result.notes.isNotEmpty())
        }
    }

    @Test fun formatVersionMatchesTheCodec() {
        assertTrue(doc.contains("\"format_version\": ${NoteFormatCodec.FORMAT_VERSION}"))
    }
}
