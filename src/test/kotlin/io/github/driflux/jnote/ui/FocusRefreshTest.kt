package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.awt.KeyboardFocusManager
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel

class FocusRefreshTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val panels = mutableListOf<JNotePanel>()
    private val outside = JPanel()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-focus").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

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

    private fun newPanel(): JNotePanel = JNotePanel(project).also {
        panels += it
        Disposer.register(testRootDisposable, it)
    }

    private fun titles(panel: JNotePanel) = (0 until panel.list.model.size).map { panel.list.model.getElementAt(it).title }

    private fun writeFromAnotherWindow(title: String) {
        repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 1, title)).get()
    }

    private fun settle(panel: JNotePanel) {
        awaitUntil("idle") { panel.isIdle }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testFocusMovingIntoThePanelFromOutsideReadsWhatOtherWindowsWrote() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("from elsewhere")

        panel.onFocusOwnerChanged(outside, panel.list)

        awaitUntil("refreshed") { titles(panel) == listOf("from elsewhere") }
    }

    fun testFocusMovingWithinThePanelDoesNotReload() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        repeat(5) {
            panel.onFocusOwnerChanged(panel.list, panel.searchField.textEditor)
            panel.onFocusOwnerChanged(panel.searchField.textEditor, panel.list)
        }
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testFocusLeavingThePanelDoesNotReload() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        panel.onFocusOwnerChanged(panel.list, outside)
        panel.onFocusOwnerChanged(null, null)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testReturningToTheIdeWithFocusStillInThePanelReloads() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("seen after activation")

        panel.onApplicationActivated(panel.list)

        awaitUntil("refreshed") { titles(panel) == listOf("seen after activation") }
    }

    fun testReturningToTheIdeWithFocusInTheEditorDoesNotReload() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        panel.onApplicationActivated(outside)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testNothingReloadsOnItsOwnWhileFocusIsInsideThePanel() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        Thread.sleep(400)
        settle(panel)

        assertTrue("no polling: the list does not change by itself", titles(panel).isEmpty())
    }

    fun testClosedPanelDropsItsFocusListener() {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val before = manager.getPropertyChangeListeners(JNotePanel.FOCUS_OWNER_PROPERTY).size
        val panel = newPanel()
        assertEquals(before + 1, manager.getPropertyChangeListeners(JNotePanel.FOCUS_OWNER_PROPERTY).size)

        Disposer.dispose(panel)

        assertEquals(before, manager.getPropertyChangeListeners(JNotePanel.FOCUS_OWNER_PROPERTY).size)
    }

    fun testClosedPanelIgnoresFocus() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        Disposer.dispose(panel)
        writeFromAnotherWindow("unseen")

        panel.onFocusOwnerChanged(outside, panel.list)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }
}
