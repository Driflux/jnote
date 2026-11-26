package io.github.driflux.jnote.relocate

import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileRelocationTest {
    private val snippet = listOf(
        "fun compute(total: Int): Int {",
        "    val doubled = total * 2",
        "    return doubled + 1",
        "}",
    )
    private val edited = listOf(
        "fun compute(total: Int): Int {",
        "    val tripled = total * 3",
        "    return tripled + 1",
        "}",
    )

    private fun filler(n: Int, tag: String = "pad") = List(n) { "// $tag $it unrelated filler text" }

    private fun note(
        uid: String,
        start: Int,
        snapshot: String?,
        status: NoteStatus = NoteStatus.OK,
        lines: Int = snippet.size,
    ) = Note(
        id = uid.hashCode().toLong(), uid = uid, kind = NoteKind.CODE, project = "p", relPath = "src/A.kt",
        className = null, startLine = start, endLine = start + lines - 1, snapshot = snapshot, status = status,
        title = uid, body = "", aiSource = null, sourceUrl = null, prompt = null, createdAt = 0, updatedAt = 0,
    )

    private val text = snippet.joinToString("\n")

    @Test
    fun movedCodeIsFoundAtItsNewLines() {
        val doc = filler(30) + snippet + filler(5)
        assertEquals(
            listOf(AnchorUpdate("a", "src/A.kt", 31, 34, NoteStatus.OK)),
            FileRelocation.relocate(listOf(note("a", 3, text)), doc),
        )
    }

    @Test
    fun exactMatchClearsApprox() {
        val doc = filler(2) + snippet
        assertEquals(
            NoteStatus.OK,
            FileRelocation.relocate(listOf(note("a", 3, text, NoteStatus.APPROX)), doc)!!.single().status,
        )
    }

    @Test
    fun editedCodeIsApproximate() {
        val doc = filler(10) + edited + filler(10)
        assertEquals(
            listOf(AnchorUpdate("a", "src/A.kt", 11, 14, NoteStatus.APPROX)),
            FileRelocation.relocate(listOf(note("a", 1, text)), doc),
        )
    }

    @Test
    fun missingCodeIsOrphanAndKeepsItsLines() {
        assertEquals(
            listOf(AnchorUpdate("a", "src/A.kt", 7, 10, NoteStatus.ORPHAN)),
            FileRelocation.relocate(listOf(note("a", 7, text)), filler(40)),
        )
    }

    @Test
    fun withoutSimilarityEditedCodeNeedsRelocationAndKeepsItsLines() {
        assertEquals(
            listOf(AnchorUpdate("a", "src/A.kt", 7, 10, NoteStatus.NEEDS_RELOCATE)),
            FileRelocation.relocate(listOf(note("a", 7, text)), filler(10) + edited, similarity = false),
        )
    }

    @Test
    fun withoutSimilarityMovedCodeIsStillFound() {
        assertEquals(
            listOf(AnchorUpdate("a", "src/A.kt", 11, 14, NoteStatus.OK)),
            FileRelocation.relocate(listOf(note("a", 1, text)), filler(10) + snippet, similarity = false),
        )
    }

    @Test
    fun notesWithoutSnapshotAreLeftOut() {
        val doc = filler(3) + snippet
        val updates = FileRelocation.relocate(listOf(note("none", 1, null), note("blank", 1, " \n "), note("a", 1, text)), doc)
        assertEquals(listOf("a"), updates!!.map { it.uid })
    }

    @Test
    fun progressCountsOnlyRelocatedNotes() {
        val doc = filler(3) + snippet
        val seen = mutableListOf<Pair<Int, Int>>()
        FileRelocation.relocate(listOf(note("a", 1, text), note("none", 1, null), note("b", 2, text)), doc) { done, total ->
            seen += done to total
        }
        assertEquals(listOf(1 to 2, 2 to 2), seen)
    }

    @Test
    fun cancelledRunReturnsNullInsteadOfOrphan() {
        // 찾지 못하는 노트의 유사도 검색 도중 취소한다. Relocator는 None을 돌려주지만 ORPHAN으로 적히면 안 된다
        val doc = (0 until 3000).map { "val v$it = compute($it, ${it * 7}) // note" }
        val notes = listOf(note("found", 1, doc.subList(100, 104).joinToString("\n")), note("missing", 1, text))
        var polls = 0
        FileRelocation.relocate(notes, doc, isCancelled = { polls++; false })
        val total = polls
        var seen = 0
        assertNull(FileRelocation.relocate(notes, doc, isCancelled = { ++seen >= total - 1 }))
    }

    @Test
    fun cancelledBeforeStartReturnsNull() {
        assertNull(FileRelocation.relocate(listOf(note("a", 1, text)), snippet, isCancelled = { true }))
    }
}
