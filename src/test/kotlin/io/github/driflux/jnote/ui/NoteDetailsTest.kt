package io.github.driflux.jnote.ui

import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDetailsTest {
    private fun code(
        className: String? = "Foo",
        snapshot: String? = "val x = 1",
        body: String = "why",
        aiSource: String? = null,
        sourceUrl: String? = null,
        prompt: String? = null,
        start: Int = 3,
        end: Int = 5,
    ) = Note(
        1, "u", NoteKind.CODE, "p", "src/Foo.kt", className, start, end, snapshot, NoteStatus.OK,
        "title", body, aiSource, sourceUrl, prompt, 1, 1,
    )

    private fun free(body: String = "select 1") = Note(
        2, "u2", NoteKind.FREE, null, null, null, null, null, null, NoteStatus.OK,
        "q", body, null, null, null, 1, 1,
    )

    @Test
    fun codeNoteShowsClassLocationSnippetAndBody() {
        val d = NoteDetails.of(code())
        assertEquals("title", d.title)
        assertEquals("Foo", d.className)
        assertEquals("src/Foo.kt:L3-5", d.location)
        assertEquals("val x = 1", d.snippet)
        assertEquals("why", d.body)
        assertFalse(d.monospaceBody)
        assertTrue(d.canGoToCode)
        assertNull(d.ai)
    }

    @Test
    fun singleLineLocationHasOneNumber() {
        assertEquals("src/Foo.kt:L7", NoteDetails.of(code(start = 7, end = 7)).location)
    }

    @Test
    fun missingClassAndEmptySnippetAreNull() {
        val d = NoteDetails.of(code(className = " ", snapshot = ""))
        assertNull(d.className)
        assertNull(d.snippet)
    }

    @Test
    fun freeNoteIsMonospaceWithoutTargetOrLocation() {
        val d = NoteDetails.of(free())
        assertTrue(d.monospaceBody)
        assertFalse(d.canGoToCode)
        assertNull(d.location)
        assertNull(d.snippet)
    }

    @Test
    fun aiSectionAppearsOnlyWithASource() {
        assertNull(NoteDetails.of(code(aiSource = " ", prompt = "p")).ai)
        val d = NoteDetails.of(code(aiSource = "Claude", sourceUrl = "https://x.test", prompt = ""))
        assertEquals(AiSourceDetail("Claude", "https://x.test", null), d.ai)
    }

    @Test
    fun relPathSafety() {
        assertTrue(CodeTargets.isSafeRelPath("src/Foo.kt"))
        assertTrue(CodeTargets.isSafeRelPath("a..b/c.kt"))
        assertFalse(CodeTargets.isSafeRelPath(""))
        assertFalse(CodeTargets.isSafeRelPath("/etc/passwd"))
        assertFalse(CodeTargets.isSafeRelPath("../x.kt"))
        assertFalse(CodeTargets.isSafeRelPath("src/../../x.kt"))
        assertFalse(CodeTargets.isSafeRelPath("src\\..\\x.kt"))
        assertFalse(CodeTargets.isSafeRelPath("C:/x.kt", windows = true))
        assertFalse(CodeTargets.isSafeRelPath("c:\\x.kt", windows = true))
        assertFalse(CodeTargets.isSafeRelPath("a:b/x.kt", windows = true))
        // POSIX에서는 콜론이 든 평범한 파일 이름이다
        assertTrue(CodeTargets.isSafeRelPath("a:b/x.kt", windows = false))
        assertTrue(CodeTargets.isSafeRelPath("1:b/x.kt", windows = true))
        assertFalse(CodeTargets.isSafeRelPath("\\\\host\\share"))
    }
}
