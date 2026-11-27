package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Tag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.SQLException
import java.util.concurrent.ExecutionException

class TagRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db by lazy { Database(tmp.root.toPath().resolve("jnote.db")) }
    private var now = 1_000L
    private val notes by lazy { NoteRepository(db) { now } }
    private val tags by lazy { TagRepository(db) }

    @After
    fun close() = db.close()

    private fun note(title: String = "n") = notes.create(NewNote.code("p", "a.kt", 1, 2, title)).get()
    private fun tag(name: String) = tags.getOrCreate(name).get()

    @Test
    fun getOrCreateIsIdempotentAndTrims() {
        val a = tag("sql")
        assertEquals(a, tag("sql"))
        assertEquals(a, tag("  sql "))
        assertEquals("sql", a.name)
        assertEquals(1, tags.list().get().size)
    }

    @Test
    fun namesAreCaseSensitive() {
        assertNotEquals(tag("SQL").id, tag("sql").id)
    }

    @Test
    fun blankNameIsRejected() {
        val e = assertThrows(ExecutionException::class.java) { tags.getOrCreate("   ").get() }
        assertTrue(e.cause is IllegalArgumentException)
        assertTrue(tags.list().get().isEmpty())
    }

    @Test
    fun commaInNameIsRejected() {
        val e = assertThrows(ExecutionException::class.java) { tags.getOrCreate("a,b").get() }
        assertTrue(e.cause is IllegalArgumentException)
        assertTrue(tags.list().get().isEmpty())
    }

    @Test
    fun concurrentGetOrCreateFromTwoConnectionsReturnsTheSameTag() {
        val other = Database(tmp.root.toPath().resolve("jnote.db"))
        try {
            val otherTags = TagRepository(other)
            tags.list().get()
            otherTags.list().get()
            repeat(20) { i ->
                val name = "race-$i"
                val a = tags.getOrCreate(name)
                val b = otherTags.getOrCreate(name)
                assertEquals(a.get(), b.get())
            }
            assertEquals(20, tags.list().get().size)
        } finally {
            other.close()
        }
    }

    @Test
    fun getOrCreateKeepsExistingRowWhenNameIsTaken() {
        val first = tag("dup")
        db.submit { c -> c.createStatement().use { it.executeUpdate("INSERT OR IGNORE INTO tag (name) VALUES ('dup')") } }.get()
        assertEquals(first, tag("dup"))
        assertEquals(1, tags.list().get().size)
    }

    @Test
    fun listIsSortedByName() {
        listOf("b", "c", "a").forEach { tag(it) }
        assertEquals(listOf("a", "b", "c"), tags.list().get().map { it.name })
    }

    @Test
    fun renameChangesTheName() {
        val t = tag("old")
        assertEquals(TagRenameResult.Renamed(Tag(t.id, "new")), tags.rename(t, " new ").get())
        assertEquals(listOf("new"), tags.list().get().map { it.name })
        assertEquals(TagRenameResult.Renamed(Tag(t.id, "new")), tags.rename(Tag(t.id, "new"), "new").get())
    }

    @Test
    fun renameConflictIsAValueAndChangesNothing() {
        val a = tag("a")
        tag("b")
        assertEquals(TagRenameResult.Conflict, tags.rename(a, "b").get())
        assertEquals(listOf("a", "b"), tags.list().get().map { it.name })
    }

    @Test
    fun renameIsAConflictWhenAnotherWriterTakesTheNameDuringTheUpdate() {
        val a = tag("a")
        db.submit { c ->
            c.createStatement().use {
                it.execute("CREATE TEMP TRIGGER race BEFORE UPDATE OF name ON tag BEGIN INSERT INTO tag (name) VALUES (NEW.name); END")
            }
        }.get()

        assertEquals(TagRenameResult.Conflict, tags.rename(a, "b").get())
        assertEquals("a", tags.list().get().single { it.id == a.id }.name)
    }

    @Test
    fun renameReportsMissingAndBlank() {
        val a = tag("a")
        assertEquals(TagRenameResult.Stale, tags.rename(Tag(999, "a"), "x").get())
        assertEquals(TagRenameResult.InvalidName, tags.rename(a, " ").get())
    }

    @Test
    fun renameRejectsCommaAndChangesNothing() {
        val a = tag("a")
        assertEquals(TagRenameResult.InvalidName, tags.rename(a, "x,y").get())
        assertEquals(listOf("a"), tags.list().get().map { it.name })
    }

    @Test
    fun renameOfMissingIdToExistingNameIsStale() {
        tag("taken")
        assertEquals(TagRenameResult.Stale, tags.rename(Tag(999, "gone"), "taken").get())
    }

    @Test
    fun renameAndDeleteOfAnIdReusedByAnotherConnectionLeaveTheNewTagAlone() {
        val other = Database(tmp.root.toPath().resolve("jnote.db"))
        try {
            val otherTags = TagRepository(other)
            val seen = tag("old")
            assertTrue(otherTags.delete(seen).get())
            val reused = otherTags.getOrCreate("new").get()
            assertEquals(seen.id, reused.id)

            assertEquals(TagRenameResult.Stale, tags.rename(seen, "renamed").get())
            assertFalse(tags.delete(seen).get())
            assertEquals(listOf(reused), tags.list().get())
        } finally {
            other.close()
        }
    }

    @Test
    fun renameAndDeleteOfATagRenamedByAnotherConnectionAreStale() {
        val other = Database(tmp.root.toPath().resolve("jnote.db"))
        try {
            val seen = tag("old")
            assertEquals(TagRenameResult.Renamed(Tag(seen.id, "theirs")), TagRepository(other).rename(seen, "theirs").get())

            assertEquals(TagRenameResult.Stale, tags.rename(seen, "mine").get())
            assertFalse(tags.delete(seen).get())
            assertEquals(listOf(Tag(seen.id, "theirs")), tags.list().get())
        } finally {
            other.close()
        }
    }

    @Test
    fun deleteRemovesTagAndLinksButKeepsNotes() {
        val n = note()
        val t = tag("x")
        tags.attach(n.id, t.id).get()
        assertTrue(tags.delete(t).get())
        assertFalse(tags.delete(t).get())
        assertTrue(tags.list().get().isEmpty())
        assertTrue(tags.tagsOf(n.id).get().isEmpty())
        assertNotNull(notes.get(n.id).get())
    }

    @Test
    fun detachKeepsTheTagInTheDictionary() {
        val n = note()
        val t = tag("x")
        tags.attach(n.id, t.id).get()
        assertTrue(tags.detach(n.id, t.id).get())
        assertFalse(tags.detach(n.id, t.id).get())
        assertTrue(tags.tagsOf(n.id).get().isEmpty())
        assertEquals(listOf(t), tags.list().get())
    }

    @Test
    fun attachTwiceIsIdempotent() {
        val n = note()
        val t = tag("x")
        tags.attach(n.id, t.id).get()
        tags.attach(n.id, t.id).get()
        assertEquals(listOf(t), tags.tagsOf(n.id).get())
    }

    @Test
    fun attachToMissingNoteOrTagFails() {
        val n = note()
        val t = tag("x")
        val e1 = assertThrows(ExecutionException::class.java) { tags.attach(999, t.id).get() }
        assertTrue(e1.cause is SQLException)
        val e2 = assertThrows(ExecutionException::class.java) { tags.attach(n.id, 999).get() }
        assertTrue(e2.cause is SQLException)
    }

    @Test
    fun tagsOfNoteAreSortedByName() {
        val n = note()
        listOf("b", "c", "a").map { tag(it) }.forEach { tags.attach(n.id, it.id).get() }
        assertEquals(listOf("a", "b", "c"), tags.tagsOf(n.id).get().map { it.name })
    }

    @Test
    fun notesOfTagUseRecentFirstOrder() {
        val a = note("a")
        val b = note("b")
        val other = note("other")
        val t = tag("x")
        tags.attach(a.id, t.id).get()
        tags.attach(b.id, t.id).get()
        assertEquals(listOf(b.id, a.id), tags.notesOf(t.id).get().map { it.id })
        now = 2_000
        notes.update(a.copy(title = "a2")).get()
        assertEquals(listOf(a.id, b.id), tags.notesOf(t.id).get().map { it.id })
        assertFalse(tags.notesOf(t.id).get().any { it.id == other.id })
    }

    @Test
    fun deletingNoteCascadesLinksButKeepsTag() {
        val n = note()
        val t = tag("x")
        tags.attach(n.id, t.id).get()
        notes.delete(n.id).get()
        assertTrue(tags.notesOf(t.id).get().isEmpty())
        assertEquals(listOf(t), tags.list().get())
    }

    @Test
    fun updatingNoteKeepsItsTags() {
        val n = note()
        val t = tag("x")
        tags.attach(n.id, t.id).get()
        notes.update(n.copy(title = "changed")).get()
        assertEquals(listOf(t), tags.tagsOf(n.id).get())
    }

    @Test
    fun tagsByPathGroupsTagsByNoteAndSkipsOtherFilesAndUntaggedNotes() {
        val a = notes.create(NewNote.code("p", "a.kt", 1, 2, "a")).get()
        val untagged = notes.create(NewNote.code("p", "a.kt", 5, 6, "untagged")).get()
        val elsewhere = notes.create(NewNote.code("p", "b.kt", 1, 2, "b")).get()
        val sql = tag("sql")
        val api = tag("api")
        tags.attach(a.id, sql.id).get()
        tags.attach(a.id, api.id).get()
        tags.attach(elsewhere.id, sql.id).get()

        val result = tags.tagsByPath("p", "a.kt").get()

        assertEquals(mapOf(a.id to listOf(api, sql)), result)
        assertFalse(result.containsKey(untagged.id))
    }

    @Test
    fun replaceTagsCreatesMissingTagsAndMakesTheSetExact() {
        val n = note()
        val keep = tag("keep")
        val gone = tag("gone")
        tags.attach(n.id, keep.id).get()
        tags.attach(n.id, gone.id).get()

        val result = tags.replaceTags(n.id, listOf(" new ", "keep", "keep", "")).get()

        assertEquals(listOf("keep", "new"), result.map { it.name })
        assertEquals(result, tags.tagsOf(n.id).get())
        assertEquals(keep, result.first { it.name == "keep" })
        assertEquals(listOf("gone", "keep", "new"), tags.list().get().map { it.name })
    }

    @Test
    fun replaceTagsWithEmptyListDetachesEverything() {
        val n = note()
        tags.replaceTags(n.id, listOf("a", "b")).get()
        assertTrue(tags.replaceTags(n.id, emptyList()).get().isEmpty())
        assertTrue(tags.tagsOf(n.id).get().isEmpty())
    }

    @Test
    fun replaceTagsOnAMissingNoteFailsAndRegistersNothing() {
        val e = assertThrows(ExecutionException::class.java) { tags.replaceTags(999, listOf("ghost")).get() }
        assertTrue(e.cause is SQLException)
        assertTrue(tags.list().get().isEmpty())
    }

    @Test
    fun replaceTagsRejectsCommaAndLeavesTheOldSet() {
        val n = note()
        tags.replaceTags(n.id, listOf("a")).get()
        val e = assertThrows(ExecutionException::class.java) { tags.replaceTags(n.id, listOf("b", "c,d")).get() }
        assertTrue(e.cause is IllegalArgumentException)
        assertEquals(listOf("a"), tags.tagsOf(n.id).get().map { it.name })
        assertEquals(listOf("a"), tags.list().get().map { it.name })
    }

    @Test
    fun assignmentsGroupsTagsByNoteAndSkipsUntaggedNotes() {
        val a = note("a")
        val b = note("b")
        note("untagged")
        tags.replaceTags(a.id, listOf("y", "x")).get()
        tags.replaceTags(b.id, listOf("x")).get()

        val result = tags.assignments().get()

        assertEquals(setOf(a.id, b.id), result.keys)
        assertEquals(listOf("x", "y"), result.getValue(a.id).map { it.name })
        assertEquals(listOf("x"), result.getValue(b.id).map { it.name })
    }
}
