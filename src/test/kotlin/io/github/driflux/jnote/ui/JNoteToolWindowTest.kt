package io.github.driflux.jnote.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
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

    // 테스트가 만든 패널. 닫고 읽기가 끝나야 DB 경로를 바꿔도 늦은 읽기가 엉뚱한 DB를 열지 않는다
    private val panels = mutableListOf<JNotePanel>()

    override fun tearDown() {
        try {
            try {
                panels.forEach { Disposer.dispose(it) }
                awaitUntil("pending loads drained") { panels.all { it.isIdle } }
            } finally {
                dbService.switchTo(DatabasePaths.default())
                dir.toFile().deleteRecursively()
            }
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

    private fun track(panel: JNotePanel): JNotePanel = panel.also {
        panels += it
        Disposer.register(testRootDisposable, it)
    }

    private fun newPanel(): JNotePanel = track(JNotePanel(project))

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
        // setUp이 이미 한 번 전환했으므로, 여기서 새 경로로 다시 전환해 "아무것도 열리지 않은" 상태를 이 테스트가 직접 만든다
        dbService.switchTo(dir.resolve("fresh.db"))
        assertNull(dbService.openDatabaseOrNull())

        // 등록 정보를 읽고 팩토리를 만드는 것까지가 등록이다. 패널은 만들지 않는다
        ToolWindowEP.EP_NAME.extensionList.single { it.id == "JNote" }
        JNoteToolWindowFactory()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull("registration must not open the database", dbService.openDatabaseOrNull())

        // 같은 단언이 실패할 수 있음을 보이는 대조: 패널이 처음 읽으면 열린다
        newPanel()
        awaitUntil("db opened by first load") { dbService.openDatabaseOrNull() != null }
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
        val closed = newPanel()
        val control = newPanel()
        awaitUntil("both panels loaded") { closed.state.notes != null && control.state.notes != null }
        Disposer.dispose(closed)

        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "late")).get()
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()

        // 닫지 않은 대조 패널이 갱신되는 것을 먼저 확인한 뒤에야 닫힌 패널이 그대로인지 본다
        awaitUntil("control panel updated") { titles(control) == listOf("late") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0, closed.list.model.size)
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

        // 실제로 등록된 액션의 메뉴 문구와 키맵의 단축키에서 기대값을 만들어, 화면의 두 번째 줄과 비교한다
        val menuText = ActionManager.getInstance().getAction("JNote.AddNote").templatePresentation.text
        val shortcut = KeymapUtil.getShortcutText(KeymapManager.getInstance().activeKeymap.getShortcuts("JNote.AddNote").first())
        val expectedHint = JNoteBundle.message("toolWindow.empty.hint", menuText, shortcut)
        assertEquals(listOf(JNoteBundle.message("toolWindow.empty.title"), expectedHint), emptyTextLines(panel))
    }

    private fun emptyTextLines(panel: JNotePanel): List<String> =
        panel.list.emptyText.wrappedFragmentsIterable.map { (it as SimpleColoredComponent).getCharSequence(false).toString() }

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

        val panel = track(JNotePanel(project, scheduler))
        awaitUntil("timer armed") { scheduled.size == 1 }
        assertFalse(panel.spinner.visible)

        scheduled.single().invoke()
        assertTrue(panel.spinner.visible)

        gate.countDown()
        blocker.get()
        awaitUntil("load finished and spinner hidden") { panel.state.notes != null && !panel.spinner.visible }
    }

    // 아이콘의 자식으로 건 표식은 아이콘이 정리될 때만 불린다
    private fun markWhenDisposed(parent: com.intellij.openapi.Disposable, flag: java.util.concurrent.atomic.AtomicBoolean) =
        Disposer.register(parent, com.intellij.openapi.Disposable { flag.set(true) })

    fun testDisposingThePanelDisposesTheSpinnerIcon() {
        val panel = newPanel()
        val iconDisposed = java.util.concurrent.atomic.AtomicBoolean()
        markWhenDisposed(panel.spinnerIcon, iconDisposed)
        assertFalse(iconDisposed.get())
        Disposer.dispose(panel)
        assertTrue(iconDisposed.get())
    }

    fun testTimerFiringAfterDisposeDoesNotResumeTheIcon() {
        val scheduled = mutableListOf<() -> Unit>()
        val gate = java.util.concurrent.CountDownLatch(1)
        val blocker = dbService.database.submit { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        val panel = track(JNotePanel(project, recordingScheduler(scheduled)))
        awaitUntil("timer armed") { scheduled.size == 1 }
        val late = scheduled.single()

        Disposer.dispose(panel)
        // 취소가 늦어 이미 대기열에 들어간 타이머가 지금 도착한 상황
        late.invoke()

        assertFalse(panel.spinner.visible)
        assertFalse(panel.spinnerIcon.isRunning)
        assertFalse(panel.spinnerIcon.isVisible)
        gate.countDown()
        blocker.get()
    }

    fun testFastLoadLeavesTheSpinnerHidden() {
        val panel = track(JNotePanel(project, neverFiringScheduler()))
        awaitUntil("load finished") { panel.state.notes != null }
        assertFalse(panel.spinner.visible)
    }

    private fun select(panel: JNotePanel, filter: NoteFilter) {
        panel.filterCombo.selectedItem = filter
    }

    fun testFilterComboOffersAllCodeFreeAndDefaultsToAll() {
        val panel = newPanel()
        assertEquals(listOf(NoteFilter.ALL, NoteFilter.CODE, NoteFilter.FREE), (0 until panel.filterCombo.itemCount).map { panel.filterCombo.getItemAt(it) })
        assertEquals(NoteFilter.ALL, panel.filterCombo.selectedItem)
    }

    fun testFilterShowsOnlyThatKindAndSwitchingBackRestoresAll() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "code")).get()
        repo.create(NewNote.free("free")).get()
        val panel = newPanel()
        awaitUntil("both listed") { titles(panel).size == 2 }

        select(panel, NoteFilter.CODE)
        assertEquals(listOf("code"), titles(panel))
        select(panel, NoteFilter.FREE)
        assertEquals(listOf("free"), titles(panel))
        select(panel, NoteFilter.ALL)
        assertEquals(2, titles(panel).size)
    }

    fun testFilterSurvivesAReload() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "code")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel) == listOf("code") }
        select(panel, NoteFilter.FREE)
        repo.create(NewNote.free("free")).get()

        panel.reload()

        awaitUntil("reloaded with filter") { titles(panel) == listOf("free") }
    }

    fun testFilteringOutTheSelectedNoteClearsTheDetail() {
        repo.create(NewNote.free("free")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel) == listOf("free") }
        panel.list.selectedIndex = 0
        assertTrue(panel.detail.isVisible)

        select(panel, NoteFilter.CODE)

        assertFalse(panel.detail.isVisible)
    }

    fun testEmptyTextNamesTheFilteredKind() {
        repo.create(NewNote.free("free")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel) == listOf("free") }
        assertEquals("", panel.list.emptyText.text)

        select(panel, NoteFilter.CODE)
        assertEquals(JNoteBundle.message("toolWindow.empty.code.title"), emptyTextLines(panel).first())

        repo.delete(repo.list(null, true).get().single().id).get()
        panel.reload()
        awaitUntil("emptied") { panel.state.notes?.isEmpty() == true }
        select(panel, NoteFilter.FREE)
        assertEquals(
            listOf(JNoteBundle.message("toolWindow.empty.free.title"), JNoteBundle.message("toolWindow.empty.free.hint")),
            emptyTextLines(panel),
        )
        select(panel, NoteFilter.ALL)
        assertEquals(JNoteBundle.message("toolWindow.empty.title"), emptyTextLines(panel).first())
    }
}
