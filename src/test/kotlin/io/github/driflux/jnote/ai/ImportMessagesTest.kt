package io.github.driflux.jnote.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus

class ImportMessagesTest : BasePlatformTestCase() {
    private val existing = Note(1, "u", NoteKind.FREE, null, null, null, null, null, null, NoteStatus.OK, "t", "", null, null, null, 0, 0)

    private fun update(title: String, body: Boolean, tags: Boolean) =
        PlannedItem.Update(FormatNote(uid = "u", title = title), existing, body, tags)

    fun testRowsListNewThenUpdatedAndMarkWhatGetsCleared() {
        val plan = ImportPlan(
            listOf(
                update("plain", false, false),
                PlannedItem.Create(FormatNote(uid = null, title = "fresh")),
                PlannedItem.Unchanged(FormatNote(uid = "u", title = "same"), existing),
                update("no body", true, false),
                update("no tags", false, true),
                update("neither", true, true),
            ),
        )

        assertEquals(
            listOf(
                JNoteBundle.message("dialog.importPreview.row.new", "fresh"),
                JNoteBundle.message("dialog.importPreview.row.updated", "plain"),
                JNoteBundle.message("dialog.importPreview.row.updated.clearsBody", "no body"),
                JNoteBundle.message("dialog.importPreview.row.updated.clearsTags", "no tags"),
                JNoteBundle.message("dialog.importPreview.row.updated.clearsBoth", "neither"),
            ),
            ImportMessages.rows(plan),
        )
        assertEquals(JNoteBundle.message("dialog.importPreview.summary", 1, 4, 1, 2), ImportMessages.summary(plan, 2))
    }

    fun testEveryProblemHasAMessage() {
        val problems = DocumentError.entries.map { ImportProblem.Rejected("d", it) } +
            FieldProblem.entries.map { ImportProblem.Skipped("d", SkippedItem(0, null, "title", it)) } +
            ImportProblem.Unreadable("d", "why") +
            ImportProblem.DuplicateAcrossDocuments("a, b", "x")
        for (problem in problems) {
            val text = ImportMessages.describe(problem)
            assertFalse(text, text.contains("!") || text.isBlank())
        }
    }

    fun testProblemListIsEscapedAndTruncated() {
        val problems = (1..12).map { ImportProblem.Unreadable("<b>$it</b>.json", "x") }

        val text = ImportMessages.problemList(problems)

        assertFalse(text, text.contains("<b>"))
        assertTrue(text, text.contains("&lt;b&gt;10&lt;/b&gt;.json"))
        assertFalse(text, text.contains("&lt;b&gt;11&lt;/b&gt;.json"))
        assertTrue(text, text.endsWith(JNoteBundle.message("notification.import.skipped.more", 2)))
        assertEquals("", ImportMessages.problemList(emptyList()))
    }
}
