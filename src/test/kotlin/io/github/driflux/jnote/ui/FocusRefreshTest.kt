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

    fun testAnInPanelMoveReportedThroughNoOwnerDoesNotReload() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        panel.onFocusOwnerChanged(outside, panel.list)
        awaitUntil("entry reload done") { panel.reloadsStarted.get() >= 2 }
        settle(panel)
        val before = panel.reloadsStarted.get()

        panel.onFocusOwnerChanged(panel.list, null)
        panel.onFocusOwnerChanged(null, panel.searchField.textEditor)
        settle(panel)

        assertEquals(before, panel.reloadsStarted.get())
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

        panel.onApplicationActivated(null, panel.list)

        awaitUntil("refreshed") { titles(panel) == listOf("seen after activation") }
    }

    fun testReturningToTheIdeBeforeFocusIsRestoredToThePanelReloads() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("seen before focus came back")

        panel.onApplicationActivated(null, focusOwner = null, restoredOwner = panel.list, permanentOwner = null)

        awaitUntil("refreshed") { titles(panel) == listOf("seen before focus came back") }
    }

    fun testReturningToTheIdeWhileTheWindowHasNoFocusOwnerYetUsesThePermanentOwner() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("seen through the permanent owner")

        panel.onApplicationActivated(null, focusOwner = null, restoredOwner = null, permanentOwner = panel.list)

        awaitUntil("refreshed") { titles(panel) == listOf("seen through the permanent owner") }
    }

    fun testTheRestoredOwnerWinsOverThePermanentOne() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        panel.onApplicationActivated(null, focusOwner = null, restoredOwner = outside, permanentOwner = panel.list)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testReturningToTheIdeBeforeFocusIsRestoredToTheEditorDoesNotReload() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        panel.onApplicationActivated(null, focusOwner = null, restoredOwner = outside, permanentOwner = null)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testAFocusOwnerOutsideThePanelWinsOverTheRestoredOne() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        panel.onApplicationActivated(null, focusOwner = outside, restoredOwner = panel.list, permanentOwner = panel.list)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testReturningToTheIdeWithFocusInTheEditorDoesNotReload() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        panel.onApplicationActivated(null, outside)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testActivationOfAnotherIdeFrameIsIgnored() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("unseen")

        panel.onApplicationActivated(JPanel(), panel.list)
        settle(panel)

        assertTrue(titles(panel).isEmpty())
    }

    fun testActivationOfTheFrameThatHoldsThePanelReloads() {
        val panel = newPanel()
        val frame = JPanel().also { it.add(panel) }
        awaitUntil("first load") { panel.state.notes != null }
        writeFromAnotherWindow("in my frame")

        panel.onApplicationActivated(frame, panel.list)

        awaitUntil("refreshed") { titles(panel) == listOf("in my frame") }
    }

    fun testManyFocusEntriesWhileALoadIsRunningCostOneMoreLoad() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        settle(panel)
        val before = panel.reloadsStarted.get()
        val release = java.util.concurrent.CountDownLatch(1)
        dbService.database.submit { release.await(10, java.util.concurrent.TimeUnit.SECONDS) }

        repeat(20) {
            panel.onFocusOwnerChanged(outside, panel.list)
            panel.onFocusOwnerChanged(panel.list, outside)
        }
        val write = repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 1, "latest"))
        release.countDown()
        write.get()

        awaitUntil("latest data shown") { titles(panel) == listOf("latest") }
        settle(panel)
        assertEquals("one load for the first entry and one rerun for the other 39", 2, panel.reloadsStarted.get() - before)
    }

    fun testTagChipsAreKeptWhenNothingAboutTheTagsChanged() {
        val note = repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 1, "tagged")).get()
        val tags = io.github.driflux.jnote.db.TagRepository(dbService.database)
        tags.attach(note.id, tags.getOrCreate("x").get().id).get()
        val panel = newPanel()
        awaitUntil("chip shown") { panel.tagBar.componentCount == 1 }
        val chip = panel.tagBar.getComponent(0)

        panel.onFocusOwnerChanged(outside, panel.list)
        awaitUntil("reloaded") { panel.reloadsStarted.get() >= 2 }
        settle(panel)

        assertSame("the focused chip must not be replaced by a reload", chip, panel.tagBar.getComponent(0))
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
