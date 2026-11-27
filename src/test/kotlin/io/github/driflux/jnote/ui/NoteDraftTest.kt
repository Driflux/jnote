package io.github.driflux.jnote.ui

import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.NoteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteDraftTest {
    private val anchor = CodeAnchor("src/A.kt", 3, 5, "snap\nshot", "A.b")

    @Test fun mapsAnchorAndTextToCodeNote() {
        val note = NoteDraft("  Title ", " body\nmore \n", "Claude", "https://x.test/c", "why?").toNewNote("proj", anchor)
        assertEquals(NoteKind.CODE, note.kind)
        assertEquals("proj", note.project)
        assertEquals("src/A.kt", note.relPath)
        assertEquals(3, note.startLine)
        assertEquals(5, note.endLine)
        assertEquals("snap\nshot", note.snapshot)
        assertEquals("A.b", note.className)
        assertEquals(NoteStatus.OK, note.status)
        assertEquals("Title", note.title)
        assertEquals("body\nmore", note.body)
        assertEquals("Claude", note.aiSource)
        assertEquals("https://x.test/c", note.sourceUrl)
        assertEquals("why?", note.prompt)
    }

    @Test fun blankAiFieldsBecomeNull() {
        val note = NoteDraft("T", aiSource = "  ", sourceUrl = "", prompt = "\n").toNewNote("proj", anchor)
        assertNull(note.aiSource)
        assertNull(note.sourceUrl)
        assertNull(note.prompt)
        assertEquals("", note.body)
    }

    @Test fun anyAiNameIsAccepted() = assertEquals("My local model", NoteDraft("T", aiSource = "My local model").toNewNote("p", anchor).aiSource)

    @Test fun missingClassNameStaysNull() =
        assertNull(NoteDraft("T").toNewNote("p", anchor.copy(className = null)).className)

    @Test fun freeNoteHasNoProjectNorAnchorAndKeepsTrimmedText() {
        val note = NoteDraft(" select 1 ", " from t\n", "Claude", " ", "why").toFreeNote()
        assertEquals(NoteKind.FREE, note.kind)
        assertNull(note.project)
        assertNull(note.relPath)
        assertNull(note.startLine)
        assertNull(note.snapshot)
        assertEquals("select 1", note.title)
        assertEquals("from t", note.body)
        assertEquals("Claude", note.aiSource)
        assertNull(note.sourceUrl)
        assertEquals("why", note.prompt)
    }

    @Test fun blankTitleIsRejectedForFreeNotes() {
        assertThrows(IllegalArgumentException::class.java) { NoteDraft(" ").toFreeNote() }
    }

    @Test fun blankTitleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NoteDraft("   ").toNewNote("p", anchor) }
    }

    private val saved = Note(
        7, "uid-1", NoteKind.CODE, "proj", "src/A.kt", "A.b", 3, 5, "snap", NoteStatus.APPROX,
        "Old", "old body", "Claude", "http://old", "old prompt", 10, 20,
    )

    @Test fun editPrefillsFromTheNote() {
        assertEquals(NoteDraft("Old", "old body", "Claude", "http://old", "old prompt"), NoteDraft.of(saved))
        assertEquals(NoteDraft("T"), NoteDraft.of(saved.copy(title = "T", body = "", aiSource = null, sourceUrl = null, prompt = null)))
    }

    @Test fun toTextTrimsAndDropsBlankAiFields() {
        assertEquals(NoteText("New", "new body", "Gemini", null, null), NoteDraft(" New ", " new body ", "Gemini", "", " ").toText())
    }

    @Test fun toTextRejectsBlankTitle() {
        assertThrows(IllegalArgumentException::class.java) { NoteDraft(" ").toText() }
    }

    @Test fun anchorOrNullFollowsTheKind() {
        assertEquals(CodeAnchor("src/A.kt", 3, 5, "snap", "A.b"), saved.anchorOrNull())
        assertEquals("", saved.copy(snapshot = null).anchorOrNull()?.snapshot)
        val free = Note(8, "u", NoteKind.FREE, null, null, null, null, null, null, NoteStatus.OK, "q", "", null, null, null, 1, 1)
        assertNull(free.anchorOrNull())
    }

    @Test fun linesLabel() {
        assertEquals("3-5", anchor.linesLabel())
        assertEquals("7", anchor.copy(startLine = 7, endLine = 7).linesLabel())
    }

    @Test fun tagNamesTrimAndDropBlanksAndDuplicates() {
        assertEquals(listOf("a", "b"), NoteDraft("t", tags = listOf(" a", "", "b", "a ")).tagNames())
        assertEquals(emptyList<String>(), NoteDraft("t").tagNames())
    }

    @Test fun ofCarriesTheGivenTags() {
        val note = NoteDraft("t").toFreeNote().let {
            io.github.driflux.jnote.model.Note(1, "u", it.kind, null, null, null, null, null, null, it.status, "t", "", null, null, null, 1, 1)
        }
        assertEquals(listOf("x", "y"), NoteDraft.of(note, listOf("x", "y")).tags)
        assertEquals(emptyList<String>(), NoteDraft.of(note).tags)
    }
}
