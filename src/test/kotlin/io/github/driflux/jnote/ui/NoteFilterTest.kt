package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteFilterTest {
    private fun accepted(filter: NoteFilter) = NoteKind.entries.filter { filter.accepts(it) }

    @Test fun allAcceptsBothKinds() = assertEquals(listOf(NoteKind.CODE, NoteKind.FREE), accepted(NoteFilter.ALL))

    @Test fun codeAcceptsOnlyCodeNotes() = assertEquals(listOf(NoteKind.CODE), accepted(NoteFilter.CODE))

    @Test fun freeAcceptsOnlyFreeNotes() = assertEquals(listOf(NoteKind.FREE), accepted(NoteFilter.FREE))
}
