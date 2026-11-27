package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRowsTest {
    private fun code(
        project: String = "github.com/org/repo",
        className: String? = "Foo",
        start: Int = 3,
        end: Int = 5,
        aiSource: String? = null,
        relPath: String = "src/Foo.kt",
    ) = NoteSummary(1, "u", NoteKind.CODE, project, relPath, className, start, end, NoteStatus.OK, "title", aiSource, 1)

    private fun free(aiSource: String? = null) =
        NoteSummary(2, "u2", NoteKind.FREE, null, null, null, null, null, NoteStatus.OK, "q", aiSource, 1)

    @Test
    fun codeSubtitleIsClassAndLineRange() {
        assertEquals("Foo · L3-5", NoteRows.subtitle(code(), "Free note"))
    }

    @Test
    fun singleLineRangeShowsOneNumber() {
        assertEquals("Foo · L7", NoteRows.subtitle(code(start = 7, end = 7), "Free note"))
    }

    @Test
    fun missingClassNameFallsBackToFileName() {
        assertEquals("Foo.kt · L3-5", NoteRows.subtitle(code(className = null), "Free note"))
        assertEquals("Foo.kt · L3-5", NoteRows.subtitle(code(className = " "), "Free note"))
    }

    @Test
    fun freeSubtitleIsTheGivenLabel() {
        assertEquals("Free note", NoteRows.subtitle(free(), "Free note"))
        assertEquals("자유 노트", NoteRows.subtitle(free(), "자유 노트"))
    }

    @Test
    fun badgeAppearsOnlyForNonBlankAiSource() {
        assertFalse(NoteRows.of(code(), "k", "f").aiBadge)
        assertFalse(NoteRows.of(code(aiSource = ""), "k", "f").aiBadge)
        assertFalse(NoteRows.of(code(aiSource = "  "), "k", "f").aiBadge)
        assertTrue(NoteRows.of(code(aiSource = "Claude"), "k", "f").aiBadge)
        assertTrue(NoteRows.of(free(aiSource = "GPT"), null, "f").aiBadge)
    }

    @Test
    fun projectNameShownOnlyForOtherProjects() {
        assertNull(NoteRows.of(code(), "github.com/org/repo", "f").projectName)
        assertEquals("repo", NoteRows.of(code(), "github.com/org/other", "f").projectName)
        assertNull(NoteRows.of(free(), "github.com/org/other", "f").projectName)
    }

    @Test
    fun projectNameFromPathKeyIgnoresTrailingSlash() {
        assertEquals("proj", NoteRows.of(code(project = "/home/me/proj/"), "x", "f").projectName)
    }

    @Test
    fun rowKeepsSummaryAndTitle() {
        val summary = code()
        val row = NoteRows.of(summary, "k", "f")
        assertEquals(summary, row.summary)
        assertEquals("title", row.title)
    }

    @Test
    fun rowCarriesItsTags() {
        val tags = listOf(io.github.driflux.jnote.model.Tag(1, "a"), io.github.driflux.jnote.model.Tag(2, "b"))
        assertEquals(tags, NoteRows.of(code(), "github.com/org/repo", "free", tags).tags)
        assertTrue(NoteRows.of(code(), "github.com/org/repo", "free").tags.isEmpty())
    }
}
