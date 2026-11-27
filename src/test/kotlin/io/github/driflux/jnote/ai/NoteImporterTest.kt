package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

class NoteImporterTest {
    private lateinit var dir: Path
    private lateinit var db: Database
    private lateinit var notes: NoteRepository
    private lateinit var tags: TagRepository
    private var now = 1_000L

    @Before fun setUp() {
        dir = Files.createTempDirectory("jnote-import")
        db = Database(dir.resolve("jnote.db"))
        notes = NoteRepository(db) { now }
        tags = TagRepository(db)
    }

    @After fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val importer get() = NoteImporter(db) { now }

    private val anchor = FormatAnchor("github.com/org/repo", "src/A.kt", 3, 5, "A", "a\nb\nc")

    private fun all(): List<Note> = notes.list(null, allProjects = true).get()

    private fun tagsOf(note: Note) = tags.tagsOf(note.id).get().map { it.name }

    private fun existingCode(): Note {
        val note = notes.create(
            NewNote.code("github.com/org/repo", "src/A.kt", 3, 5, "old", body = "old body", className = "A", snapshot = "a\nb\nc",
                aiSource = "Claude", sourceUrl = "https://x", prompt = "p"),
        ).get()
        tags.replaceTags(note.id, listOf("keep", "drop")).get()
        return note
    }

    private fun sameAs(note: Note, tagNames: List<String>) = FormatNote(
        uid = note.uid, title = note.title, body = note.body, tags = tagNames,
        anchor = note.relPath?.let { FormatAnchor(note.project!!, it, note.startLine!!, note.endLine!!, note.className, note.snapshot) },
        aiSource = note.aiSource, sourceUrl = note.sourceUrl, prompt = note.prompt,
    )

    @Test fun createsNotesWithAndWithoutUidAndRegistersTags() {
        now = 5_000
        val result = importer.apply(
            listOf(
                FormatNote(uid = "given-uid", title = "code", body = "b", tags = listOf("new", "tags"), anchor = anchor, aiSource = "Claude"),
                FormatNote(uid = null, title = "free"),
            ),
        ).get()

        assertEquals(ImportResult(created = 2, updated = 0, unchanged = 0), result)
        val code = notes.getByUid("given-uid").get()!!
        assertEquals(NoteKind.CODE, code.kind)
        assertEquals(NoteStatus.OK, code.status)
        assertEquals(listOf(3, 5), listOf(code.startLine, code.endLine))
        assertEquals("a\nb\nc", code.snapshot)
        assertEquals(5_000L, code.createdAt)
        assertEquals(listOf("new", "tags"), tagsOf(code))
        assertEquals(listOf("new", "tags"), tags.list().get().map { it.name })
        val free = all().single { it.title == "free" }
        assertEquals(NoteKind.FREE, free.kind)
        assertTrue(free.uid.isNotBlank())
    }

    @Test fun updateReplacesTheWholeNoteAndClearsLeftOutFields() {
        val existing = existingCode()
        now = 9_000
        val incoming = FormatNote(uid = existing.uid, title = "new title", tags = listOf("keep"), anchor = anchor)

        assertEquals(ImportResult(0, 1, 0), importer.apply(listOf(incoming)).get())

        val updated = notes.get(existing.id).get()!!
        assertEquals(
            existing.copy(title = "new title", body = "", aiSource = null, sourceUrl = null, prompt = null, updatedAt = 9_000),
            updated,
        )
        assertEquals(listOf("keep"), tagsOf(updated))
        assertTrue("dropped tags stay in the dictionary", "drop" in tags.list().get().map { it.name })
    }

    @Test fun identicalItemIsUnchangedAndNotWritten() {
        val existing = existingCode()
        now = 9_000

        assertEquals(ImportResult(0, 0, 1), importer.apply(listOf(sameAs(existing, listOf("drop", "keep")))).get())

        assertEquals(existing, notes.get(existing.id).get())
    }

