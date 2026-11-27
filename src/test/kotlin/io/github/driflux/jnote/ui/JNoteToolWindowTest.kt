package io.github.driflux.jnote.ui

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
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
import io.github.driflux.jnote.ai.SELECTED_NOTES
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.Tag
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

        JNoteToolWindowFactory {}.createToolWindowContent(project, window)

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

    fun testPanelProvidesTheSelectedNotesToActions() {
        val note = repo.create(NewNote.free("pick me")).get()
        val panel = newPanel()
        awaitUntil("list loaded") { panel.list.model.size == 1 }
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val context = { DataManager.getInstance().getDataContext(panel.list) }
        assertTrue(context().getData(SELECTED_NOTES).isNullOrEmpty())

        panel.list.selectedIndex = 0

        assertEquals(listOf(note.id), context().getData(SELECTED_NOTES)?.map { it.id })
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

    private val tagRepo get() = io.github.driflux.jnote.db.TagRepository(dbService.database)

    private fun chipTexts(panel: JNotePanel) = panel.tagBar.components.filterIsInstance<javax.swing.JToggleButton>().map { it.text }

    private fun chip(panel: JNotePanel, name: String) =
        panel.tagBar.components.filterIsInstance<javax.swing.JToggleButton>().single { it.text == name }

    private fun taggedNotes(): Pair<String, List<String>> {
        val key = projectKey()
        val a = repo.create(NewNote.code(key, "src/A.kt", 1, 2, "a")).get()
        val b = repo.create(NewNote.free("b")).get()
        repo.create(NewNote.free("plain")).get()
        tagRepo.replaceTags(a.id, listOf("sql", "todo")).get()
        tagRepo.replaceTags(b.id, listOf("sql")).get()
        tagRepo.getOrCreate("unused").get()
        return key to listOf("a", "b", "plain")
    }

    fun testTagBarShowsTheWholeDictionaryAndIsHiddenWhenItIsEmpty() {
        val empty = newPanel()
        awaitUntil("empty load") { empty.state.notes != null }
        assertFalse(empty.tagBar.isVisible)

        taggedNotes()
        val panel = newPanel()
        awaitUntil("tag bar built") { chipTexts(panel) == listOf("sql", "todo", "unused") }
        assertTrue(panel.tagBar.isVisible)
        assertTrue(panel.tagBar.components.filterIsInstance<javax.swing.JToggleButton>().none { it.isSelected })
    }

    fun testClickingTagChipsNarrowsTheListWithAnd() {
        taggedNotes()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 && chipTexts(panel).size == 3 }

        chip(panel, "sql").doClick()
        assertEquals(setOf("a", "b"), titles(panel).toSet())

        chip(panel, "todo").doClick()
        assertEquals(listOf("a"), titles(panel))

        chip(panel, "todo").doClick()
        chip(panel, "sql").doClick()
        assertEquals(3, titles(panel).size)
    }

    fun testRowsShowTheirTags() {
        taggedNotes()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }
        val rows = (0 until 3).map { panel.list.model.getElementAt(it) }
        assertEquals(listOf("sql", "todo"), rows.single { it.title == "a" }.tags.map { it.name })
        assertTrue(rows.single { it.title == "plain" }.tags.isEmpty())
    }

    fun testTagFilterSurvivesAReloadAndPicksUpNewTags() {
        taggedNotes()
        val panel = newPanel()
        awaitUntil("listed") { chipTexts(panel).size == 3 }
        chip(panel, "todo").doClick()

        val plain = repo.list(null, true).get().single { it.title == "plain" }
        tagRepo.replaceTags(plain.id, listOf("todo", "fresh")).get()
        panel.reload()

        awaitUntil("reloaded with the filter") { chipTexts(panel).contains("fresh") && titles(panel).toSet() == setOf("a", "plain") }
        assertTrue(chip(panel, "todo").isSelected)
    }

    fun testDeletingAFilteredTagDropsItsChipAndTheFilter() {
        taggedNotes()
        val panel = newPanel()
        awaitUntil("listed") { chipTexts(panel).size == 3 }
        chip(panel, "todo").doClick()
        assertEquals(listOf("a"), titles(panel))

        tagRepo.delete(tagRepo.list().get().single { it.name == "todo" }.id).get()
        panel.reload()

        awaitUntil("filter dropped") { titles(panel).size == 3 && !chipTexts(panel).contains("todo") }
        assertFalse(panel.state.tagFilterActive)
    }

    fun testEmptyTextExplainsATagFilterThatMatchesNothing() {
        val key = projectKey()
        taggedNotes()
        repo.create(NewNote.code(key, "src/Z.kt", 1, 1, "z")).get()
        val panel = newPanel()
        awaitUntil("listed") { chipTexts(panel).size == 3 && titles(panel).size == 4 }

        chip(panel, "unused").doClick()

        assertEquals(
            listOf(JNoteBundle.message("toolWindow.empty.tags.title"), JNoteBundle.message("toolWindow.empty.tags.hint")),
            emptyTextLines(panel),
        )
    }

    private fun answeringName(name: String?, seen: MutableList<Pair<Tag, List<Tag>>>): (com.intellij.openapi.project.Project, Tag, List<Tag>) -> String? =
        { _, tag, all ->
            seen += tag to all
            name
        }

    private fun confirming(answer: Boolean, seen: MutableList<Tag>): (com.intellij.openapi.project.Project, Tag) -> Boolean = { _, tag ->
        seen += tag
        answer
    }

    private fun tagPanel(
        askName: (com.intellij.openapi.project.Project, Tag, List<Tag>) -> String? = { _, _, _ -> null },
        confirm: (com.intellij.openapi.project.Project, Tag) -> Boolean = { _, _ -> false },
    ): JNotePanel = track(JNotePanel(project, AppDelayScheduler, askTagName = askName, confirmTagDelete = confirm))

    fun testRightClickMenuOffersRenameAndDelete() {
        taggedNotes()
        val panel = newPanel()
        awaitUntil("chips") { chipTexts(panel).size == 3 }
        val tag = panel.state.tags.first { it.name == "sql" }

        val items = panel.tagMenu(tag).components.filterIsInstance<javax.swing.JMenuItem>().map { it.text }

        assertEquals(listOf(JNoteBundle.message("toolWindow.tag.rename"), JNoteBundle.message("toolWindow.tag.delete")), items)
    }

    fun testRenameAppliesToEveryNoteAndKeepsTheChipOn() {
        taggedNotes()
        val seen = mutableListOf<Pair<Tag, List<Tag>>>()
        val panel = tagPanel(askName = answeringName(" query ", seen))
        awaitUntil("chips") { chipTexts(panel).size == 3 }
        chip(panel, "sql").doClick()
        val tag = panel.state.tags.first { it.name == "sql" }

        panel.renameTag(tag).get()

        awaitUntil("renamed chip") { chipTexts(panel) == listOf("query", "todo", "unused") }
        assertEquals(listOf("sql", "todo", "unused"), seen.single().second.map { it.name })
        assertTrue(chip(panel, "query").isSelected)
        assertEquals(setOf("a", "b"), titles(panel).toSet())
        val all = repo.list(null, true).get().associateBy { it.title }
        assertEquals(listOf("query", "todo"), tagRepo.tagsOf(all.getValue("a").id).get().map { it.name })
        assertEquals(listOf("query"), tagRepo.tagsOf(all.getValue("b").id).get().map { it.name })
    }

    fun testCancelledRenameChangesNothing() {
        taggedNotes()
        val panel = tagPanel(askName = answeringName(null, mutableListOf()))
        awaitUntil("chips") { chipTexts(panel).size == 3 }

        panel.renameTag(panel.state.tags.first { it.name == "sql" }).get()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(listOf("sql", "todo", "unused"), tagRepo.list().get().map { it.name })
    }

    fun testRenameToAnExistingNameIsReportedAndChangesNothing() {
        taggedNotes()
        val panel = tagPanel(askName = answeringName("todo", mutableListOf()))
        awaitUntil("chips") { chipTexts(panel).size == 3 }
        val shown = mutableListOf<com.intellij.notification.Notification>()
        val listener = object : com.intellij.notification.Notifications {
            override fun notify(notification: com.intellij.notification.Notification) { shown += notification }
        }
        project.messageBus.connect(testRootDisposable).subscribe(com.intellij.notification.Notifications.TOPIC, listener)
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(com.intellij.notification.Notifications.TOPIC, listener)

        panel.renameTag(panel.state.tags.first { it.name == "sql" }).get()

        awaitUntil("conflict reported") { shown.any { it.title == JNoteBundle.message("toolWindow.error.tagExists.title") } }
        assertEquals(listOf("sql", "todo", "unused"), tagRepo.list().get().map { it.name })
    }

    fun testDeleteAsksFirstThenOnlyDetachesTheTagFromNotes() {
        taggedNotes()
        val asked = mutableListOf<Tag>()
        val panel = tagPanel(confirm = confirming(true, asked))
        awaitUntil("chips") { chipTexts(panel).size == 3 && titles(panel).size == 3 }
        val tag = panel.state.tags.first { it.name == "sql" }

        panel.deleteTag(tag).get()

        awaitUntil("chip gone") { chipTexts(panel) == listOf("todo", "unused") }
        assertEquals(listOf(tag), asked)
        assertEquals(3, repo.list(null, true).get().size)
        assertEquals(3, titles(panel).size)
        assertEquals(listOf("todo"), tagRepo.tagsOf(repo.list(null, true).get().single { it.title == "a" }.id).get().map { it.name })
    }

    fun testDeclinedDeleteKeepsTheTag() {
        taggedNotes()
        val asked = mutableListOf<Tag>()
        val panel = tagPanel(confirm = confirming(false, asked))
        awaitUntil("chips") { chipTexts(panel).size == 3 }

        panel.deleteTag(panel.state.tags.first { it.name == "sql" }).get()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(1, asked.size)
        assertEquals(listOf("sql", "todo", "unused"), tagRepo.list().get().map { it.name })
    }

    private fun search(panel: JNotePanel, text: String) {
        panel.searchField.text = text
        panel.applyQuery(text)
    }

    fun testSearchNarrowsTheListAndKeepsMostRecentFirst() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "alpha handler")).get()
        repo.create(NewNote.code(key, "src/B.kt", 1, 2, "beta")).get()
        repo.create(NewNote.free("Handler notes")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }

        search(panel, "handler")

        awaitUntil("narrowed") { titles(panel) == listOf("Handler notes", "alpha handler") }
    }

    fun testSearchFindsKoreanTextByItsStem() {
        repo.create(NewNote.free("취소건을 처리한다")).get()
        repo.create(NewNote.free("무관한 노트")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 2 }

        search(panel, "취소")

        awaitUntil("narrowed") { titles(panel) == listOf("취소건을 처리한다") }
    }

    fun testOneCharacterDoesNotSearchAndClearingRestoresTheList() {
        repo.create(NewNote.free("alpha")).get()
        repo.create(NewNote.free("beta")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 2 }

        search(panel, "alp")
        awaitUntil("narrowed") { titles(panel) == listOf("alpha") }

        search(panel, "a")
        assertEquals(2, titles(panel).size)
        assertEquals("", panel.list.emptyText.text)

        search(panel, "alp")
        awaitUntil("narrowed again") { titles(panel) == listOf("alpha") }
        search(panel, "")
        assertEquals(2, titles(panel).size)
    }

    fun testNoResultGuidanceNamesTheQueryAndSuggestsAllProjects() {
        repo.create(NewNote.free("alpha")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 1 }

        search(panel, "zzz")

        awaitUntil("no results") { panel.state.noSearchResults }
        assertEquals(
            listOf(JNoteBundle.message("toolWindow.search.empty.title", "zzz"), JNoteBundle.message("toolWindow.search.empty.hint")),
            emptyTextLines(panel),
        )

        panel.allProjectsBox.doClick()
        awaitUntil("all-projects hint") { emptyTextLines(panel).lastOrNull() == JNoteBundle.message("toolWindow.search.empty.hint.all") }
    }

    fun testSearchFollowsAllProjectsAndNoteChanges() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "needle mine")).get()
        repo.create(NewNote.code("github.com/org/other", "src/B.kt", 1, 2, "needle theirs")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel) == listOf("needle mine") }
        search(panel, "needle")
        awaitUntil("searched") { panel.state.match != null }
        assertEquals(listOf("needle mine"), titles(panel))

        panel.allProjectsBox.doClick()
        awaitUntil("all projects searched") { titles(panel).size == 2 }

        repo.create(NewNote.code(key, "src/C.kt", 1, 2, "needle added")).get()
        panel.reload()
        awaitUntil("new match listed first") { titles(panel).first() == "needle added" && titles(panel).size == 3 }
    }

    fun testTypingWaitsForTheDelayAndOnlyTheLastInputSearches() {
        repo.create(NewNote.free("alpha")).get()
        repo.create(NewNote.free("beta")).get()
        val scheduled = mutableListOf<() -> Unit>()
        val panel = track(JNotePanel(project, recordingScheduler(scheduled)))
        awaitUntil("listed") { titles(panel).size == 2 }

        panel.searchField.text = "al"
        panel.searchField.text = "alp"
        assertEquals(1, scheduled.size)
        assertEquals(2, titles(panel).size)

        scheduled.single().invoke()
        awaitUntil("narrowed") { titles(panel) == listOf("alpha") }
    }

    fun testStaleSearchResultDoesNotOverrideTheNewQuery() {
        repo.create(NewNote.free("alpha")).get()
        repo.create(NewNote.free("beta")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 2 }
        val gate = java.util.concurrent.CountDownLatch(1)
        val blocker = dbService.database.submit { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }

        panel.applyQuery("alp")
        panel.applyQuery("bet")
        gate.countDown()
        blocker.get()

        awaitUntil("latest query wins") { titles(panel) == listOf("beta") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf("beta"), titles(panel))
    }
}
