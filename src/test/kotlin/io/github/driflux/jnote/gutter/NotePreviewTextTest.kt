package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NotePreviewTextTest {
    private fun note(id: Long, start: Int, end: Int, status: NoteStatus = NoteStatus.OK) =
        CachedNote(id, "u$id", "t$id", "", emptyList(), null, status, start, end, "")

    @Test
    fun shortBodyIsKeptAsIs() {
        assertEquals("one\ntwo", NotePreviewText.body("  one\ntwo \n"))
    }

    @Test
    fun longBodyIsCutAtTheLineLimitWithAnEllipsis() {
        val body = (1..20).joinToString("\n") { "line $it" }

        val shown = NotePreviewText.body(body)

        assertEquals(NotePreviewText.BODY_MAX_LINES, shown.lines().size)
        assertEquals("line 8…", shown.lines().last())
    }

    @Test
    fun aVeryLongLineIsCutAtTheCharacterLimit() {
        val shown = NotePreviewText.body("x".repeat(5_000))

        assertEquals(NotePreviewText.BODY_MAX_CHARS + 1, shown.length)
        assertEquals('…', shown.last())
    }

    @Test
    fun aHugeBodyIsCutWithoutReadingItAll() {
        val body = "first\n" + "y".repeat(50_000_000)

        val shown = NotePreviewText.body(body)

        assertEquals("first\n" + "y".repeat(NotePreviewText.BODY_MAX_CHARS - 6) + "…", shown)
    }

    @Test
    fun blankBodiesAndSurroundingWhitespaceGiveCleanText() {
        assertEquals("", NotePreviewText.body(" \n\t "))
        assertEquals("a\n\nb", NotePreviewText.body("\n\n a\n\nb \n\n"))
        assertEquals("1\n2\n3\n4\n5\n6\n7\n8", NotePreviewText.body((1..8).joinToString("\n")))
    }

    @Test
    fun tagsAreJoinedWithAHash() {
        assertEquals("#a #b", NotePreviewText.tags(listOf("a", "b")))
        assertEquals("", NotePreviewText.tags(emptyList()))
    }

    @Test
    fun coveringIncludesTheStartLineTheRangeAndTheEndLine() {
        val notes = listOf(note(1, 5, 8))

        assertEquals(emptyList<CachedNote>(), NotePreviewText.covering(notes, 4))
        for (line in 5..8) assertEquals(listOf(1L), NotePreviewText.covering(notes, line).map { it.id })
        assertEquals(emptyList<CachedNote>(), NotePreviewText.covering(notes, 9))
    }

    @Test
    fun coveringSkipsLostNotesAndOrdersById() {
        val notes = listOf(
            note(9, 1, 5),
            note(3, 2, 2, NoteStatus.APPROX),
            note(4, 2, 2, NoteStatus.ORPHAN),
            note(5, 2, 2, NoteStatus.NEEDS_RELOCATE),
        )

        assertEquals(listOf(3L, 9L), NotePreviewText.covering(notes, 2).map { it.id })
    }
}