    @Test fun planClassifiesAndFlagsEmptiedBodyAndTags() {
        val existing = existingCode()
        val same = sameAs(existing, listOf("keep", "drop"))
        val plan = importer.plan(
            listOf(
                FormatNote(uid = "fresh", title = "new"),
                same,
                same.copy(title = "retitled"),
                same.copy(body = "", tags = emptyList()),
            ),
        ).get()

        assertEquals(1, plan.creates.size)
        assertEquals(1, plan.unchanged.size)
        assertEquals(listOf(false to false, true to true), plan.updates.map { it.emptiedBody to it.emptiedTags })
        assertEquals(existing, plan.updates.first().existing)
        assertEquals("plan writes nothing", listOf(existing), all())
    }

    @Test fun locationStatusIsKeptOnlyWhileTheLocationIsTheSame() {
        val existing = existingCode()
        notes.updateAnchors(listOf(AnchorUpdate(existing.uid, "src/A.kt", 3, 5, NoteStatus.APPROX))).get()
        val same = sameAs(existing, listOf("keep"))

        importer.apply(listOf(same.copy(anchor = anchor.copy(snapshot = "changed")))).get()
        assertEquals(NoteStatus.APPROX, notes.get(existing.id).get()!!.status)

        importer.apply(listOf(same.copy(anchor = anchor.copy(startLine = 4)))).get()
        assertEquals(NoteStatus.OK, notes.get(existing.id).get()!!.status)
    }

    @Test fun codeNoteCanBecomeAFreeNote() {
        val existing = existingCode()

        importer.apply(listOf(FormatNote(uid = existing.uid, title = "now free"))).get()

        val updated = notes.get(existing.id).get()!!
        assertEquals(NoteKind.FREE, updated.kind)
        assertEquals(listOf(null, null, null, null), listOf(updated.project, updated.relPath, updated.startLine, updated.snapshot))
    }

    @Test fun importNeverDeletesNotesLeftOutOfTheDocument() {
        val existing = existingCode()

        importer.apply(emptyList()).get()
        importer.apply(listOf(FormatNote(uid = null, title = "other"))).get()

        assertTrue(all().any { it.id == existing.id })
    }

    @Test fun applyDecidesAgainAtApplyTime() {
        val existing = existingCode()
        val incoming = sameAs(existing, emptyList()).copy(title = "edited")
        val plan = importer.plan(listOf(incoming, FormatNote(uid = "later", title = "created elsewhere"))).get()
        assertEquals(1, plan.updates.size)
        // 미리보기 뒤에 다른 IDE가 노트를 지우고, 계획에서 새로 만들 노트를 먼저 만들었다
        notes.delete(existing.id).get()
        importer.apply(listOf(FormatNote(uid = "later", title = "created elsewhere"))).get()

        assertEquals(ImportResult(1, 0, 1), importer.apply(plan.items.map { it.note }).get())

        assertEquals("edited", notes.getByUid(existing.uid).get()!!.title)
        assertEquals(1, all().count { it.uid == "later" })
    }

    @Test fun cancelRollsBackEverything() {
        val existing = existingCode()
        var seen = 0
        try {
            importer.apply(
                listOf(FormatNote(uid = null, title = "first"), sameAs(existing, emptyList()).copy(title = "second")),
            ) { if (++seen == 2) throw CancellationException() }.get()
            fail("expected cancellation")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is CancellationException)
        }

        assertEquals(listOf(existing), all())
        assertEquals(listOf("drop", "keep"), tagsOf(existing))
    }

    @Test fun twoProcessesImportingTheSameNewUidsDoNotCollide() {
        val other = Database(dir.resolve("jnote.db"))
        try {
            val batch = (1..20).map { FormatNote(uid = "uid-$it", title = "note $it", tags = listOf("t$it")) }
            val runs = (1..5).flatMap { listOf(NoteImporter(db).apply(batch), NoteImporter(other).apply(batch)) }
            CompletableFuture.allOf(*runs.toTypedArray()).get()

            assertEquals(20, all().size)
            assertEquals(20, runs.sumOf { it.get().created })
        } finally {
            other.close()
        }
    }
}
