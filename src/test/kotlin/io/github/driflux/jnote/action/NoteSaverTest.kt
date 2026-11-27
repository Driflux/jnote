package io.github.driflux.jnote.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
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
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.ui.NoteDraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class NoteSaverTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var db: Database
    private val anchor = CodeAnchor("src/A.kt", 3, 5, "snap", "A.b")
    private val published = AtomicInteger()

    // 작업이 execute 반환 전에 끝나게 해서, 뒤에 붙인 단계가 호출 스레드에서 도는지 background에서 도는지가 항상 갈리게 한다
    private val joiningBackground = Executor { r -> Thread(r, "fake-background").also { it.start(); it.join() } }

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

    fun testUpdateChangesTextBumpsUpdatedAtKeepsAnchorAndPublishesOnce() {
        var now = 1_000L
        val repo = NoteRepository(db) { now }
        val tags = TagRepository(db)
        val created = repo.create(NewNote.code("k", "src/A.kt", 3, 5, "Old", "b", "A.b", "snap")).get()
        val tag = tags.getOrCreate("x").get()
        tags.attach(created.id, tag.id).get()
        now = 2_000L

        val future = NoteSaver(repository = { repo }, tags = { tags }, projectKey = { "k" })
            .update(project, created, NoteDraft(" New ", "nb", "Gemini", tags = listOf("x")))
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

    fun testSaveRegistersNewTagsAndAttachesThem() {
        val repo = NoteRepository(db)
        val tags = TagRepository(db)
        val existing = tags.getOrCreate("old").get()
        val saver = NoteSaver(repository = { repo }, tags = { tags }, projectKey = { "k" })

        val future = saver.save(project, anchor, NoteDraft("T", tags = listOf("old", " fresh ", "old", "")))
        await(future)

        val note = future.get()
        assertEquals(listOf("fresh", "old"), tags.tagsOf(note.id).get().map { it.name })
        assertEquals(existing, tags.list().get().single { it.name == "old" })
        assertEquals(2, tags.list().get().size)
        assertEquals(1, published.get())
    }

    fun testSaveThatFailsWhileAttachingTagsLeavesNoNoteAndDoesNotPublish() {
        val repo = NoteRepository(db)
        db.submit { c ->
            c.createStatement().use { it.execute("CREATE TEMP TRIGGER fail_link BEFORE INSERT ON note_tag BEGIN SELECT RAISE(ABORT, 'link failed'); END") }
        }.get()
        val saver = NoteSaver(repository = { repo }, projectKey = { "k" })

        val code = saver.save(project, anchor, NoteDraft("T", tags = listOf("x")))
        val free = saver.saveFree(NoteDraft("q", tags = listOf("x")))
        await(code)
        await(free)

        assertTrue(code.isCompletedExceptionally)
        assertTrue(free.isCompletedExceptionally)
        assertTrue(repo.list(null, true).get().isEmpty())
        assertEquals(0, published.get())
    }

    fun testSaveFreeAttachesTags() {
        val repo = NoteRepository(db)
        val tags = TagRepository(db)
        val future = NoteSaver(repository = { repo }, tags = { tags }).saveFree(NoteDraft("q", tags = listOf("sql")))
        await(future)
        assertEquals(listOf("sql"), tags.tagsOf(future.get().id).get().map { it.name })
    }

    fun testUpdateReplacesTheTagSetAndKeepsRemovedTagsInTheDictionary() {
        val repo = NoteRepository(db)
        val tags = TagRepository(db)
        val created = repo.create(NewNote.free("q")).get()
        tags.replaceTags(created.id, listOf("a", "b")).get()

        val future = NoteSaver(repository = { repo }, tags = { tags }).update(project, created, NoteDraft("q", tags = listOf("b", "c")))
        await(future)

        assertEquals(listOf("b", "c"), tags.tagsOf(created.id).get().map { it.name })
        assertEquals(listOf("a", "b", "c"), tags.list().get().map { it.name })
    }

    fun testUpdateWithNoTagsClearsThem() {
        val repo = NoteRepository(db)
        val tags = TagRepository(db)
        val created = repo.create(NewNote.free("q")).get()
        tags.replaceTags(created.id, listOf("a")).get()
        await(NoteSaver(repository = { repo }, tags = { tags }).update(project, created, NoteDraft("q")))
        assertTrue(tags.tagsOf(created.id).get().isEmpty())
    }

    fun testUpdateOfDeletedNoteEndsWithNullAndStillPublishes() {
        val repo = NoteRepository(db)
        val created = repo.create(NewNote.free("q")).get()
        repo.delete(created.id).get()
        val future = NoteSaver(repository = { repo }, projectKey = { "k" }).update(project, created, NoteDraft("x"))
        await(future)
        assertNull(future.get())
        assertEquals(1, published.get())
    }

    fun testUpdateFailureDoesNotPublish() {
        val repo = NoteRepository(db)
        val created = repo.create(NewNote.free("q")).get()
        val future = NoteSaver(repository = { repo }, projectKey = { "k" }).update(project, created, NoteDraft(" "))
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
            background = joiningBackground,
        )
        await(saver.saveFree(NoteDraft("q")))
        assertEquals("fake-background", repoThread)
    }

    fun testSaveResolvesRepositoryOnBackgroundExecutor() {
        var repoThread: String? = null
        val repo = NoteRepository(db)
        val saver = NoteSaver(
            repository = {
                repoThread = Thread.currentThread().name
                repo
            },
            projectKey = { "k" },
            background = joiningBackground,
        )
        await(saver.save(project, anchor, NoteDraft("q")))
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

    // 노트 경로는 basePath 기준이므로 basePath 아래에 실제 파일을 만든다. 테스트마다 이름을 달리해 VFS 캐시가 섞이지 않게 한다
    private fun sourceFile(name: String, text: String): String {
        val real = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(real.parent)
        Files.writeString(real, text)
        Disposer.register(testRootDisposable) { real.toFile().delete() }
        return "src/$name"
    }

    private fun approx(repo: NoteRepository, relPath: String, start: Int, end: Int) =
        repo.create(NewNote.code("k", relPath, start, end, "Old", "ob", snapshot = "moved", status = NoteStatus.APPROX)).get()

    fun testConfirmLocationMakesApproxNoteOkWithTheCurrentLinesAndPublishesOnce() {
        val relPath = sourceFile("Confirm.kt", "one\ntwo\nthree\nfour\n")
        val repo = NoteRepository(db)
        val created = approx(repo, relPath, 2, 3)

        val future = NoteSaver(repository = { repo }).confirmLocation(project, created)
        await(future)

        val stored = repo.get(created.id).get()!!
        assertEquals(stored, future.get())
        assertEquals(NoteStatus.OK, stored.status)
        assertEquals("two\nthree", stored.snapshot)
        assertEquals(created.copy(status = NoteStatus.OK, snapshot = "two\nthree", updatedAt = stored.updatedAt), stored)
        assertEquals(1, published.get())
    }

    fun testEditingAnApproxNoteAlsoConfirmsItsLocation() {
        val relPath = sourceFile("EditApprox.kt", "a\nb\nc\n")
        val repo = NoteRepository(db)
        val created = approx(repo, relPath, 3, 3)

        val future = NoteSaver(repository = { repo }).update(project, created, NoteDraft("New", "nb"))
        await(future)

        val stored = repo.get(created.id).get()!!
        assertEquals(created.copy(title = "New", body = "nb", status = NoteStatus.OK, snapshot = "c", updatedAt = stored.updatedAt), stored)
        assertEquals(1, published.get())
    }

    fun testEditingAnOkNoteKeepsItsSnapshotEvenWhenTheCodeChanged() {
        val relPath = sourceFile("EditOk.kt", "changed\n")
        val repo = NoteRepository(db)
        val created = repo.create(NewNote.code("k", relPath, 1, 1, "Old", snapshot = "original")).get()

        val future = NoteSaver(repository = { repo }).update(project, created, NoteDraft("New"))
        await(future)

        val stored = repo.get(created.id).get()!!
        assertEquals(created.copy(title = "New", updatedAt = stored.updatedAt), stored)
    }

    fun testConfirmFailsWithoutWritingWhenTheFileOrLinesAreGone() {
        val short = sourceFile("Short.kt", "only\n")
        val repo = NoteRepository(db)
        val missingFile = approx(repo, "src/Gone.kt", 1, 1)
        val missingLines = approx(repo, short, 2, 4)

        for (note in listOf(missingFile, missingLines)) {
            val future = NoteSaver(repository = { repo }).confirmLocation(project, note)
            await(future)
            assertTrue(future.isCompletedExceptionally)
            val cause = try {
                future.join()
                null
            } catch (e: CompletionException) {
                e.cause
            }
            assertTrue(cause?.message, cause?.message.orEmpty().contains(note.relPath!!))
            assertEquals(note, repo.get(note.id).get())
        }
        assertEquals(0, published.get())
    }

    fun testEditingAnApproxNoteWhoseFileIsGoneSavesTheEditAndStaysApprox() {
        val repo = NoteRepository(db)
        val created = approx(repo, "src/Gone.kt", 1, 1)

        val future = NoteSaver(repository = { repo }).update(project, created, NoteDraft("New"))
        await(future)

        val stored = repo.get(created.id).get()!!
        assertEquals(created.copy(title = "New", body = "", updatedAt = stored.updatedAt), stored)
        assertEquals(1, published.get())
    }
}
