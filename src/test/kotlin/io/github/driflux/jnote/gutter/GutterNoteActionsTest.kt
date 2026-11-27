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

    private val actions by lazy {
        GutterNoteActions(project, NoteSaver(), { _, note, tags ->
            asked += note to tags
            answer
        })
    }

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

        await(actions.edit(note.id))

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

        await(actions.edit(note.id))

        assertEquals("old", notes.get(note.id).get()!!.title)
    }

    fun testEditingANoteThatWasDeletedMeanwhileOnlyRefreshesTheList() {
        val changes = countChanges()

        await(actions.edit(404))

        assertTrue(asked.isEmpty())
        assertEquals(1, changes[0])
    }

    fun testDeleteRemovesTheNoteAndOffersUndoWhichRestoresIt() {
        val note = create()
        val shown = captureNotifications()

        await(actions.delete(note.id))

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

    fun testOpenRequestsTheNoteInTheToolWindow() {
        actions.open(77)

        assertEquals(77L, project.service<PendingNoteSelection>().peek())
    }
}
