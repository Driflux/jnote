package io.github.driflux.jnote.ai

import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.NoteStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class NoteExporterTest {
    private lateinit var dir: Path
    private lateinit var db: Database
    private lateinit var notes: NoteRepository
    private lateinit var tags: TagRepository
    private var now = 1_000L

    @Before fun setUp() {
        dir = Files.createTempDirectory("jnote-export")
        db = Database(dir.resolve("jnote.db"))
        notes = NoteRepository(db) { now++ }
        tags = TagRepository(db)
    }

    @After fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val exporter get() = NoteExporter(db)

    private fun decode(path: Path): List<FormatNote> =
        (NoteFormatCodec.decode(Files.readString(path)) as DecodeResult.Decoded).also { assertEquals(emptyList<SkippedItem>(), it.skipped) }.notes

    private fun otherFiles() = Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { !it.startsWith("jnote.db") }.toList() }

    @Test fun exportsEveryNoteOfEveryProjectWithTagsNewestFirst() {
        val code = notes.create(
            NewNote.code("github.com/org/repo", "src/A.kt", 3, 5, "code", body = "b", className = "A", snapshot = "x\ny\nz",
                status = NoteStatus.APPROX, aiSource = "Claude", prompt = "why"),
        ).get()
        val other = notes.create(NewNote.code("/Users/me/scratch", "B.kt", 1, 1, "other project")).get()
        val free = notes.create(NewNote.free("free", body = "SELECT 1")).get()
        tags.replaceTags(code.id, listOf("b", "a")).get()
        val target = dir.resolve("out.json")

        assertEquals(3, exporter.export(target, null) {})

        val expected = listOf(
            FormatNote(uid = free.uid, title = "free", body = "SELECT 1"),
            FormatNote(uid = other.uid, title = "other project", anchor = FormatAnchor("/Users/me/scratch", "B.kt", 1, 1)),
            FormatNote(
                uid = code.uid, title = "code", body = "b", tags = listOf("a", "b"),
                anchor = FormatAnchor("github.com/org/repo", "src/A.kt", 3, 5, "A", "x\ny\nz"),
                aiSource = "Claude", prompt = "why",
            ),
        )
        assertEquals(expected, decode(target))
        val text = Files.readString(target)
        assertEquals(true, "\"status\": \"APPROX\"" in text)
        assertEquals(listOf("out.json"), otherFiles())
    }

    @Test fun exportsOnlySelectedNotesThatStillExist() {
        val first = notes.create(NewNote.free("first")).get()
        notes.create(NewNote.free("second")).get()
        val target = dir.resolve("out.json")

        assertEquals(1, exporter.export(target, setOf(first.uid, "gone")) {})

        assertEquals(listOf(first.uid), decode(target).map { it.uid })
    }

    @Test fun selectedNoteDeletedAndItsIdReusedIsNotExported() {
        val selected = notes.create(NewNote.free("selected")).get()
        notes.delete(selected.id).get()
        val reused = notes.create(NewNote.free("newcomer")).get()
        assertEquals("SQLite reuses the last id", selected.id, reused.id)
        val target = dir.resolve("out.json")

        assertEquals(0, exporter.export(target, setOf(selected.uid)) {})
    }

    @Test fun longTargetNameStillWorks() {
        notes.create(NewNote.free("note")).get()
        val target = dir.resolve("n".repeat(250) + ".json")

        assertEquals(1, exporter.export(target, null) {})
    }

    @Test fun cancelLeavesThePreviousFileAndNoTempFile() {
        notes.create(NewNote.free("note")).get()
        val target = dir.resolve("out.json")
        Files.writeString(target, "previous")
        var checks = 0

        try {
            exporter.export(target, null) { if (++checks == 3) throw CancellationException() }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }

        assertEquals("previous", Files.readString(target))
        assertEquals(listOf("out.json"), otherFiles())
    }

    @Test fun refusesToOverwriteTheActiveDatabase() {
        val note = notes.create(NewNote.free("keep me")).get()
        val target = dir.resolve("jnote.db")

        try {
            exporter.export(target, null) {}
            fail("expected refusal")
        } catch (_: ExportTargetIsDatabaseException) {
        }

        assertEquals(note.uid, notes.getByUid(note.uid).get()?.uid)
        assertEquals(emptyList<String>(), otherFiles())
    }

    @Test fun databaseFailureWritesNothing() {
        db.close()
        val target = dir.resolve("out.json")

        try {
            exporter.export(target, null) {}
            fail("expected failure")
        } catch (_: Exception) {
        }

        assertFalse(Files.exists(target))
    }
}
