package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolWindowStateTest {
    private val row = NoteRows.of(
        NoteSummary(1, "u", NoteKind.FREE, null, null, null, null, null, NoteStatus.OK, "t", null, 1),
        null,
        "free",
    )
    private val codeRow = NoteRows.of(
        NoteSummary(2, "c", NoteKind.CODE, "p", "a.kt", null, 1, 1, NoteStatus.OK, "t", null, 1),
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
        val state = ToolWindowState(notes = listOf(tagged, sqlOnly, row.copy(summary = row.summary.copy(id = 3))))
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

    private fun codeIn(id: Long, project: String, relPath: String, current: String = "p") = NoteRows.of(
        NoteSummary(id, "u$id", NoteKind.CODE, project, relPath, null, 1, 1, NoteStatus.OK, "t$id", null, 1),
        current,
        "free",
    )

    @Test
    fun currentFileOnlyKeepsCodeNotesOfThisProjectInThatFile() {
        val here = codeIn(10, "p", "src/A.kt")
        val otherFile = codeIn(11, "p", "src/B.kt")
        val otherProject = codeIn(12, "q", "src/A.kt")
        val state = ToolWindowState(notes = listOf(row, here, otherFile, otherProject), currentFileOnly = true, currentRelPath = "src/A.kt")
        assertEquals(listOf(10L), state.visibleNotes.map { it.summary.id })
    }

    @Test
    fun currentFileOffIgnoresThePath() {
        val state = ToolWindowState(notes = listOf(row, codeIn(10, "p", "src/A.kt")), currentFileOnly = false, currentRelPath = "src/Z.kt")
        assertEquals(2, state.visibleNotes.size)
    }

    @Test
    fun currentFileOnlyWithNoOpenFileMatchesNothingAndShowsTheEmptyState() {
        val state = ToolWindowState(notes = listOf(row, codeIn(10, "p", "src/A.kt")), currentFileOnly = true, currentRelPath = null)
        assertTrue(state.visibleNotes.isEmpty())
        assertTrue(state.showEmptyState)
    }

    @Test
    fun currentFileCombinesWithTheKindFilter() {
        val state = ToolWindowState(notes = listOf(codeIn(10, "p", "src/A.kt")), filter = NoteFilter.FREE, currentFileOnly = true, currentRelPath = "src/A.kt")
        assertTrue(state.visibleNotes.isEmpty())
    }

    private fun aiRow(id: Long, aiSource: String?) = NoteRows.of(
        NoteSummary(id, "u$id", NoteKind.CODE, "p", "a.kt", null, 1, 1, NoteStatus.OK, "t$id", aiSource, 1),
        "p",
        "free",
    )

    @Test
    fun aiOnlyKeepsOnlyNotesWithAnAiSource() {
        val state = ToolWindowState(notes = listOf(aiRow(1, "Claude"), aiRow(2, null), aiRow(3, "  "), aiRow(4, "GPT")), aiOnly = true)
        assertEquals(listOf(1L, 4L), state.visibleNotes.map { it.summary.id })
    }

    @Test
    fun aiOffKeepsEveryNote() {
        val state = ToolWindowState(notes = listOf(aiRow(1, "Claude"), aiRow(2, null)), aiOnly = false)
        assertEquals(2, state.visibleNotes.size)
    }

    @Test
    fun aiOnlyCombinesWithTheKindAndTagFilters() {
        val tagged = aiRow(1, "Claude").copy(tags = listOf(io.github.driflux.jnote.model.Tag(5, "x")))
        val plain = aiRow(2, "Claude")
        val human = aiRow(3, null).copy(tags = listOf(io.github.driflux.jnote.model.Tag(5, "x")))
        val state = ToolWindowState(notes = listOf(tagged, plain, human), aiOnly = true, selectedTagIds = setOf(5), filter = NoteFilter.CODE)
        assertEquals(listOf(1L), state.visibleNotes.map { it.summary.id })
    }

    @Test
    fun aiFilterIsTheReasonOnlyWhenTurningItOffShowsNotes() {
        val handNote = aiRow(1, null).copy(tags = listOf(io.github.driflux.jnote.model.Tag(5, "x")))
        assertTrue(ToolWindowState(notes = listOf(handNote), aiOnly = true, selectedTagIds = setOf(5)).aiFilterEmptiedTheList)
        assertFalse("another filter also empties the list", ToolWindowState(notes = listOf(handNote), aiOnly = true, selectedTagIds = setOf(6)).aiFilterEmptiedTheList)
        assertFalse("nothing is hidden", ToolWindowState(notes = listOf(aiRow(1, "Claude")), aiOnly = true).aiFilterEmptiedTheList)
        assertFalse("filter is off", ToolWindowState(notes = listOf(handNote), aiOnly = false).aiFilterEmptiedTheList)
    }

    @Test
    fun aiOnlyWithNoAiNotesShowsTheEmptyState() {
        assertTrue(ToolWindowState(notes = listOf(aiRow(1, null)), aiOnly = true).showEmptyState)
    }
}
