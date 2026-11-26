package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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

    private fun newPanel(): JNotePanel = JNotePanel(project).also { com.intellij.openapi.util.Disposer.register(testRootDisposable, it) }

    fun testToolWindowIsRegisteredOnTheRightWithAFactory() {
        val ep = ToolWindowEP.EP_NAME.extensionList.single { it.id == "JNote" }
        assertEquals(ToolWindowAnchor.RIGHT.toString(), ep.anchor)
        assertEquals(JNoteToolWindowFactory::class.java.name, ep.factoryClass)
        assertTrue(ep.isDoNotActivateOnStart)
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
        com.intellij.openapi.util.Disposer.dispose(panel)
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "late")).get()
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0, panel.list.model.size)
    }
}
