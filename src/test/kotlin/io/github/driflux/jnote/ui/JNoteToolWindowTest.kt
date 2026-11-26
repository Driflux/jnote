package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path

class JNoteToolWindowTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-toolwindow").toRealPath()
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

    private fun titles(panel: JNotePanel) = (0 until panel.list.model.size).map { panel.list.model.getElementAt(it).title }

    private fun newPanel(): JNotePanel = JNotePanel(project).also { Disposer.register(testRootDisposable, it) }

    // JUnit3 러너가 test로 시작하는 메서드 안의 람다를 테스트로 오인하므로 람다는 도우미 함수에서 만든다
    private fun neverFiringScheduler() = DelayScheduler { _, _ -> Cancellable { } }

    private fun recordingScheduler(into: MutableList<() -> Unit>) = DelayScheduler { _, task ->
        into += task
        Cancellable { into.remove(task) }
    }

    fun testToolWindowIsRegisteredOnTheRightWithAFactory() {
        val ep = ToolWindowEP.EP_NAME.extensionList.single { it.id == "JNote" }
        assertEquals(ToolWindowAnchor.RIGHT.toString(), ep.anchor)
        assertEquals(JNoteToolWindowFactory::class.java.name, ep.factoryClass)
        assertTrue(ep.isDoNotActivateOnStart)
    }

    fun testFactoryAddsOneContentAndLoadsOnlyWhenInvoked() {
        val window = com.intellij.toolWindow.ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        assertNull(dbService.openDatabaseOrNull())

        JNoteToolWindowFactory().createToolWindowContent(project, window)

        assertEquals(1, window.contentManager.contentCount)
        assertTrue(window.contentManager.getContent(0)!!.component is JNotePanel)
        awaitUntil("db opened by the first load") { dbService.openDatabaseOrNull() != null }
    }

    fun testRegistrationDoesNotOpenTheDatabase() {
        // 등록과 서비스 접근만으로는 연결이 열리지 않는다. 열리는 것은 패널이 처음 읽을 때다
        assertNull(dbService.openDatabaseOrNull())
        val panel = newPanel()
        awaitUntil("db opened by first load") { dbService.openDatabaseOrNull() != null }
        assertNotNull(panel)
    }

    fun testPanelListsCurrentProjectAndGlobalNotesMostRecentFirst() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "first")).get()
        repo.create(NewNote.code("other/project", "src/B.kt", 1, 2, "elsewhere")).get()
        repo.create(NewNote.free("global")).get()
        repo.create(NewNote.code(key, "src/C.kt", 1, 2, "last")).get()

        val panel = newPanel()
        awaitUntil("list loaded") { panel.list.model.size == 3 }
        assertEquals(listOf("last", "global", "first"), titles(panel))
    }

    fun testPanelReloadsWhenNotesChange() {
        val key = projectKey()
        val panel = newPanel()
        awaitUntil("empty load done") { dbService.openDatabaseOrNull() != null }
        assertEquals(0, panel.list.model.size)

        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "added")).get()
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        awaitUntil("reloaded after event") { titles(panel) == listOf("added") }
    }

    fun testDisposedPanelIgnoresLaterEvents() {
        val key = projectKey()
        val panel = JNotePanel(project)
        awaitUntil("first load") { dbService.openDatabaseOrNull() != null }
        Disposer.dispose(panel)
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "late")).get()
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0, panel.list.model.size)
    }

    fun testAllProjectsToggleListsOtherProjectsNotesWithProjectName() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "mine")).get()
        repo.create(NewNote.code("github.com/org/other", "src/B.kt", 1, 2, "theirs")).get()
        val panel = newPanel()
        awaitUntil("default list") { titles(panel) == listOf("mine") }
        assertFalse(panel.allProjectsBox.isSelected)

        panel.allProjectsBox.doClick()
        awaitUntil("all projects list") { titles(panel).size == 2 }

        assertTrue(panel.state.allProjects)
        val theirs = (0 until 2).map { panel.list.model.getElementAt(it) }.single { it.title == "theirs" }
        assertEquals("other", theirs.projectName)

        panel.allProjectsBox.doClick()
        awaitUntil("back to current project") { titles(panel) == listOf("mine") }
    }

    fun testEmptyStateShowsGuidanceWithTheShortcutOnlyAfterTheFirstLoad() {
        val panel = newPanel()
        assertFalse(panel.state.showEmptyState)
        awaitUntil("empty load done") { panel.state.showEmptyState }

        assertEquals(JNoteBundle.message("toolWindow.empty.title"), panel.list.emptyText.text)
        val hint = JNoteBundle.message("toolWindow.empty.hint", JNoteBundle.message("toolWindow.empty.shortcut"))
        assertTrue(hint.contains(JNoteBundle.message("toolWindow.empty.shortcut")))    }

    fun testEmptyGuidanceDisappearsWhenANoteArrives() {
        val key = projectKey()
        val panel = newPanel()
        awaitUntil("empty load done") { panel.state.showEmptyState }
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "added")).get()
        panel.reload()
        awaitUntil("note listed") { titles(panel) == listOf("added") }
        assertFalse(panel.state.showEmptyState)
        assertEquals("", panel.list.emptyText.text)
    }

    fun testSpinnerShowsOnlyWhenTheLoadIsSlow() {
        val scheduled = mutableListOf<() -> Unit>()
        val scheduler = recordingScheduler(scheduled)
        // DB 스레드를 막아 첫 읽기를 붙잡아 둔다
        val gate = java.util.concurrent.CountDownLatch(1)
        val blocker = dbService.database.submit { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }

        val panel = JNotePanel(project, scheduler).also { Disposer.register(testRootDisposable, it) }
        awaitUntil("timer armed") { scheduled.size == 1 }
        assertFalse(panel.spinner.visible)

        scheduled.single().invoke()
        assertTrue(panel.spinner.visible)

        gate.countDown()
        blocker.get()
        awaitUntil("load finished and spinner hidden") { panel.state.notes != null && !panel.spinner.visible }
    }

    fun testFastLoadLeavesTheSpinnerHidden() {
        val panel = JNotePanel(project, neverFiringScheduler()).also { Disposer.register(testRootDisposable, it) }
        awaitUntil("load finished") { panel.state.notes != null }
        assertFalse(panel.spinner.visible)
    }
}
