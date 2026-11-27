package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path

class SnippetViewerTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val panels = mutableListOf<JNotePanel>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-snippet").toRealPath()
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

    private fun select(panel: JNotePanel, title: String) {
        awaitUntil("listed") { (0 until panel.list.model.size).any { panel.list.model.getElementAt(it).title == title } }
        panel.list.selectedIndex = (0 until panel.list.model.size).first { panel.list.model.getElementAt(it).title == title }
        awaitUntil("detail shown") { panel.detail.isVisible && panel.detail.titleLabel.text == title }
    }

    private fun tokenAt(panel: JNotePanel, offset: Int) = panel.detail.snippetViewer.viewer!!.let { (it as com.intellij.openapi.editor.ex.EditorEx).highlighter.createIterator(offset).tokenType.toString() }

    fun testNoEditorExistsUntilANoteWithASnippetIsSelected() {
        repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 1, "xml", snapshot = "<a b='c'/>")).get()
        val before = EditorFactory.getInstance().allEditors.size
        val panel = newPanel()
        awaitUntil("listed") { panel.list.model.size == 1 }
        assertNull(panel.detail.snippetViewer.viewer)
        assertEquals(before, EditorFactory.getInstance().allEditors.size)

        select(panel, "xml")

        assertNotNull(panel.detail.snippetViewer.viewer)
        assertEquals(before + 1, EditorFactory.getInstance().allEditors.size)
    }

    fun testSnippetIsShownReadOnlyWithTheHighlighterOfTheFileType() {
        repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 1, "xml", snapshot = "<a b='c'/>")).get()
        repo.create(NewNote.code(projectKey(), "src/a.txt", 1, 1, "txt", snapshot = "<a b='c'/>")).get()
        val panel = newPanel()

        select(panel, "xml")
        val editor = panel.detail.snippetViewer.viewer!!
        assertEquals("<a b='c'/>", editor.document.text)
        assertTrue(editor.isViewer)
        val xmlToken = tokenAt(panel, 0)
        assertTrue("xml tokens expected but was $xmlToken", xmlToken.contains("XML"))

        select(panel, "txt")
        assertFalse("plain text is not colored as xml", tokenAt(panel, 0).contains("XML"))
    }

    fun testWindowsLineEndingsInTheSnapshotAreShownAndCopiedAsStored() {
        val note = repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 2, "crlf", snapshot = "<a>\r\n</a>")).get()
        val panel = newPanel()

        select(panel, "crlf")

        assertEquals("<a>\n</a>", panel.detail.snippetViewer.viewer!!.document.text)
        panel.detail.copySnippetButton.doClick(0)
        assertEquals(note.snapshot, com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor))
    }

    fun testALongSingleLineStillGetsRoomForItsScrollbar() {
        repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 1, "wide", snapshot = "<a>" + "x".repeat(500) + "</a>")).get()
        val panel = newPanel()

        select(panel, "wide")

        val editor = panel.detail.snippetViewer.viewer as com.intellij.openapi.editor.ex.EditorEx
        assertTrue(editor.component.preferredSize.height >= editor.lineHeight + editor.scrollPane.horizontalScrollBar.preferredSize.height)
    }

    fun testSelectingANoteWithoutASnippetReleasesTheEditor() {
        repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 1, "xml", snapshot = "<a/>")).get()
        repo.create(NewNote.free("free")).get()
        val before = EditorFactory.getInstance().allEditors.size
        val panel = newPanel()
        select(panel, "xml")

        select(panel, "free")

        assertNull(panel.detail.snippetViewer.viewer)
        assertEquals(before, EditorFactory.getInstance().allEditors.size)
    }

    fun testSelectingAnotherSnippetReplacesTheEditorWithoutLeakingTheOldOne() {
        repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 1, "one", snapshot = "<one/>")).get()
        repo.create(NewNote.code(projectKey(), "src/b.xml", 1, 1, "two", snapshot = "<two/>")).get()
        val before = EditorFactory.getInstance().allEditors.size
        val panel = newPanel()
        select(panel, "one")
        val first = panel.detail.snippetViewer.viewer

        select(panel, "two")

        assertNotSame(first, panel.detail.snippetViewer.viewer)
        assertTrue(first!!.isDisposed)
        assertEquals(before + 1, EditorFactory.getInstance().allEditors.size)
    }

    fun testReloadingTheSameNoteKeepsTheEditor() {
        val note = repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 1, "one", snapshot = "<one/>")).get()
        val panel = newPanel()
        select(panel, "one")
        val editor = panel.detail.snippetViewer.viewer

        repo.update(note.copy(title = "renamed")).get()
        panel.reload()
        awaitUntil("new title shown by the reload") { panel.detail.titleLabel.text == "renamed" }

        assertSame(editor, panel.detail.snippetViewer.viewer)
    }

    fun testClosingThePanelReleasesTheEditor() {
        repo.create(NewNote.code(projectKey(), "src/a.xml", 1, 1, "one", snapshot = "<one/>")).get()
        val before = EditorFactory.getInstance().allEditors.size
        val panel = newPanel()
        select(panel, "one")
        val editor = panel.detail.snippetViewer.viewer!!

        Disposer.dispose(panel)

        assertTrue(editor.isDisposed)
        assertEquals(before, EditorFactory.getInstance().allEditors.size)
    }
    fun testCostOfSelectingALargeSnippetIsPrinted() {
        if (System.getenv("JNOTE_PERF") != "1") return
        val code = (1..2000).joinToString("\n") { "    <item id='$it' name='value $it'/>" }.take(65_536)
        val times = List(15) {
            val viewer = SnippetViewer(project)
            val start = System.nanoTime()
            viewer.show(code, "big.xml")
            val ms = (System.nanoTime() - start) / 1_000_000.0
            viewer.release()
            ms
        }
        println("JNOTE-PERF snippetChars=${code.length} createViewerMs(first,median)=${"%.1f".format(times.first())},${"%.1f".format(times.sorted()[times.size / 2])}")
    }
}
