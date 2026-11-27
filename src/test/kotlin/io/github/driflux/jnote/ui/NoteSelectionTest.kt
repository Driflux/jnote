package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.NoteSelectRequested
import io.github.driflux.jnote.NoteSelectRequestedListener
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path

class NoteSelectionTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-selection").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    // 테스트가 만든 패널. 닫고 읽기가 끝나야 DB 경로를 바꿔도 늦은 읽기가 엉뚱한 DB를 열지 않는다
    private val panels = mutableListOf<JNotePanel>()

    override fun tearDown() {
        try {
            try {
                panels.forEach { Disposer.dispose(it) }
                awaitUntil("pending loads drained") { panels.all { it.isIdle } }
                project.service<PendingNoteSelection>().take()
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

    private fun newPanel() = JNotePanel(project).also {
        panels += it
        Disposer.register(testRootDisposable, it)
    }

    private fun createThree(): List<Long> {
        val key = projectKey()
        return (1..3).map { repo.create(NewNote.code(key, "src/A.kt", it, it, "n$it")).get().id }
    }

    fun testRequestSelectsTheNoteInAnOpenPanel() {
        val ids = createThree()
        val panel = newPanel()
        awaitUntil("list loaded") { panel.list.model.size == 3 }

        NoteSelection.request(project, ids[0]) { }

        awaitUntil("selected") { panel.list.selectedValue?.summary?.id == ids[0] }
        assertNull(project.service<PendingNoteSelection>().peek())
    }

    fun testRequestMadeBeforeThePanelExistsIsAppliedAfterItsFirstLoad() {
        val ids = createThree()
        var opened = 0
        NoteSelection.request(project, ids[1]) { opened++ }
        assertEquals(1, opened)

        val panel = newPanel()

        awaitUntil("selected after first load") { panel.list.selectedValue?.summary?.id == ids[1] }
        assertNull(project.service<PendingNoteSelection>().peek())
    }

    fun testRequestForANoteNotInTheListKeepsTheSelection() {
        val ids = createThree()
        val panel = newPanel()
        awaitUntil("list loaded") { panel.list.model.size == 3 }
        NoteSelection.request(project, ids[2]) { }
        awaitUntil("selected") { panel.list.selectedValue?.summary?.id == ids[2] }

        NoteSelection.request(project, 9_999L) { }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(ids[2], panel.list.selectedValue?.summary?.id)
    }

    fun testRequestPublishesTheTopicWithProjectAndId() {
        val received = mutableListOf<Long>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NoteSelectRequested.TOPIC, NoteSelectRequestedListener { p, id -> if (p === project) received += id })

        NoteSelection.request(project, 42L) { }

        assertEquals(listOf(42L), received)
        project.service<PendingNoteSelection>().take()
    }
}
