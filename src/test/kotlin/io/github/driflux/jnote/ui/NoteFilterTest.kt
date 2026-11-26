package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteFilterTest {
    private fun note(kind: NoteKind) = when (kind) {
        NoteKind.CODE -> Note(1, "u1", kind, "p", "a.kt", null, 1, 1, null, NoteStatus.OK, "t", "", null, null, null, 1, 1)
        NoteKind.FREE -> Note(2, "u2", kind, null, null, null, null, null, null, NoteStatus.OK, "t", "", null, null, null, 1, 1)
    }

    private fun accepted(filter: NoteFilter) = NoteKind.entries.filter { filter.accepts(note(it)) }

    @Test fun allAcceptsBothKinds() = assertEquals(listOf(NoteKind.CODE, NoteKind.FREE), accepted(NoteFilter.ALL))

    @Test fun codeAcceptsOnlyCodeNotes() = assertEquals(listOf(NoteKind.CODE), accepted(NoteFilter.CODE))

    @Test fun freeAcceptsOnlyFreeNotes() = assertEquals(listOf(NoteKind.FREE), accepted(NoteFilter.FREE))
}
