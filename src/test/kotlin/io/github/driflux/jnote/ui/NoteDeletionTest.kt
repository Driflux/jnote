package io.github.driflux.jnote.ui

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ExecutionException

class NoteDeletionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db by lazy { Database(tmp.root.toPath().resolve("jnote.db")) }
    private var now = 1_000L
    private val notes by lazy { NoteRepository(db) { now } }
    private val tags by lazy { TagRepository(db) }
    private val deletion by lazy { NoteDeletion(db) }

    @After
    fun close() = db.close()

    @Test
    fun deleteRemovesTheNoteAndRemembersItsTags() {
        val note = notes.create(NewNote.code("p", "src/A.kt", 1, 2, "t")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(note.id, tag.id).get()

        val deleted = deletion.delete(listOf(note.id)).get().single()

        assertEquals(note, deleted.note)
        assertEquals(listOf(tag.id), deleted.tagIds)
        assertNull(notes.get(note.id).get())
    }

    @Test
    fun deletingAMissingNoteGivesNull() {
        assertTrue(deletion.delete(listOf(404)).get().isEmpty())
    }

    @Test
    fun deleteThenUndoRoundTripsIdentityTimestampsAndTags() {
        val note = notes.create(NewNote.code("p", "src/A.kt", 1, 2, "t", snapshot = "s")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(note.id, tag.id).get()
        now = 50_000

        val deleted = deletion.delete(listOf(note.id)).get().single()
        val restored = deletion.undo(listOf(deleted)).get().single()

        assertEquals(note, restored)
        assertEquals(note, notes.get(note.id).get())
        assertEquals(listOf(tag), tags.tagsOf(note.id).get())
        assertTrue(notes.list("p", false).get().any { it.uid == note.uid })
    }

    @Test
    fun undoTwiceFailsTheSecondTime() {
        val note = notes.create(NewNote.free("t")).get()
        val deleted = deletion.delete(listOf(note.id)).get().single()
        deletion.undo(listOf(deleted)).get()
        val second = deletion.undo(listOf(deleted))
        assertFalse(runCatching { second.get() }.isSuccess)
    }

    @Test
    fun undoRestoresIntoTheDatabaseTheNoteWasDeletedFrom() {
        val note = notes.create(NewNote.free("t")).get()
        val deleted = deletion.delete(listOf(note.id)).get().single()
        // 지운 뒤 다른 DB로 바뀌어도 되돌리기는 원래 DB로 간다
        val other = Database(tmp.root.toPath().resolve("other.db"))
        try {
            deletion.undo(listOf(deleted)).get()
            assertEquals(note, notes.get(note.id).get())
            assertNull(NoteRepository(other).get(note.id).get())
        } finally {
            other.close()
        }
    }

    @Test
    fun undoAfterTheDatabaseWasClosedFailsInsteadOfRestoringElsewhere() {
        val note = notes.create(NewNote.free("t")).get()
        val deleted = deletion.delete(listOf(note.id)).get().single()
        db.close()

        val error = assertThrows(ExecutionException::class.java) { deletion.undo(listOf(deleted)).get() }

        assertTrue(error.cause is IllegalStateException)
    }

    @Test
    fun deleteReadsAndDeletesInOneTransaction() {
        val note = notes.create(NewNote.code("p", "src/A.kt", 1, 2, "t")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(note.id, tag.id).get()
        // 같은 DB 스레드에서 지우므로, 읽은 노트와 태그가 지워진 것과 같다
        val (read, tagIds) = notes.deleteReturningTagIds(listOf(note.id)).get().single()
        assertEquals(note, read)
        assertEquals(listOf(tag.id), tagIds)
        assertNull(notes.get(note.id).get())
        assertTrue(notes.deleteReturningTagIds(listOf(note.id)).get().isEmpty())
    }

    @Test
    fun deletingSeveralNotesRemovesThemTogetherAndUndoRestoresThemAll() {
        val a = notes.create(NewNote.code("p", "src/A.kt", 1, 2, "a")).get()
        val b = notes.create(NewNote.free("b")).get()
        val kept = notes.create(NewNote.free("kept")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(b.id, tag.id).get()

        val deleted = deletion.delete(listOf(a.id, 404, b.id)).get()

        assertEquals(listOf(a, b), deleted.map { it.note })
        assertNull(notes.get(a.id).get())
        assertNull(notes.get(b.id).get())
        assertEquals(kept, notes.get(kept.id).get())

        assertEquals(listOf(a, b), deletion.undo(deleted).get())
        assertEquals(a, notes.get(a.id).get())
        assertEquals(listOf(tag), tags.tagsOf(b.id).get())
    }

    @Test
    fun undoOfSeveralNotesRestoresNoneWhenOneCannotBeRestored() {
        val a = notes.create(NewNote.free("a")).get()
        val b = notes.create(NewNote.free("b")).get()
        val deleted = deletion.delete(listOf(a.id, b.id)).get()
        notes.restore(deleted[1].note, emptyList()).get()

        assertFalse(runCatching { deletion.undo(deleted).get() }.isSuccess)

        assertNull("the first note is rolled back with the failed second one", notes.get(a.id).get())
    }
}
