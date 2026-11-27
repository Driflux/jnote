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

        val deleted = deletion.delete(note.id, note.uid).get()!!

        assertEquals(note, deleted.note)
        assertEquals(listOf(tag.id), deleted.tagIds)
        assertNull(notes.get(note.id).get())
    }

    @Test
    fun deletingAMissingNoteGivesNull() {
        assertNull(deletion.delete(404, "none").get())
    }

    @Test
    fun deleteThenUndoRoundTripsIdentityTimestampsAndTags() {
        val note = notes.create(NewNote.code("p", "src/A.kt", 1, 2, "t", snapshot = "s")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(note.id, tag.id).get()
        now = 50_000

        val deleted = deletion.delete(note.id, note.uid).get()!!
        val restored = deletion.undo(deleted).get()

        assertEquals(note, restored)
        assertEquals(note, notes.get(note.id).get())
        assertEquals(listOf(tag), tags.tagsOf(note.id).get())
        assertTrue(notes.list("p", false).get().any { it.uid == note.uid })
    }

    @Test
    fun undoTwiceFailsTheSecondTime() {
        val note = notes.create(NewNote.free("t")).get()
        val deleted = deletion.delete(note.id, note.uid).get()!!
        deletion.undo(deleted).get()
        val second = deletion.undo(deleted)
        assertFalse(runCatching { second.get() }.isSuccess)
    }

    @Test
    fun undoRestoresIntoTheDatabaseTheNoteWasDeletedFrom() {
        val note = notes.create(NewNote.free("t")).get()
        val deleted = deletion.delete(note.id, note.uid).get()!!
        val other = Database(tmp.root.toPath().resolve("other.db"))
        try {
            deletion.undo(deleted).get()
            assertEquals(note, notes.get(note.id).get())
            assertNull(NoteRepository(other).get(note.id).get())
        } finally {
            other.close()
        }
    }

    @Test
    fun undoAfterTheDatabaseWasClosedFailsInsteadOfRestoringElsewhere() {
        val note = notes.create(NewNote.free("t")).get()
        val deleted = deletion.delete(note.id, note.uid).get()!!
        db.close()

        val error = assertThrows(ExecutionException::class.java) { deletion.undo(deleted).get() }

        assertTrue(error.cause is IllegalStateException)
    }

    @Test
    fun deleteReadsAndDeletesInOneTransaction() {
        val note = notes.create(NewNote.code("p", "src/A.kt", 1, 2, "t")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(note.id, tag.id).get()
        val (read, tagIds) = notes.deleteReturningTagIds(note.id, note.uid).get()!!
        assertEquals(note, read)
        assertEquals(listOf(tag.id), tagIds)
        assertNull(notes.get(note.id).get())
        assertNull(notes.deleteReturningTagIds(note.id, note.uid).get())
    }

    @Test
    fun deletingWithAReusedIdAndAnotherUidKeepsTheNewNote() {
        val old = notes.create(NewNote.free("old")).get()
        notes.delete(old.id).get()
        val reused = notes.create(NewNote.free("new")).get()
        assertEquals(old.id, reused.id)

        assertNull(deletion.delete(old.id, old.uid).get())

        assertEquals(reused, notes.get(reused.id).get())
    }
}
