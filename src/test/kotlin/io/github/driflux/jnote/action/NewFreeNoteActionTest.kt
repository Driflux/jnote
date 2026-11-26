package io.github.driflux.jnote.action

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.ui.NoteDraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NewFreeNoteActionTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var db: Database
    private val published = AtomicInteger()
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-newfree")
        db = Database(dir.resolve("jnote.db"))
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { published.incrementAndGet() })
        val collect = object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        }
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, collect)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, collect)
    }

    override fun tearDown() {
        try {
            db.close()
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun eventFor(action: NewFreeNoteAction) = TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(),
    )

    fun testRegisteredAsAnActionWithBundleText() {
        val action = ActionManager.getInstance().getAction(NewFreeNoteAction.ID)
        assertTrue(action is NewFreeNoteAction)
        assertEquals(JNoteBundle.message("action.newFreeNote.text"), action.templatePresentation.text)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    fun testFactoryDoesNotFailWhenAddingTheToolbarAction() {
        val window = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        io.github.driflux.jnote.ui.JNoteToolWindowFactory().createToolWindowContent(project, window)
        assertEquals(1, window.contentManager.contentCount)
        com.intellij.openapi.util.Disposer.dispose(window.contentManager.getContent(0)!!.component as io.github.driflux.jnote.ui.JNotePanel)
    }

    fun testEnabledOnlyWithAProject() {
        val action = NewFreeNoteAction()
        val withProject = eventFor(action)
        action.update(withProject)
        assertTrue(withProject.presentation.isEnabledAndVisible)

        val without = TestActionEvent.createTestEvent(action, SimpleDataContext.EMPTY_CONTEXT)
        action.update(without)
        assertFalse(without.presentation.isEnabledAndVisible)
    }

    fun testPerformedSavesAGlobalNoteAndNotifies() {
        val repo = NoteRepository(db)
        val action = NewFreeNoteAction(askUser = { NoteDraft("q", "select 1") }, saver = NoteSaver(repository = { repo }))

        action.actionPerformed(eventFor(action))
        PlatformTestUtil.waitWithEventsDispatching("note was not saved", { notifications.isNotEmpty() }, 20)

        val note = repo.list("any/project", true).get().single()
        assertEquals(NoteKind.FREE, note.kind)
        assertNull(note.project)
        assertEquals("select 1", note.body)
        assertEquals(1, published.get())
        assertEquals(NotificationType.INFORMATION, notifications.single().type)
        // 다른 프로젝트에서도 보이는지는 목록 쿼리가 프로젝트 키와 상관없이 FREE를 돌려주는지로 확인한다
        assertEquals(listOf(note), repo.list("another/project", false).get())
    }

    fun testCancelledDialogSavesNothing() {
        val repo = NoteRepository(db)
        var asked = false
        val action = NewFreeNoteAction(askUser = { asked = true; null }, saver = NoteSaver(repository = { repo }))

        action.actionPerformed(eventFor(action))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(asked)
        assertTrue(repo.list("k", true).get().isEmpty())
        assertEquals(0, published.get())
        assertTrue(notifications.isEmpty())
    }

    fun testSaveFailureShowsErrorNotificationWithEscapedReason() {
        val action = NewFreeNoteAction(
            askUser = { NoteDraft("q") },
            saver = NoteSaver(repository = { error("disk <full>") }),
        )

        action.actionPerformed(eventFor(action))
        PlatformTestUtil.waitWithEventsDispatching("no error notification", { notifications.isNotEmpty() }, 20)

        val notification = notifications.single()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.contains("disk &lt;full&gt;"))
        assertEquals(0, published.get())
    }

    fun testClosedDatabaseIsReportedNotSwallowed() {
        db.close()
        val repo = NoteRepository(db)
        val action = NewFreeNoteAction(askUser = { NoteDraft("q") }, saver = NoteSaver(repository = { repo }))

        action.actionPerformed(eventFor(action))
        PlatformTestUtil.waitWithEventsDispatching("no error notification", { notifications.isNotEmpty() }, 20)

        assertEquals(NotificationType.ERROR, notifications.single().type)
        assertEquals(0, published.get())
    }
}
