package io.github.driflux.jnote.action

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.driflux.jnote.anchor.AnchorNameResolver
import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.ui.NoteDraft
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.KeyStroke

class AddNoteActionTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()
    private lateinit var dir: Path
    private lateinit var db: Database
    private val published = AtomicInteger()
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-action")
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
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
            db.close()
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun openProjectFile(name: String, text: String): Editor {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += vFile
        myFixture.openFileInEditor(vFile)
        return myFixture.editor
    }

    private fun eventFor(action: AddNoteAction, editor: Editor, file: VirtualFile): AnActionEvent {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    private val source = "class A {\n    fun b() {\n        first()\n        second()\n    }\n}\n"

    fun testRegisteredWithPopupMenuAndShortcuts() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("JNote.AddNote")
        assertTrue(action is AddNoteAction)
        assertEquals(JNoteBundle.message("action.addNote.text"), action.templatePresentation.text)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)

        val popup = manager.getAction("EditorPopupMenu") as ActionGroup
        assertTrue(popup.getChildren(null).any { it === action })

        val expected = KeyboardShortcut(KeyStroke.getKeyStroke("ctrl alt N"), null)
        listOf("\$default", "Mac OS X 10.5+").forEach { name ->
            val keymap = KeymapManager.getInstance().getKeymap(name)!!
            assertTrue("no shortcut in $name", keymap.getShortcuts("JNote.AddNote").contains(expected))
        }
    }

    fun testEnabledForFileInProject() {
        val editor = openProjectFile("A.kt", source)
        val action = AddNoteAction()
        val event = eventFor(action, editor, myFixture.file.virtualFile)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun testDisabledForFileOutsideProject() {
        myFixture.configureByText("Outside.kt", "class O")
        val action = AddNoteAction()
        val event = eventFor(action, myFixture.editor, myFixture.file.virtualFile)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testDisabledWithoutEditorOrProject() {
        val action = AddNoteAction()
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.EMPTY_CONTEXT)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testPerformedSavesNoteWithResolvedAnchorAndNotifies() {
        val editor = openProjectFile("A.kt", source)
        editor.selectionModel.setSelection(source.indexOf("first"), source.indexOf("second()") + "second()".length)
        val repo = NoteRepository(db)
        var asked: CodeAnchor? = null
        val action = AddNoteAction(
            askUser = { _, anchor ->
                asked = anchor
                NoteDraft("Title", "Body")
            },
            saver = NoteSaver(repository = { repo }, projectKey = { "key" }),
        )

        action.actionPerformed(eventFor(action, editor, myFixture.file.virtualFile))
        PlatformTestUtil.waitWithEventsDispatching("note was not saved", { notifications.isNotEmpty() }, 20)

        assertEquals(CodeAnchor("src/A.kt", 3, 4, "        first()\n        second()", "A.b"), asked)
        val note = repo.list("key", false).get().single()
        assertEquals(NoteKind.CODE, note.kind)
        assertEquals("A.b", note.className)
        assertEquals(3, note.startLine)
        assertEquals(4, note.endLine)
        assertEquals("Title", note.title)
        assertEquals(1, published.get())
        assertEquals(NotificationType.INFORMATION, notifications.single().type)
    }

    fun testCancelledDialogSavesNothing() {
        val editor = openProjectFile("A.kt", source)
        val repo = NoteRepository(db)
        var asked = false
        val action = AddNoteAction(
            askUser = { _, _ ->
                asked = true
                null
            },
            saver = NoteSaver(repository = { repo }, projectKey = { "key" }),
        )
        action.actionPerformed(eventFor(action, editor, myFixture.file.virtualFile))
        PlatformTestUtil.waitWithEventsDispatching("dialog was not asked", { asked }, 20)

        assertTrue(repo.list("key", true).get().isEmpty())
        assertEquals(0, published.get())
        assertTrue(notifications.isEmpty())
    }

    fun testClassNameResolveFailureStillSavesWithNullName() {
        val editor = openProjectFile("A.kt", source)
        val repo = NoteRepository(db)
        val action = AddNoteAction(
            askUser = { _, _ -> NoteDraft("Title") },
            saver = NoteSaver(repository = { repo }, projectKey = { "key" }),
            nameResolver = object : AnchorNameResolver {
                override fun resolve(file: PsiFile, offset: Int): String? = error("psi broke")
            },
        )
        action.actionPerformed(eventFor(action, editor, myFixture.file.virtualFile))
        PlatformTestUtil.waitWithEventsDispatching("note was not saved", { notifications.isNotEmpty() }, 20)

        val note = repo.list("key", false).get().single()
        assertNull(note.className)
        assertEquals(1, note.startLine)
        assertEquals(NotificationType.INFORMATION, notifications.single().type)
    }

    // 닫힌 프로젝트는 서비스나 알림에 닿으면 안 되므로, 닿은 메서드를 기록하는 프록시로 확인한다
    private class ProjectProbe(var disposed: Boolean) {
        val touched = CopyOnWriteArrayList<String>()
        val disposedChecks = AtomicInteger()
        val project: Project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            when (method.name) {
                "isDisposed" -> {
                    disposedChecks.incrementAndGet()
                    disposed
                }
                "toString" -> "ProjectProbe"
                "hashCode" -> 1
                "equals" -> false
                else -> {
                    touched += method.name
                    null
                }
            }
        } as Project
    }

    private val anchor = CodeAnchor("src/A.kt", 1, 1, "snap", null)
    private val direct = Executor { it.run() }

    fun testNothingHappensWhenProjectIsAlreadyClosed() {
        val probe = ProjectProbe(disposed = true)
        var asked = false
        val action = AddNoteAction(askUser = { _, _ -> asked = true; NoteDraft("T") }, saver = NoteSaver(repository = { error("no") }))
        action.askAndSave(probe.project, anchor)
        assertFalse(asked)
        assertTrue(probe.touched.toString(), probe.touched.isEmpty())
    }

    fun testProjectClosedWhileDialogIsOpenSkipsSaving() {
        val probe = ProjectProbe(disposed = false)
        var saved = false
        val action = AddNoteAction(
            askUser = { _, _ -> probe.disposed = true; NoteDraft("T") },
            saver = NoteSaver(repository = { saved = true; NoteRepository(db) }, projectKey = { "key" }),
        )
        action.askAndSave(probe.project, anchor)
        assertFalse(saved)
        assertTrue(probe.touched.toString(), probe.touched.isEmpty())
    }

    fun testProjectClosedWhileSavingDoesNotNotify() {
        val probe = ProjectProbe(disposed = false)
        val repo = NoteRepository(db)
        val action = AddNoteAction(
            askUser = { _, _ -> NoteDraft("T") },
            saver = NoteSaver(
                repository = { repo },
                projectKey = { probe.disposed = true; "key" },
                background = direct,
                ui = direct,
                publish = {},
            ),
        )
        action.askAndSave(probe.project, anchor)
        // 시작, 다이얼로그 뒤, 완료 콜백에서 한 번씩 확인하므로 3번째 확인이 끝난 뒤에 알림 여부를 본다
        PlatformTestUtil.waitWithEventsDispatching("completion was not reached", { probe.disposedChecks.get() >= 3 }, 20)
        assertEquals(1, repo.list("key", false).get().size)
        assertTrue(probe.touched.toString(), probe.touched.isEmpty())
        assertTrue(notifications.isEmpty())
    }

    fun testSaveFailureShowsErrorNotificationWithReason() {
        val editor = openProjectFile("A.kt", source)
        val action = AddNoteAction(
            askUser = { _, _ -> NoteDraft("Title") },
            saver = NoteSaver(repository = { error("disk full") }, projectKey = { "key" }),
        )
        action.actionPerformed(eventFor(action, editor, myFixture.file.virtualFile))
        PlatformTestUtil.waitWithEventsDispatching("no error notification", { notifications.isNotEmpty() }, 20)

        val notification = notifications.single()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.contains("disk full"))
        assertEquals(0, published.get())
    }
}
