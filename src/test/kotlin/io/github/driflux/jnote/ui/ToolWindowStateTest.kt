package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolWindowStateTest {
    private val row = NoteRows.of(
        Note(1, "u", NoteKind.FREE, null, null, null, null, null, null, NoteStatus.OK, "t", "", null, null, null, 1, 1),
        null,
        "free",
    )
    private val codeRow = NoteRows.of(
        Note(2, "c", NoteKind.CODE, "p", "a.kt", null, 1, 1, null, NoteStatus.OK, "t", "", null, null, null, 1, 1),
        "p",
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

    @Test
    fun filterIsAllByDefaultAndKeepsEveryRow() {
        val state = ToolWindowState(notes = listOf(row, codeRow))
        assertEquals(NoteFilter.ALL, state.filter)
        assertEquals(listOf(row, codeRow), state.visibleNotes)
    }

    @Test
    fun filterNarrowsTheVisibleRowsButNotTheLoadedOnes() {
        val state = ToolWindowState(notes = listOf(row, codeRow), filter = NoteFilter.CODE)
        assertEquals(listOf(codeRow), state.visibleNotes)
        assertEquals(listOf(row, codeRow), state.notes)
    }

    @Test
    fun filterThatHidesEverythingShowsEmptyState() {
        assertTrue(ToolWindowState(notes = listOf(row), filter = NoteFilter.CODE).showEmptyState)
        assertFalse(ToolWindowState(notes = listOf(row), filter = NoteFilter.FREE).showEmptyState)
    }

    @Test
    fun beforeTheFirstLoadAFilterShowsNoEmptyState() {
        assertFalse(ToolWindowState(filter = NoteFilter.FREE).showEmptyState)
    }
}
