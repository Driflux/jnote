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

    private val sql = io.github.driflux.jnote.model.Tag(10, "sql")
    private val todo = io.github.driflux.jnote.model.Tag(11, "todo")
    private val tagged = row.copy(tags = listOf(sql, todo))
    private val sqlOnly = codeRow.copy(tags = listOf(sql))

    @Test
    fun noChipOnMeansNoTagFiltering() {
        val state = ToolWindowState(notes = listOf(tagged, sqlOnly, row.copy(note = row.note.copy(id = 3))))
        assertEquals(3, state.visibleNotes.size)
        assertFalse(state.tagFilterActive)
    }

    @Test
    fun severalChipsNarrowDownAndRequireEveryTag() {
        val state = ToolWindowState(notes = listOf(tagged, sqlOnly), selectedTagIds = setOf(10))
        assertEquals(listOf(tagged, sqlOnly), state.visibleNotes)
        assertEquals(listOf(tagged), state.toggleTag(11).visibleNotes)
    }

    @Test
    fun tagFilterCombinesWithTheKindFilter() {
        val state = ToolWindowState(notes = listOf(tagged, sqlOnly), filter = NoteFilter.CODE, selectedTagIds = setOf(10))
        assertEquals(listOf(sqlOnly), state.visibleNotes)
    }

    @Test
    fun toggleTagTurnsItOnThenOff() {
        val on = ToolWindowState().toggleTag(10)
        assertEquals(setOf(10L), on.selectedTagIds)
        assertTrue(on.tagFilterActive)
        assertEquals(emptySet<Long>(), on.toggleTag(10).selectedTagIds)
    }

    @Test
    fun withTagsDropsChipsOfTagsThatNoLongerExist() {
        val state = ToolWindowState(selectedTagIds = setOf(10, 11)).withTags(listOf(sql))
        assertEquals(listOf(sql), state.tags)
        assertEquals(setOf(10L), state.selectedTagIds)
    }

    @Test
    fun aTagFilterThatMatchesNothingShowsTheEmptyState() {
        assertTrue(ToolWindowState(notes = listOf(row), selectedTagIds = setOf(10)).showEmptyState)
    }

    @Test
    fun searchKeepsOnlyMatchedRowsInListOrder() {
        val state = ToolWindowState(notes = listOf(row, codeRow), query = "ab", match = SearchMatch("ab", setOf(2L, 1L)))
        assertEquals(listOf(row, codeRow), state.visibleNotes)
        assertEquals(listOf(codeRow), state.copy(match = SearchMatch("ab", setOf(2L))).visibleNotes)
    }

    @Test
    fun aQueryShorterThanTheMinimumLeavesTheListAlone() {
        val state = ToolWindowState(notes = listOf(row, codeRow), query = "a", match = SearchMatch("ab", setOf(2L)))
        assertEquals(listOf(row, codeRow), state.visibleNotes)
        assertFalse(state.noSearchResults)
    }

    @Test
    fun searchAndKindFilterCombine() {
        val state = ToolWindowState(
            notes = listOf(row, codeRow), filter = NoteFilter.CODE, query = "ab", match = SearchMatch("ab", setOf(1L, 2L)),
        )
        assertEquals(listOf(codeRow), state.visibleNotes)
    }

    @Test
    fun noResultsOnlyOnceTheCurrentQueryAnswered() {
        val waiting = ToolWindowState(notes = listOf(row), query = "ab", match = SearchMatch("a-old", setOf(1L)))
        assertFalse(waiting.noSearchResults)
        val answered = ToolWindowState(notes = listOf(row), query = "ab", match = SearchMatch("ab", emptySet()))
        assertTrue(answered.noSearchResults)
        assertTrue(answered.showEmptyState)
    }

    @Test
    fun matchesPresentWithoutRowsAreNotNoResultsBeforeTheFirstLoad() {
        assertFalse(ToolWindowState(query = "ab", match = SearchMatch("ab", emptySet())).showEmptyState)
    }
}
