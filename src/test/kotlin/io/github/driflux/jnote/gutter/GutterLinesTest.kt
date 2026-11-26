package io.github.driflux.jnote.gutter

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutterLinesTest {
    private fun cached(id: Long, line: Int, status: NoteStatus = NoteStatus.OK) =
        CachedNote(id, "t$id", "", emptyList(), null, status, line, line + 2, "")

    @Test
    fun onlyOkAndApproxNotesDrawAMarker() {
        val groups = GutterLines.group(
            listOf(
                cached(1, 1, NoteStatus.OK),
                cached(2, 2, NoteStatus.APPROX),
                cached(3, 3, NoteStatus.ORPHAN),
                cached(4, 4, NoteStatus.NEEDS_RELOCATE),
            ),
        )
        assertEquals(listOf(1, 2), groups.map { it.line })
    }

    @Test
    fun notesOnTheSameStartLineShareOneGroupOrderedById() {
        val groups = GutterLines.group(listOf(cached(7, 5), cached(3, 5), cached(9, 2)))
        assertEquals(listOf(2, 5), groups.map { it.line })
        assertEquals(listOf(3L, 7L), groups[1].notes.map { it.id })
    }

    @Test
    fun aRangeIsMarkedOnlyOnItsStartLine() {
        assertEquals(listOf(5), GutterLines.group(listOf(cached(1, 5))).map { it.line })
    }

    @Test
    fun aGroupUsesTheApproxIconOnlyWhenEveryNoteIsApprox() {
        assertTrue(GutterLines.group(listOf(cached(1, 1, NoteStatus.APPROX))).single().approxOnly)
        assertFalse(GutterLines.group(listOf(cached(1, 1, NoteStatus.APPROX), cached(2, 1, NoteStatus.OK))).single().approxOnly)
    }

    @Test
    fun noNotesMeansNoGroups() {
        assertEquals(emptyList<LineGroup>(), GutterLines.group(emptyList()))
    }

    private fun stored(snapshot: String?): Note = Note(
        1, "uid", NoteKind.CODE, "p", "a.kt", null, 3, 9, snapshot, NoteStatus.APPROX, "title", "body", "Claude", null, null, 1, 1,
    )

    @Test
    fun cachedNoteKeepsWhatThePreviewNeeds() {
        val note = CachedNote.of(stored("l1\nl2\nl3\nl4\nl5\nl6\nl7"), listOf(Tag(1, "sql"), Tag(2, "api")))!!
        assertEquals("title", note.title)
        assertEquals("body", note.body)
        assertEquals(listOf("sql", "api"), note.tags)
        assertEquals("Claude", note.aiSource)
        assertEquals(NoteStatus.APPROX, note.status)
        assertEquals(3, note.startLine)
        assertEquals(9, note.endLine)
        assertEquals("l1\nl2\nl3\nl4\nl5", note.snippetHead)
    }

    @Test
    fun snippetHeadIsCappedAndNullSafe() {
        assertEquals("", CachedNote.snippetHead(null))
        assertEquals(CachedNote.SNIPPET_MAX_CHARS, CachedNote.snippetHead("x".repeat(5_000)).length)
    }

    @Test
    fun aFreeNoteHasNoAnchorSoItIsNotCached() {
        val free = Note(1, "u", NoteKind.FREE, null, null, null, null, null, null, NoteStatus.OK, "t", "", null, null, null, 1, 1)
        assertNull(CachedNote.of(free, emptyList()))
    }
}
