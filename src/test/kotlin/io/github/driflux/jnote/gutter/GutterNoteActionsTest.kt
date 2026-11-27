package io.github.driflux.jnote.gutter

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.action.NoteSaver
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.ui.NoteDraft
import io.github.driflux.jnote.ui.PendingNoteSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class GutterNoteActionsTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val db get() = DatabaseService.getInstance().database
    private val notes get() = NoteRepository(db)
    private val asked = mutableListOf<Pair<Note, List<String>>>()
    private var answer: NoteDraft? = null

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-gutter-actions").toRealPath()
        DatabaseService.getInstance().switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            project.service<PendingNoteSelection>().take()
        } finally {
            try {
                DatabaseService.getInstance().switchTo(DatabasePaths.default())
                dir.toFile().deleteRecursively()
            } finally {
                super.tearDown()
            }
        }
    }

    private val actions by lazy { actionsOn(db) }

    private fun actionsOn(database: io.github.driflux.jnote.db.Database) = GutterNoteActions(
        project,
        database,
        NoteSaver(repository = { NoteRepository(database) }),
        { _, note, tags ->
            asked += note to tags
            answer
        },
    )

    private fun await(future: CompletableFuture<*>) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!future.isDone) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun create(title: String = "old"): Note = notes.create(NewNote.code("p", "src/A.kt", 1, 3, title, body = "b")).get()

    private fun captureNotifications(): List<Notification> {
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        return shown
    }

    private fun countChanges(): IntArray {
        val count = IntArray(1)
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { count[0]++ })
        return count
    }

    fun testEditAsksWithTheStoredNoteAndItsTagsAndSavesTheDraft() {
        val note = create()
        TagRepository(db).replaceTags(note.id, listOf("x")).get()
        answer = NoteDraft(title = "new", body = "nb", tags = listOf("x", "y"))
        val changes = countChanges()

        await(actions.edit(note.id, note.uid))

        assertEquals(note.id, asked.single().first.id)
        assertEquals(listOf("x"), asked.single().second)
        val saved = notes.get(note.id).get()!!
        assertEquals("new", saved.title)
        assertEquals("nb", saved.body)
        assertEquals(listOf("x", "y"), TagRepository(db).tagsOf(note.id).get().map { it.name }.sorted())
        assertEquals(1, changes[0])
    }

    fun testCancellingTheEditChangesNothing() {
        val note = create()
        answer = null

        await(actions.edit(note.id, note.uid))

        assertEquals("old", notes.get(note.id).get()!!.title)
    }

    fun testEditingANoteThatWasDeletedMeanwhileOnlyRefreshesTheList() {
        val changes = countChanges()

        await(actions.edit(404, "none"))

        assertTrue(asked.isEmpty())
        assertEquals(1, changes[0])
    }

    fun testDeleteRemovesTheNoteAndOffersUndoWhichRestoresIt() {
        val note = create()
        val shown = captureNotifications()

        await(actions.delete(note.id, note.uid))

        assertNull(notes.get(note.id).get())
        val notification = shown.single()
        val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder().add(Notification.KEY, notification).build()
        notification.actions.single().actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent(context))
        val deadline = System.currentTimeMillis() + 10_000
        while (notes.get(note.id).get() == null) {
            check(System.currentTimeMillis() < deadline) { "undo did not restore the note" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertEquals(note, notes.get(note.id).get())
    }

    fun testDeletingThroughAStaleCardDoesNotDeleteTheSameIdInTheNewDatabase() {
        val old = db
        val oldNote = create("in old")
        val staleActions = actionsOn(old)
        DatabaseService.getInstance().switchTo(dir.resolve("other.db"))
        val newNote = create("in new")
        assertEquals(oldNote.id, newNote.id)
        val shown = captureNotifications()

        await(staleActions.delete(oldNote.id, oldNote.uid))

        assertEquals("in new", notes.get(newNote.id).get()!!.title)
        assertEquals(1, shown.size)
    }

    fun testEditingThroughAStaleCardNeverReadsOrWritesTheNewDatabase() {
        val old = db
        val oldNote = create("in old")
        val staleActions = actionsOn(old)
        DatabaseService.getInstance().switchTo(dir.resolve("other2.db"))
        val newNote = create("in new")
        answer = NoteDraft(title = "hijacked")
        val shown = captureNotifications()

        await(staleActions.edit(oldNote.id, oldNote.uid))

        assertTrue(asked.isEmpty())
        assertEquals("in new", notes.get(newNote.id).get()!!.title)
        assertEquals(1, shown.size)
    }

    fun testSwitchingTheDatabaseWhileTheEditDialogIsOpenDoesNotWriteIntoTheNewOne() {
        val note = create("in old")
        val old = db
        val switching = GutterNoteActions(project, old, NoteSaver(repository = { NoteRepository(old) }), { _, _, _ ->
            DatabaseService.getInstance().switchTo(dir.resolve("other3.db"))
            NoteDraft(title = "hijacked")
        })
        val shown = captureNotifications()

        await(switching.edit(note.id, note.uid))

        val newNote = create("in new")
        assertEquals(note.id, newNote.id)
        assertEquals("in new", notes.get(newNote.id).get()!!.title)
        assertEquals(1, shown.size)
    }

    fun testOpenIgnoresACardFromAnotherDatabase() {
        val staleActions = actionsOn(db)
        DatabaseService.getInstance().switchTo(dir.resolve("other4.db"))
        db

        staleActions.open(5, "u")

        assertNull(project.service<PendingNoteSelection>().peek())
    }

    fun testADialogThatThrowsCompletesTheEditExceptionally() {
        val note = create()
        val throwing = GutterNoteActions(project, db, NoteSaver(), { _, _, _ -> error("dialog broke") })

        val future = throwing.edit(note.id, note.uid)
        val deadline = System.currentTimeMillis() + 10_000
        while (!future.isDone) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            try {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            } catch (expected: Throwable) {
            }
            Thread.sleep(10)
        }

        assertTrue(future.isCompletedExceptionally)
    }

    fun testOpenRequestsTheNoteInTheToolWindow() {
        val note = create()

        await(actions.open(note.id, note.uid))

        assertEquals(note.id, project.service<PendingNoteSelection>().peek())
    }

    private fun staleCardAfterIdReuse(): Triple<Note, Note, GutterNoteActions> {
        val old = create("old")
        val stale = actions
        notes.delete(old.id).get()
        val reused = create("reused")
        assertEquals(old.id, reused.id)
        return Triple(old, reused, stale)
    }

    fun testDeletingThroughACardWhoseIdWasReusedKeepsTheNewNote() {
        val (old, reused, stale) = staleCardAfterIdReuse()
        val changes = countChanges()

        await(stale.delete(old.id, old.uid))

        assertEquals(reused, notes.get(reused.id).get())
        assertEquals(1, changes[0])
    }

    fun testEditingThroughACardWhoseIdWasReusedNeitherAsksNorWrites() {
        val (old, reused, stale) = staleCardAfterIdReuse()
        answer = NoteDraft(title = "hijacked")
        val changes = countChanges()

        await(stale.edit(old.id, old.uid))

        assertTrue(asked.isEmpty())
        assertEquals(reused, notes.get(reused.id).get())
        assertEquals(1, changes[0])
    }

    fun testSavingAnEditWhoseNoteWasReplacedMeanwhileDoesNotWriteTheNewNote() {
        val old = create("old")
        val reusedHolder = arrayOfNulls<Note>(1)
        val replacing = GutterNoteActions(project, db, NoteSaver(repository = { NoteRepository(db) }), { _, _, _ ->
            notes.delete(old.id).get()
            reusedHolder[0] = create("reused")
            NoteDraft(title = "hijacked")
        })

        await(replacing.edit(old.id, old.uid))

        assertEquals(reusedHolder[0], notes.get(old.id).get())
    }

    fun testOpeningACardWhoseIdWasReusedRequestsNothing() {
        val (old, _, stale) = staleCardAfterIdReuse()

        await(stale.open(old.id, old.uid))

        assertNull(project.service<PendingNoteSelection>().peek())
    }
}
