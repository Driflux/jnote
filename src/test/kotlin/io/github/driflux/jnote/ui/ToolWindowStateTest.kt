package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolWindowStateTest {
    private val row = NoteRows.of(
        Note(1, "u", NoteKind.FREE, null, null, null, null, null, null, NoteStatus.OK, "t", "", null, null, null, 1, 1),
        null,
        "free",
    )

    @Test
    fun beforeTheFirstLoadNoEmptyStateIsShown() {
        assertFalse(ToolWindowState().showEmptyState)
    }

    @Test
    fun emptyAfterLoadShowsGuidance() {
        assertTrue(ToolWindowState(notes = emptyList()).showEmptyState)
    }

    @Test
    fun nonEmptyListHasNoGuidance() {
        assertFalse(ToolWindowState(notes = listOf(row)).showEmptyState)
    }

    @Test
    fun allProjectsIsOffByDefault() {
        assertFalse(ToolWindowState().allProjects)
    }
}
