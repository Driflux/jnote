package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.ai.DecodeResult
import io.github.driflux.jnote.ai.ExportedNote
import io.github.driflux.jnote.ai.NoteFormatCodec
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JMenuItem
import javax.swing.ListSelectionModel

class ToolWindowCopyTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val panels = mutableListOf<JNotePanel>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-copy").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
        CopyPasteManager.getInstance().setContents(StringSelection("before"))
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

    private fun loadedPanel(count: Int): JNotePanel {
        val panel = JNotePanel(project).also {
            panels += it
            Disposer.register(testRootDisposable, it)
        }
        awaitUntil("list loaded") { panel.list.model.size == count }
        return panel
    }

    private fun titles(panel: JNotePanel) = (0 until panel.list.model.size).map { panel.list.model.getElementAt(it).title }

    private fun selectedTitles(panel: JNotePanel) = panel.list.selectedValuesList.map { it.title }

    private fun clipboard(): String? = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)

    private fun menuItem(panel: JNotePanel, key: String): JMenuItem =
        panel.listMenu().components.filterIsInstance<JMenuItem>().single { it.text == JNoteBundle.message(key) }

    private fun copyWith(panel: JNotePanel, key: String) {
        val before = clipboard()
        menuItem(panel, key).doClick(0)
        awaitUntil("clipboard written") { clipboard() != before }
    }

    private fun threeNotes(): JNotePanel {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 2, "first", snapshot = "a\nb")).get()
        repo.create(NewNote.free("second", body = "SELECT 1;")).get()
        repo.create(NewNote.code(key, "src/C.kt", 3, 3, "third")).get()
        return loadedPanel(3)
    }

    fun testListAllowsSelectingSeveralNotes() {
        val panel = threeNotes()

        assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, panel.list.selectionMode)
    }

    fun testCopyAsJsonPutsEverySelectedNoteInListOrder() {
        val panel = threeNotes()
        panel.list.selectedIndices = intArrayOf(0, 2)

        copyWith(panel, "toolWindow.copy.json")

        val decoded = NoteFormatCodec.decode(clipboard()!!) as DecodeResult.Decoded
        assertEquals(listOf("third", "first"), decoded.notes.map { it.title })
        assertEquals(panel.list.selectedValuesList.map { it.summary.uid }, decoded.notes.map { it.uid })
    }

    fun testCopyAsMarkdownCopiesTitleLocationSnippetAndBody() {
        val panel = threeNotes()
        panel.list.selectedIndices = intArrayOf(1, 2)

        copyWith(panel, "toolWindow.copy.markdown")

        assertEquals("## second\n\nSELECT 1;\n\n## first\n\n`src/A.kt:L1-2`\n\n```kt\na\nb\n```", clipboard())
    }

    fun testCopyReadsTheLatestTextFromTheDatabase() {
        val panel = threeNotes()
        panel.list.selectedIndex = 0
        val shown = repo.get(panel.list.selectedValue.summary.id).get()!!
        repo.update(shown.copy(title = "renamed elsewhere")).get()

        copyWith(panel, "toolWindow.copy.markdown")

        assertTrue(clipboard()!!.startsWith("## renamed elsewhere"))
    }

    fun testCopyOfNotesDeletedMeanwhileLeavesTheClipboardAlone() {
        val panel = threeNotes()
        panel.list.selectedIndex = 0
        repo.delete(panel.list.selectedValue.summary.id).get()

        panel.copySelected { "should not be copied" }.get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("before", clipboard())
    }

    fun testCopyItemsAreDisabledWithoutASelection() {
        val panel = threeNotes()
        panel.list.clearSelection()

        assertFalse(menuItem(panel, "toolWindow.copy.markdown").isEnabled)
        assertFalse(menuItem(panel, "toolWindow.copy.json").isEnabled)
    }

    fun testReloadDoesNotSelectANewNoteThatReusedTheIdOfASelectedOne() {
        val panel = threeNotes()
        panel.list.selectedIndex = 0
        val selected = panel.list.selectedValue.summary
        repo.delete(selected.id).get()
        val reused = repo.create(NewNote.free("reused id")).get()
        assertEquals("SQLite gives the next note the deleted highest id", selected.id, reused.id)

        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        awaitUntil("reloaded") { titles(panel).firstOrNull() == "reused id" }

        assertEquals(emptyList<String>(), selectedTitles(panel))
    }

    private fun blockingFormatter(gate: CountDownLatch): (List<ExportedNote>) -> String = { gate.await(10, TimeUnit.SECONDS); "older copy" }

    private fun constantFormatter(text: String): (List<ExportedNote>) -> String = { text }

    fun testALaterCopyIsNotOverwrittenByAnEarlierOneThatFinishesLast() {
        val panel = threeNotes()
        panel.list.selectedIndex = 0
        val gate = CountDownLatch(1)

        val older = panel.copySelected(blockingFormatter(gate))
        panel.copySelected(constantFormatter("newer copy")).get(10, TimeUnit.SECONDS)
        awaitUntil("newer copy written") { clipboard() == "newer copy" }
        gate.countDown()
        older.get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("newer copy", clipboard())
    }

    fun testACopyFromAnotherPanelIsNotOverwrittenByAnEarlierOneThatFinishesLast() {
        val first = threeNotes()
        val second = loadedPanel(3)
        first.list.selectedIndex = 0
        second.list.selectedIndex = 1
        val gate = CountDownLatch(1)

        val older = first.copySelected(blockingFormatter(gate))
        second.copySelected(constantFormatter("newer copy")).get(10, TimeUnit.SECONDS)
        awaitUntil("newer copy written") { clipboard() == "newer copy" }
        gate.countDown()
        older.get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("newer copy", clipboard())
    }

    fun testACopyThatFinishesAfterTheUserCopiedSomethingElseLeavesTheClipboardAlone() {
        val panel = threeNotes()
        panel.list.selectedIndex = 0
        val gate = CountDownLatch(1)

        val copy = panel.copySelected(blockingFormatter(gate))
        CopyPasteManager.getInstance().setContents(StringSelection("copied in the editor"))
        gate.countDown()
        copy.get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("copied in the editor", clipboard())
    }

    fun testACopyThatFinishesAfterThePanelClosedLeavesTheClipboardAlone() {
        val panel = threeNotes()
        panel.list.selectedIndex = 0
        val gate = CountDownLatch(1)

        val copy = panel.copySelected(blockingFormatter(gate))
        Disposer.dispose(panel)
        CopyPasteManager.getInstance().setContents(StringSelection("copied after closing"))
        gate.countDown()
        copy.get(10, TimeUnit.SECONDS)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("copied after closing", clipboard())
    }

    fun testACopyStartedAfterTheUserCopiedIsWritten() {
        val panel = threeNotes()
        panel.list.selectedIndex = 0
        CopyPasteManager.getInstance().setContents(StringSelection("copied in the editor"))

        panel.copySelected(constantFormatter("from JNote")).get(10, TimeUnit.SECONDS)

        awaitUntil("JNote copy written") { clipboard() == "from JNote" }
    }

    fun testCopyFormatInstructionsWorksWithoutASelection() {
        val panel = loadedPanel(0)
        val item = menuItem(panel, "toolWindow.copy.instructions")
        assertTrue(item.isEnabled)

        copyWith(panel, "toolWindow.copy.instructions")

        assertEquals(ApplicationManager.getApplication().executeOnPooledThread<String> { io.github.driflux.jnote.ai.AiInstructions.text() }.get(), clipboard())
    }

    fun testReloadKeepsEverySelectedNoteSelected() {
        val panel = threeNotes()
        panel.list.selectedIndices = intArrayOf(0, 2)
        assertEquals(listOf("third", "first"), selectedTitles(panel))

        repo.create(NewNote.free("newest")).get()
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        awaitUntil("reloaded") { titles(panel).firstOrNull() == "newest" }

        assertEquals(listOf("third", "first"), selectedTitles(panel))
        assertEquals("third", panel.detail.titleLabel.text)
    }
}
