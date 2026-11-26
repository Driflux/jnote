package io.github.driflux.jnote.ui

import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
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

    @Test fun blankTitleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NoteDraft("   ").toNewNote("p", anchor) }
    }

    @Test fun linesLabel() {
        assertEquals("3-5", anchor.linesLabel())
        assertEquals("7", anchor.copy(startLine = 7, endLine = 7).linesLabel())
    }
}
