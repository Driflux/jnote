package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class NoteRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db by lazy { Database(tmp.root.toPath().resolve("jnote.db")) }
    private var now = 1_000L
    private val repo by lazy { NoteRepository(db) { now } }

    @After
    fun close() = db.close()

    private fun code(title: String = "t", project: String = "p1", relPath: String = "src/A.kt") =
        NewNote.code(project, relPath, 3, 5, title, body = "b", className = "A", snapshot = "snap")

    @Test
    fun createCodeNoteRoundTrips() {
        val created = repo.create(code()).get()
        assertEquals(created, repo.get(created.id).get())
        assertEquals(NoteKind.CODE, created.kind)
        assertEquals("p1", created.project)
        assertEquals("src/A.kt", created.relPath)
        assertEquals("A", created.className)
        assertEquals(3, created.startLine)
        assertEquals(5, created.endLine)
        assertEquals("snap", created.snapshot)
        assertEquals(NoteStatus.OK, created.status)
    }

    @Test
    fun createFreeNoteHasNoAnchorAndNoProject() {
        val created = repo.create(NewNote.free("query", "select 1")).get()
        assertEquals(NoteKind.FREE, created.kind)
        assertNull(created.project)
        assertNull(created.relPath)
        assertNull(created.startLine)
        assertNull(created.endLine)
        assertEquals("select 1", repo.get(created.id).get()?.body)
    }

    @Test
    fun illegalShapesAreRejectedByTheModel() {
        assertThrows(IllegalArgumentException::class.java) {
            NewNote(NoteKind.CODE, "p", null, null, 1, 2, null, title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewNote(NoteKind.CODE, "p", "a.kt", null, null, null, null, title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewNote(NoteKind.CODE, null, "a.kt", null, 1, 2, null, title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) { NewNote.code("p", "a.kt", 5, 4, "x") }
        assertThrows(IllegalArgumentException::class.java) {
            NewNote(NoteKind.FREE, "p", null, null, null, null, null, title = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NewNote(NoteKind.FREE, null, "a.kt", null, null, null, null, title = "x")
        }
    }

    @Test
    fun uidIsGeneratedUniqueAndStableAcrossUpdate() {
        val a = repo.create(code("a")).get()
        val b = repo.create(code("b")).get()
        UUID.fromString(a.uid)
        assertNotEquals(a.uid, b.uid)
        now = 2_000
        val updated = repo.update(a.copy(title = "a2")).get()!!
        assertEquals(a.uid, updated.uid)
        assertEquals(a.id, repo.getByUid(a.uid).get()?.id)
        assertNull(repo.getByUid("missing").get())
    }

    @Test
    fun timestampsComeFromTheInjectedClock() {
        val created = repo.create(code()).get()
        assertEquals(1_000L, created.createdAt)
        assertEquals(1_000L, created.updatedAt)
        now = 5_000
        val updated = repo.update(created.copy(title = "new", body = "nb")).get()!!
        assertEquals(1_000L, updated.createdAt)
        assertEquals(5_000L, updated.updatedAt)
        assertEquals("new", updated.title)
        assertEquals("nb", updated.body)
    }

    @Test
    fun updateCanChangeStatusAndLines() {
        val created = repo.create(code()).get()
        val updated = repo.update(created.copy(status = NoteStatus.ORPHAN, startLine = 10, endLine = 12)).get()!!
        assertEquals(NoteStatus.ORPHAN, updated.status)
        assertEquals(10, updated.startLine)
        assertEquals(12, updated.endLine)
    }

    @Test
    fun updateMissingReturnsNull() {
        val created = repo.create(code()).get()
        repo.delete(created.id).get()
        assertNull(repo.update(created).get())
    }

    @Test
    fun deleteRemovesNoteOnce() {
        val created = repo.create(code()).get()
        assertTrue(repo.delete(created.id).get())
        assertNull(repo.get(created.id).get())
        assertFalse(repo.delete(created.id).get())
    }

    @Test
    fun aiFieldsRoundTripAndAreNullByDefault() {
        val plain = repo.create(code()).get()
        assertNull(plain.aiSource)
        assertNull(plain.sourceUrl)
        assertNull(plain.prompt)

        val ai = repo.create(NewNote.free("q", aiSource = "Claude", sourceUrl = "https://x.test/1", prompt = "why?")).get()
        val read = repo.get(ai.id).get()!!
        assertEquals("Claude", read.aiSource)
        assertEquals("https://x.test/1", read.sourceUrl)
        assertEquals("why?", read.prompt)

        val codeAi = repo.create(NewNote.code("p1", "a.kt", 1, 1, "c", aiSource = "GPT", prompt = "p")).get()
        assertEquals("GPT", codeAi.aiSource)
        val cleared = repo.update(read.copy(aiSource = null, prompt = null)).get()!!
        assertNull(cleared.aiSource)
        assertNull(cleared.prompt)
    }

    @Test
    fun listIsMostRecentlyModifiedFirstWithIdTieBreak() {
        val a = repo.create(code("a")).get()
        val b = repo.create(code("b")).get()
        val c = repo.create(code("c")).get()
        assertEquals(listOf(c.id, b.id, a.id), repo.list("p1", false).get().map { it.id })
        now = 2_000
        repo.update(a.copy(title = "a2")).get()
        assertEquals(listOf(a.id, c.id, b.id), repo.list("p1", false).get().map { it.id })
    }

    @Test
    fun listByProjectIncludesGlobalFreeNotesButNotOtherProjects() {
        val mine = repo.create(code("mine", project = "p1")).get()
        val other = repo.create(code("other", project = "p2")).get()
        val free = repo.create(NewNote.free("free")).get()
        assertEquals(setOf(mine.id, free.id), repo.list("p1", false).get().map { it.id }.toSet())
        assertEquals(setOf(other.id, free.id), repo.list("p2", false).get().map { it.id }.toSet())
        assertEquals(listOf(free.id), repo.list(null, false).get().map { it.id })
        assertEquals(setOf(mine.id, other.id, free.id), repo.list("p1", true).get().map { it.id }.toSet())
    }

    @Test
    fun constraintFailureSurfacesAsFailedFuture() {
        // 모델이 막는 상태는 DB 제약이 마지막 방어선이다. 우회로 잘못된 행을 넣어 오류가 future로 드러나는지 본다
        val created = repo.create(code()).get()
        val failed = db.submit { c ->
            c.createStatement().use { it.executeUpdate("UPDATE note SET status = 'BAD' WHERE id = ${created.id}") }
        }
        val e = assertThrows(java.util.concurrent.ExecutionException::class.java) { failed.get() }
        assertTrue(e.cause is java.sql.SQLException)
        assertNotNull(repo.get(created.id).get())
    }
}
