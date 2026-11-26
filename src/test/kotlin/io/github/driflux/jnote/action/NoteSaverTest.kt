package io.github.driflux.jnote.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.ui.NoteDraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger

class NoteSaverTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var db: Database
    private val anchor = CodeAnchor("src/A.kt", 3, 5, "snap", "A.b")
    private val published = AtomicInteger()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-saver")
        db = Database(dir.resolve("jnote.db"))
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { published.incrementAndGet() })
    }

    override fun tearDown() {
        try {
            db.close()
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun await(future: CompletableFuture<*>) =
        PlatformTestUtil.waitWithEventsDispatching("save did not finish", { future.isDone }, 20)

    fun testSavesCodeNoteWithAnchorAndPublishesOnce() {
        val repo = NoteRepository(db)
        val saver = NoteSaver(repository = { repo }, projectKey = { "github.com/o/r" })
        val future = saver.save(project, anchor, NoteDraft("Title", "Body", "Claude", "http://x", "p"))
        await(future)

        val note = future.get()
        assertEquals(NoteKind.CODE, note.kind)
        assertEquals("github.com/o/r", note.project)
        assertEquals("src/A.kt", note.relPath)
        assertEquals(3, note.startLine)
        assertEquals(5, note.endLine)
        assertEquals("snap", note.snapshot)
        assertEquals("A.b", note.className)
        assertEquals("Title", note.title)
        assertEquals("Claude", note.aiSource)
        assertEquals(note, repo.get(note.id).get())
        assertEquals(1, published.get())
    }

    fun testProjectKeyIsResolvedOffEdt() {
        var onEdt: Boolean? = null
        val repo = NoteRepository(db)
        val saver = NoteSaver(repository = { repo }, projectKey = {
            onEdt = ApplicationManager.getApplication().isDispatchThread
            "k"
        })
        await(saver.save(project, anchor, NoteDraft("T")))
        assertEquals(false, onEdt)
    }

    fun testFailureSurfacesAndDoesNotPublish() {
        val saver = NoteSaver(repository = { error("disk full") }, projectKey = { "k" })
        val future = saver.save(project, anchor, NoteDraft("T"))
        await(future)

        assertTrue(future.isCompletedExceptionally)
        val cause = try {
            future.join()
            null
        } catch (e: CompletionException) {
            e.cause
        }
        assertEquals("disk full", cause?.message)
        assertEquals(0, published.get())
    }

    fun testUpdateChangesTextBumpsUpdatedAtKeepsAnchorAndTagsAndPublishesOnce() {
        var now = 1_000L
        val repo = NoteRepository(db) { now }
        val tags = TagRepository(db)
        val created = repo.create(NewNote.code("k", "src/A.kt", 3, 5, "Old", "b", "A.b", "snap")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(created.id, tag.id).get()
        now = 2_000L

        val future = NoteSaver(repository = { repo }, projectKey = { "k" }).update(created, NoteDraft(" New ", "nb", "Gemini"))
        await(future)

        val updated = future.get()!!
        assertEquals(updated, repo.get(created.id).get())
        assertEquals("New", updated.title)
        assertEquals("Gemini", updated.aiSource)
        assertEquals(2_000L, updated.updatedAt)
        assertEquals(1_000L, updated.createdAt)
        assertEquals(created.copy(title = "New", body = "nb", aiSource = "Gemini", updatedAt = 2_000L), updated)
        assertEquals(listOf(tag.id), tags.tagsOf(created.id).get().map { it.id })
        assertEquals(1, published.get())
    }

    fun testUpdateOfDeletedNoteEndsWithNullAndStillPublishes() {
        val repo = NoteRepository(db)
        val created = repo.create(NewNote.free("q")).get()
        repo.delete(created.id).get()
        val future = NoteSaver(repository = { repo }, projectKey = { "k" }).update(created, NoteDraft("x"))
        await(future)
        assertNull(future.get())
        assertEquals(1, published.get())
    }

    fun testUpdateFailureDoesNotPublish() {
        val repo = NoteRepository(db)
        val created = repo.create(NewNote.free("q")).get()
        val future = NoteSaver(repository = { repo }, projectKey = { "k" }).update(created, NoteDraft(" "))
        await(future)
        assertTrue(future.isCompletedExceptionally)
        assertEquals("q", repo.get(created.id).get()!!.title)
        assertEquals(0, published.get())
    }

    fun testBlankTitleFailsWithoutWriting() {
        val repo = NoteRepository(db)
        val saver = NoteSaver(repository = { repo }, projectKey = { "k" })
        val future = saver.save(project, anchor, NoteDraft(" "))
        await(future)
        assertTrue(future.isCompletedExceptionally)
        assertTrue(repo.list("k", false).get().isEmpty())
        assertEquals(0, published.get())
    }

    fun testSaveFreeStoresGlobalNoteWithoutResolvingProjectAndPublishesOnce() {
        val repo = NoteRepository(db)
        val saver = NoteSaver(repository = { repo }, projectKey = { error("free notes need no project key") })
        val future = saver.saveFree(NoteDraft("q", "select 1", "Claude"))
        await(future)

        val note = future.get()
        assertEquals(NoteKind.FREE, note.kind)
        assertNull(note.project)
        assertNull(note.relPath)
        assertEquals("select 1", note.body)
        assertEquals(note, repo.get(note.id).get())
        assertEquals(1, published.get())
    }

    fun testSaveFreeResolvesRepositoryOnBackgroundExecutor() {
        var repoThread: String? = null
        val repo = NoteRepository(db)
        val saver = NoteSaver(
            repository = {
                repoThread = Thread.currentThread().name
                repo
            },
            background = { task -> Thread(task, "fake-background").start() },
        )
        await(saver.saveFree(NoteDraft("q")))
        assertEquals("fake-background", repoThread)
    }

    fun testSaveFreeFailureSurfacesAndDoesNotPublish() {
        val repo = NoteRepository(db)
        val saver = NoteSaver(repository = { repo })
        val blank = saver.saveFree(NoteDraft(" "))
        val broken = NoteSaver(repository = { error("disk full") }).saveFree(NoteDraft("q"))
        await(blank)
        await(broken)

        assertTrue(blank.isCompletedExceptionally)
        assertTrue(broken.isCompletedExceptionally)
        assertTrue(repo.list(null, true).get().isEmpty())
        assertEquals(0, published.get())
    }
}
