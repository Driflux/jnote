package io.github.driflux.jnote.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.notification.NotificationAction
import com.intellij.testFramework.TestActionEvent
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path

class NoteDetailPanelTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-detail").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            dbService.switchTo(DatabasePaths.default())
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun projectKey(): String =
        ApplicationManager.getApplication().executeOnPooledThread<String> { project.service<ProjectKeyService>().resolve().key }.get()

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $message" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun newPanel(expected: Int): JNotePanel {
        val panel = JNotePanel(project)
        Disposer.register(testRootDisposable, panel)
        awaitUntil("list loaded") { panel.list.model.size == expected }
        return panel
    }

    fun testSelectingANoteShowsItsDetail() {
        val key = projectKey()
        repo.create(
            NewNote.code(
                key, "src/Foo.kt", 3, 5, "why locked", body = "because", className = "Foo", snapshot = "lock()",
                aiSource = "Claude", prompt = "explain",
            ),
        ).get()
        val panel = newPanel(1)
        assertFalse(panel.detail.isVisible)

        panel.list.selectedIndex = 0
        val d = panel.detail
        assertTrue(d.isVisible)
        assertEquals("why locked", d.titleLabel.text)
        assertEquals("Foo", d.classLabel.text)
        assertEquals("src/Foo.kt:L3-5", d.locationLabel.text)
        assertEquals("lock()", d.snippetArea.text)
        assertEquals("because", d.bodyArea.text)
        assertEquals("Claude", d.aiSourceLabel.text)
        assertEquals("explain", d.aiPromptArea.text)
        assertTrue(d.goToCodeButton.isVisible)
    }

    fun testFreeNoteHidesGoToCodeAndUsesMonospaceBody() {
        repo.create(NewNote.free("query", "select 1")).get()
        val panel = newPanel(1)
        panel.list.selectedIndex = 0
        assertFalse(panel.detail.goToCodeButton.isVisible)
        assertEquals("select 1", panel.detail.bodyArea.text)
        assertFalse(panel.detail.locationLabel.isVisible)
    }

    fun testGoToCodeOpensTheFileAtTheStartLine() {
        val key = projectKey()
        // 노트 경로는 basePath 기준이므로 라이트 픽스처의 temp:// 파일이 아니라 basePath 아래 실제 파일을 쓴다
        val real = Path.of(project.basePath!!, "src", "Foo.kt")
        Files.createDirectories(real.parent)
        Files.writeString(real, "line1\nline2\nline3\nline4\n")
        Disposer.register(testRootDisposable) { real.toFile().delete() }
        val note = repo.create(NewNote.code(key, "src/Foo.kt", 3, 4, "n")).get()

        val opened = PlatformTestUtil.waitForFuture(CodeNavigator.goTo(project, note), 10_000)

        assertTrue(opened)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("Foo.kt", editor.virtualFile.name)
        assertEquals(2, editor.caretModel.logicalPosition.line)
    }

    fun testGoToCodeWithMissingFileNotifiesAndOpensNothing() {
        captureNotifications()
        val key = projectKey()
        val note = repo.create(NewNote.code(key, "src/Gone.kt", 1, 1, "n")).get()

        val opened = PlatformTestUtil.waitForFuture(CodeNavigator.goTo(project, note), 10_000)

        assertFalse(opened)
        assertTrue(shownNotifications().any { it.content.contains("src/Gone.kt") })
        assertNull(FileEditorManager.getInstance(project).selectedTextEditor)
    }

    fun testGoToCodeRejectsPathsOutsideTheProject() {
        val key = projectKey()
        val outside = Path.of(project.basePath!!).resolve("../jnote-outside-test.kt").normalize()
        Files.writeString(outside, "class Outside")
        try {
            val note = repo.create(NewNote.code(key, "../jnote-outside-test.kt", 1, 1, "n")).get()
            assertTrue("the file must really exist", Files.exists(outside))

            val opened = PlatformTestUtil.waitForFuture(CodeNavigator.goTo(project, note), 10_000)

            assertFalse(opened)
            assertNull(FileEditorManager.getInstance(project).selectedTextEditor)
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    fun testFindResolvesAgainstTheGivenBaseNotTheGuessedProjectDir() {
        val otherBase = Files.createTempDirectory("jnote-base").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, otherBase.toString())
        try {
            Files.createDirectories(otherBase.resolve("src"))
            Files.writeString(otherBase.resolve("src/X.kt"), "class X")
            val found = CodeTargets.findUnder(otherBase.toString(), "src/X.kt")!!
            assertEquals(otherBase.resolve("src/X.kt").toString(), found.path)
            assertNull(CodeTargets.findUnder(project.basePath!!, "src/X.kt"))
            // VFS가 아직 모르는 파일도 같은 기준으로 새로 확인한다
            Files.writeString(otherBase.resolve("src/Late.kt"), "class Late")
            assertNotNull(CodeTargets.findUnder(otherBase.toString(), "src/Late.kt"))
        } finally {
            otherBase.toFile().deleteRecursively()
        }
    }

    fun testFindIgnoresDirectoriesAndUnsafePaths() {
        val base = Files.createTempDirectory("jnote-base").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, base.toString())
        try {
            Files.createDirectories(base.resolve("src"))
            assertNull(CodeTargets.findUnder(base.toString(), "src"))
            assertNull(CodeTargets.findUnder(base.toString(), "../x.kt"))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun collectNotifications(): MutableList<Notification> {
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

    fun testLookupFailureIsReportedNotSwallowed() {
        val shown = collectNotifications()
        val note = repo.create(NewNote.code(projectKey(), "src/Foo.kt", 1, 1, "n")).get()

        val done = CodeNavigator.goTo(project, note) { _, _ -> error("vfs <broke>") }
        PlatformTestUtil.waitWithEventsDispatching("future did not complete", { done.isDone }, 10)

        assertTrue(done.isCompletedExceptionally)
        val notification = shown.single()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.contains("vfs &lt;broke&gt;"))
    }

    fun testCodeNoteWithoutPathNotifiesAndFreeNoteDoesNot() {
        val shown = collectNotifications()
        assertFalse(CodeNavigator.open(project, NoteKind.FREE, null, null).get())
        assertTrue(shown.isEmpty())

        assertFalse(CodeNavigator.open(project, NoteKind.CODE, null, 1).get())
        assertEquals(NotificationType.WARNING, shown.single().type)
    }

    // 닫힌 프로젝트는 건드리면 안 되므로 호출된 메서드를 기록하는 프록시를 쓴다
    private fun closedProject(touched: MutableList<String>, flag: java.util.concurrent.atomic.AtomicBoolean) =
        java.lang.reflect.Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            when (method.name) {
                "isDisposed" -> flag.get()
                "toString" -> "ClosedProject"
                "hashCode" -> 1
                "equals" -> false
                else -> {
                    touched += method.name
                    null
                }
            }
        } as Project

    fun testGoToOnClosedProjectCompletesInsteadOfStayingPending() {
        val touched = mutableListOf<String>()
        val flag = java.util.concurrent.atomic.AtomicBoolean(true)
        val done = CodeNavigator.open(closedProject(touched, flag = flag), NoteKind.CODE, "src/A.kt", 1)
        assertTrue(done.isDone)
        assertFalse(done.get())
        assertTrue(touched.toString(), touched.isEmpty())
    }

    fun testProjectClosingDuringLookupStillCompletesTheFuture() {
        val touched = mutableListOf<String>()
        val flag = java.util.concurrent.atomic.AtomicBoolean(false)
        val closing = closedProject(touched, flag = flag)

        val done = CodeNavigator.open(closing, NoteKind.CODE, "src/A.kt", 1) { _, _ ->
            flag.set(true)
            null
        }

        assertFalse(PlatformTestUtil.waitForFuture(done, 10_000))
        assertTrue(touched.toString(), touched.isEmpty())
    }

    fun testFreeNoteHasNoTarget() {
        val note = repo.create(NewNote.free("q")).get()
        assertFalse(CodeNavigator.goTo(project, note).get())
    }

    private val captured = java.util.concurrent.CopyOnWriteArrayList<Notification>()

    private fun shownNotifications(): List<Notification> = captured.toList()

    private fun captureNotifications() {
        val listener = object : Notifications {
            override fun notify(notification: Notification) {
                if (captured.none { it === notification }) captured += notification
            }
        }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, listener)
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, listener)
    }

    private fun awaitDeletedNotification(): Notification {
        val title = JNoteBundle.message("toolWindow.notification.deleted")
        awaitUntil("deleted notification shown, got " + shownNotifications().map { it.title + ": " + it.content }) { shownNotifications().any { it.content == title } }
        return shownNotifications().single { it.content == title }
    }

    fun testDeleteButtonDeletesImmediatelyThenUndoRestoresEverything() {
        captureNotifications()
        val key = projectKey()
        val tags = TagRepository(dbService.database)
        val tag = tags.getOrCreate("keep").get()
        val note = repo.create(NewNote.code(key, "src/Foo.kt", 2, 3, "doomed", body = "b", aiSource = "Claude")).get()
        tags.attach(note.id, tag.id).get()
        val panel = newPanel(1)
        panel.list.selectedIndex = 0

        panel.detail.deleteButton.doClick()

        awaitUntil("list emptied") { panel.list.model.size == 0 }
        assertNull(repo.get(note.id).get())
        assertFalse(panel.detail.isVisible)
        val notification = awaitDeletedNotification()
        val undo = notification.actions.single() as NotificationAction

        undo.actionPerformed(TestActionEvent.createTestEvent(undo), notification)

        awaitUntil("list restored") { panel.list.model.size == 1 }
        assertEquals(note, repo.get(note.id).get())
        assertEquals(listOf(tag), tags.tagsOf(note.id).get())
        assertEquals("doomed", panel.list.model.getElementAt(0).title)
    }

    fun testDeleteNotifiesListenersOnDeleteAndOnUndo() {
        captureNotifications()
        val key = projectKey()
        val note = repo.create(NewNote.code(key, "src/Foo.kt", 1, 1, "n")).get()
        val events = java.util.concurrent.atomic.AtomicInteger()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { events.incrementAndGet() })
        val panel = newPanel(1)

        panel.delete(note).get()
        awaitUntil("delete event") { events.get() == 1 }
        val notification = awaitDeletedNotification()
        val undo = notification.actions.single() as NotificationAction
        undo.actionPerformed(TestActionEvent.createTestEvent(undo), notification)

        awaitUntil("undo event") { events.get() == 2 }
    }

    fun testUndoAfterTheDatabaseWasSwitchedReportsAnErrorInsteadOfRestoringElsewhere() {
        captureNotifications()
        val key = projectKey()
        val note = repo.create(NewNote.code(key, "src/Foo.kt", 1, 1, "n")).get()
        val panel = newPanel(1)
        panel.delete(note).get()
        val notification = awaitDeletedNotification()

        val otherDir = Files.createTempDirectory("jnote-detail-other").toRealPath()
        try {
            dbService.switchTo(otherDir.resolve("other.db"))
            val undo = notification.actions.single() as NotificationAction
            undo.actionPerformed(TestActionEvent.createTestEvent(undo), notification)

            val restoreFailed = JNoteBundle.message("toolWindow.error.restore.title")
            awaitUntil("restore error shown") { shownNotifications().any { it.title == restoreFailed } }
            val error = shownNotifications().single { it.title == restoreFailed }
            assertTrue(error.content, error.content.contains(JNoteBundle.message("toolWindow.error.restore.databaseSwitched")))
            assertNull(repo.get(note.id).get())
        } finally {
            dbService.switchTo(dir.resolve("jnote.db"))
            otherDir.toFile().deleteRecursively()
        }
    }

    private val delays = mutableListOf<Long>()
    private val tasks = mutableListOf<Runnable>()
    private val futures = mutableListOf<java.util.concurrent.ScheduledFuture<*>>()
    private val pending by lazy { java.util.concurrent.ScheduledThreadPoolExecutor(1) }

    // 시간을 기다리지 않고 만료 작업을 직접 돌리기 위한 스케줄러
    private fun recordingSchedule(task: Runnable, delayMs: Long): java.util.concurrent.ScheduledFuture<*> {
        delays += delayMs
        tasks += task
        return pending.schedule({}, 1, java.util.concurrent.TimeUnit.HOURS).also { futures += it }
    }

    fun testDefaultExpiryIsEightSecondsAndDismissingCancelsIt() {
        try {
            val expiring = ToolWindowNotifier.deleted(project, schedule = ::recordingSchedule) { }
            assertEquals(listOf(8_000L), delays)
            assertFalse(expiring.isExpired)
            tasks.single().run()
            assertTrue(expiring.isExpired)

            ToolWindowNotifier.deleted(project, schedule = ::recordingSchedule) { }.expire()
            assertTrue("dismissing must cancel the pending expiry", futures[1].isCancelled)
        } finally {
            pending.shutdownNow()
        }
    }

    fun testDeletedNotificationExpires() {
        val notification = ToolWindowNotifier.deleted(project, expireAfterMs = 100) { }
        awaitUntil("notification expired") { notification.isExpired }
    }

    fun testDeletingAnAlreadyDeletedNoteStillRefreshes() {
        val key = projectKey()
        val note = repo.create(NewNote.code(key, "src/Foo.kt", 1, 1, "n")).get()
        val panel = newPanel(1)
        repo.delete(note.id).get()

        panel.delete(note).get()

        awaitUntil("list emptied") { panel.list.model.size == 0 }
    }
}
