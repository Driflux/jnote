package io.github.driflux.jnote.db

import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.LocationConfirmation
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.NoteSummary
import io.github.driflux.jnote.model.NoteText
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
import java.util.concurrent.ExecutionException

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
    fun createAttachesTheGivenTagsAndRegistersNewNames() {
        val tags = TagRepository(db)
        val old = tags.getOrCreate("old").get()

        val created = repo.create(code(), listOf("old", " fresh ", "old", "")).get()

        assertEquals(listOf("fresh", "old"), tags.tagsOf(created.id).get().map { it.name })
        assertEquals(old, tags.list().get().single { it.name == "old" })
    }

    @Test
    fun createLeavesNothingWhenAttachingTagsFails() {
        db.submit { c ->
            c.createStatement().use { it.execute("CREATE TEMP TRIGGER fail_link BEFORE INSERT ON note_tag BEGIN SELECT RAISE(ABORT, 'link failed'); END") }
        }.get()

        assertThrows(ExecutionException::class.java) { repo.create(code(), listOf("x")).get() }

        assertTrue(repo.list(null, true).get().isEmpty())
        assertTrue(TagRepository(db).list().get().isEmpty())
    }

    @Test
    fun createRejectsACommaInATagNameWithoutWriting() {
        val e = assertThrows(ExecutionException::class.java) { repo.create(code(), listOf("a,b")).get() }
        assertTrue(e.cause is IllegalArgumentException)
        assertTrue(repo.list(null, true).get().isEmpty())
    }

    @Test
    fun updateTextKeepsAnchorColumnsThatChangedAfterTheNoteWasRead() {
        val read = repo.create(code()).get()
        repo.updateAnchors(listOf(AnchorUpdate(read.uid, "src/B.kt", 7, 9, NoteStatus.APPROX))).get()
        now = 2_000

        val saved = repo.updateText(read.uid, NoteText("New", "nb", "Claude", null, null), emptyList()).get()!!

        val expected = read.copy(
            relPath = "src/B.kt", startLine = 7, endLine = 9, status = NoteStatus.APPROX,
            title = "New", body = "nb", aiSource = "Claude", updatedAt = 2_000,
        )
        assertEquals(expected, saved.note)
        assertEquals(expected, repo.get(read.id).get())
        assertFalse(saved.locationMoved)
    }

    @Test
    fun updateTextOfAMissingNoteIsNull() {
        assertNull(repo.updateText("no-such-uid", NoteText("t", "", null, null, null), listOf("x")).get())
    }

    @Test
    fun confirmLocationAppliesOnlyWhileTheStoredAnchorIsTheOneConfirmed() {
        val read = repo.create(NewNote.code("p1", "src/A.kt", 3, 5, "t", snapshot = "old", status = NoteStatus.APPROX)).get()
        val other = Database(tmp.root.toPath().resolve("jnote.db"))
        try {
            // 다른 IDE 프로세스가 파일을 열어 노트를 옮긴 상황
            NoteRepository(other).updateAnchors(listOf(AnchorUpdate(read.uid, "src/A.kt", 4, 6, NoteStatus.APPROX))).get()
        } finally {
            other.close()
        }

        val missed = repo.confirmLocation(read.uid, LocationConfirmation("src/A.kt", 3, 5, "seen")).get()!!
        assertTrue(missed.locationMoved)
        assertEquals(read.copy(startLine = 4, endLine = 6), missed.note)

        val applied = repo.confirmLocation(read.uid, LocationConfirmation("src/A.kt", 4, 6, "current")).get()!!
        assertFalse(applied.locationMoved)
        assertEquals(NoteStatus.OK, applied.note.status)
        assertEquals("current", applied.note.snapshot)
        assertEquals(applied.note, repo.get(read.id).get())
    }

    @Test
    fun updateTextSetsTheTagsByUidInTheSameTransaction() {
        val read = repo.create(code(), listOf("a", "b")).get()
        val saved = repo.updateText(read.uid, NoteText("t", "b", null, null, null), listOf("b", "c")).get()!!
        assertEquals(listOf("b", "c"), TagRepository(db).tagsOf(saved.note.id).get().map { it.name })
    }

    @Test
    fun confirmLocationOverridesAStatusThatChangedAtTheSameLocation() {
        val read = repo.create(NewNote.code("p1", "src/A.kt", 3, 5, "t", snapshot = "old", status = NoteStatus.APPROX)).get()
        repo.updateAnchors(listOf(AnchorUpdate(read.uid, "src/A.kt", 3, 5, NoteStatus.ORPHAN))).get()

        val saved = repo.confirmLocation(read.uid, LocationConfirmation("src/A.kt", 3, 5, "seen")).get()!!

        assertFalse(saved.locationMoved)
        assertEquals(NoteStatus.OK, saved.note.status)
        assertEquals("seen", saved.note.snapshot)
    }

    @Test
    fun confirmLocationOfAMissingNoteIsNull() {
        assertNull(repo.confirmLocation("no-such-uid", LocationConfirmation("src/A.kt", 1, 1, "s")).get())
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
    fun listSummariesMatchListInOrderAndCarryTheSameFields() {
        val a = repo.create(code("a")).get()
        val free = repo.create(NewNote.free("free", aiSource = "Claude")).get()
        now = 2_000
        repo.update(a.copy(title = "a2")).get()

        val notes = repo.list("p1", true).get()
        val summaries = repo.listSummaries("p1", true).get()

        assertEquals(notes.map { it.id }, summaries.map { it.id })
        assertEquals(listOf(a.id, free.id), summaries.map { it.id })
        notes.zip(summaries).forEach { (note, summary) ->
            assertEquals(
                NoteSummary(
                    note.id, note.uid, note.kind, note.project, note.relPath, note.className, note.startLine, note.endLine,
                    note.status, note.title, note.aiSource, note.updatedAt,
                ),
                summary,
            )
        }
        assertEquals(repo.list("p1", false).get().map { it.id }, repo.listSummaries("p1", false).get().map { it.id })
        assertEquals(repo.list(null, false).get().map { it.id }, repo.listSummaries(null, false).get().map { it.id })
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
        val created = repo.create(code()).get()
        val failed = db.submit { c ->
            c.createStatement().use { it.executeUpdate("UPDATE note SET status = 'BAD' WHERE id = ${created.id}") }
        }
        val e = assertThrows(java.util.concurrent.ExecutionException::class.java) { failed.get() }
        assertTrue(e.cause is java.sql.SQLException)
        assertNotNull(repo.get(created.id).get())
    }

    @Test
    fun restoreBringsBackTheSameIdUidAndTimestamps() {
        val created = repo.create(code()).get()
        now = 9_000
        repo.delete(created.id).get()
        assertNull(repo.get(created.id).get())

        val restored = repo.restore(created, emptyList()).get()

        assertEquals(created, restored)
        assertEquals(created, repo.get(created.id).get())
        assertEquals(created.id, repo.getByUid(created.uid).get()?.id)
        assertEquals(1_000L, restored.updatedAt)
    }

    @Test
    fun restoreReattachesTags() {
        val tags = TagRepository(db)
        val a = tags.getOrCreate("a").get()
        val b = tags.getOrCreate("b").get()
        val created = repo.create(code()).get()
        tags.attach(created.id, a.id).get()
        tags.attach(created.id, b.id).get()
        val tagIds = tags.tagsOf(created.id).get().map { it.id }
        repo.delete(created.id).get()
        assertTrue(tags.tagsOf(created.id).get().isEmpty())

        repo.restore(created, tagIds).get()

        assertEquals(listOf(a, b), tags.tagsOf(created.id).get())
    }

    @Test
    fun restoreSkipsTagsThatNoLongerExist() {
        val tags = TagRepository(db)
        val gone = tags.getOrCreate("gone").get()
        val kept = tags.getOrCreate("kept").get()
        val created = repo.create(code()).get()
        repo.delete(created.id).get()
        tags.delete(gone.id).get()

        repo.restore(created, listOf(gone.id, kept.id)).get()

        assertEquals(listOf(kept), tags.tagsOf(created.id).get())
    }

    @Test
    fun restoreOfAnExistingIdFailsAndChangesNothing() {
        val tags = TagRepository(db)
        val tag = tags.getOrCreate("t").get()
        val created = repo.create(code("original")).get()

        val e = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            repo.restore(created.copy(title = "changed"), listOf(tag.id)).get()
        }

        assertTrue(e.cause is java.sql.SQLException)
        assertEquals(created, repo.get(created.id).get())
        assertTrue(tags.tagsOf(created.id).get().isEmpty())
        assertNotNull(repo.create(code("next")).get())
    }

    @Test
    fun restoreWithATakenUidFails() {
        val a = repo.create(code("a")).get()
        val b = repo.create(code("b")).get()
        repo.delete(b.id).get()

        assertThrows(java.util.concurrent.ExecutionException::class.java) {
            repo.restore(b.copy(uid = a.uid), emptyList()).get()
        }

        assertNull(repo.get(b.id).get())
    }

    @Test
    fun restoredNoteIsFoundByTheFullTextIndexAgain() {
        val created = repo.create(NewNote.free("needle title", "needle body")).get()
        repo.delete(created.id).get()
        repo.restore(created, emptyList()).get()

        assertEquals(listOf(created.id), SearchRepository(db).search("needle", null, true).get())
    }

    @Test
    fun listByPathReturnsOnlyThatFileOfThatProjectOrderedByStartLine() {
        val late = repo.create(NewNote.code("p1", "src/A.kt", 20, 21, "late")).get()
        val early = repo.create(NewNote.code("p1", "src/A.kt", 3, 4, "early")).get()
        val sameLine = repo.create(NewNote.code("p1", "src/A.kt", 3, 9, "same line")).get()
        repo.create(NewNote.code("p1", "src/B.kt", 3, 4, "other file")).get()
        repo.create(NewNote.code("p2", "src/A.kt", 3, 4, "other project")).get()
        repo.create(NewNote.free("free")).get()

        assertEquals(listOf(early.id, sameLine.id, late.id), repo.listByPath("p1", "src/A.kt").get().map { it.id })
    }

    @Test
    fun listByPathOfAFileWithoutNotesIsEmpty() {
        repo.create(code()).get()
        assertTrue(repo.listByPath("p1", "src/None.kt").get().isEmpty())
    }

    @Test
    fun listByPathUsesTheProjectPathIndex() {
        val plan = db.submitAndWait { c ->
            c.createStatement().use { s ->
                s.executeQuery("EXPLAIN QUERY PLAN SELECT id FROM note WHERE project = 'p1' AND rel_path = 'a' ORDER BY start_line, id").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("detail") else null }.toList()
                }
            }
        }
        assertTrue(plan.toString(), plan.any { it.contains("idx_note_project_path") })
    }

    @Test
    fun updateAnchorsChangesOnlyPathLinesAndStatus() {
        val created = repo.create(code()).get()
        now = 2_000L
        val changed = repo.updateAnchors(listOf(AnchorUpdate(created.uid, "src/B.kt", 10, 12, NoteStatus.APPROX))).get()
        assertEquals(1, changed)
        val expected = created.copy(relPath = "src/B.kt", startLine = 10, endLine = 12, status = NoteStatus.APPROX)
        assertEquals(expected, repo.get(created.id).get())
    }

    @Test
    fun updateAnchorsCountsOnlyNotesThatChange() {
        val same = repo.create(code("same")).get()
        val moved = repo.create(code("moved")).get()
        val changed = repo.updateAnchors(
            listOf(
                AnchorUpdate(same.uid, "src/A.kt", 3, 5, NoteStatus.OK),
                AnchorUpdate(moved.uid, "src/A.kt", 4, 6, NoteStatus.OK),
            ),
        ).get()
        assertEquals(1, changed)
        assertEquals(3, repo.get(same.id).get()?.startLine)
        assertEquals(4, repo.get(moved.id).get()?.startLine)
    }

    @Test
    fun updateAnchorsRollsBackEveryNoteWhenOneWriteFails() {
        val first = repo.create(code("first")).get()
        val failing = repo.create(code("failing")).get()
        db.submitAndWait { c ->
            c.createStatement().use {
                it.execute("CREATE TRIGGER fail_anchor BEFORE UPDATE ON note WHEN new.uid = '${failing.uid}' BEGIN SELECT RAISE(ABORT, 'boom'); END")
            }
        }
        val write = repo.updateAnchors(
            listOf(
                AnchorUpdate(first.uid, "src/A.kt", 7, 9, NoteStatus.OK),
                AnchorUpdate(failing.uid, "src/A.kt", 7, 9, NoteStatus.OK),
            ),
        )
        assertThrows(ExecutionException::class.java) { write.get() }
        assertEquals(first, repo.get(first.id).get())
    }

    @Test
    fun updateAnchorsIgnoresNotesDeletedMeanwhile() {
        val kept = repo.create(code("kept")).get()
        val gone = repo.create(code("gone")).get()
        repo.delete(gone.id).get()
        val reused = repo.create(code("new")).get()
        assertEquals(gone.id, reused.id)
        val changed = repo.updateAnchors(
            listOf(
                AnchorUpdate(gone.uid, "src/A.kt", 9, 9, NoteStatus.ORPHAN),
                AnchorUpdate(kept.uid, "src/A.kt", 7, 9, NoteStatus.OK),
            ),
        ).get()
        assertEquals(1, changed)
        assertEquals(3, repo.get(reused.id).get()?.startLine)
        assertEquals(7, repo.get(kept.id).get()?.startLine)
    }
}
