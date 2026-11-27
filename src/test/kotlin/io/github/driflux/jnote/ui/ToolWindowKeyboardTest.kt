package io.github.driflux.jnote.ui

import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.project.ProjectKeyService
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.KeyStroke

/** 스니펫 복사와 키보드 조작(검색창으로 이동, Enter로 코드 이동, Delete로 삭제). */
class ToolWindowKeyboardTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val opened = mutableListOf<Note>()
    private val panels = mutableListOf<JNotePanel>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-keys").toRealPath()
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

    private fun recordGoTo(target: Project, note: Note) {
        check(target === project)
        opened += note
    }

    private fun newPanel(): JNotePanel = JNotePanel(project, AppDelayScheduler, goToCode = ::recordGoTo).also {
        panels += it
        Disposer.register(testRootDisposable, it)
    }

    private fun showNote(panel: JNotePanel, title: String) {
        awaitUntil("listed") { (0 until panel.list.model.size).any { panel.list.model.getElementAt(it).title == title } }
        panel.list.selectedIndex = (0 until panel.list.model.size).first { panel.list.model.getElementAt(it).title == title }
        awaitUntil("detail shown") { panel.detail.isVisible && panel.detail.titleLabel.text == title }
    }

    // 실제 키 바인딩을 거친다: 입력 맵의 키 이름으로 액션 맵의 동작을 찾아 실행한다
    private fun press(panel: JNotePanel, keyCode: Int) {
        val name = panel.list.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(keyCode, 0))
        assertNotNull("no binding for key $keyCode", name)
        panel.list.actionMap.get(name).actionPerformed(ActionEvent(panel.list, ActionEvent.ACTION_PERFORMED, ""))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun clipboard(): String? = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)

    fun testCopySnippetPutsTheSnapshotOnTheClipboard() {
        repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 2, "with snippet", snapshot = "val a = 1\nval b = 2")).get()
        val panel = newPanel()
        showNote(panel, "with snippet")
        assertTrue(panel.detail.copySnippetButton.isVisible)

        panel.detail.copySnippetButton.doClick(0)

        assertEquals("val a = 1\nval b = 2", clipboard())
    }

    fun testCopySnippetIsHiddenWhenTheNoteHasNoSnippet() {
        repo.create(NewNote.free("free")).get()
        val panel = newPanel()
        showNote(panel, "free")
        assertFalse(panel.detail.copySnippetButton.isVisible)
    }

    fun testFindShortcutFocusesAndSelectsTheSearchText() {
        val panel = newPanel()
        panel.searchField.text = "abc"
        assertTrue(panel.focusSearchAction.shortcutSet.shortcuts.toList() == CommonShortcuts.getFind().shortcuts.toList())

        panel.focusSearchAction.actionPerformed(TestActionEvent.createTestEvent(panel.focusSearchAction))

        assertEquals("abc", panel.searchField.textEditor.selectedText)
    }

    fun testEnterOnAListedCodeNoteGoesToItsCode() {
        repo.create(NewNote.code(projectKey(), "src/A.kt", 3, 4, "code note")).get()
        val panel = newPanel()
        showNote(panel, "code note")

        press(panel, KeyEvent.VK_ENTER)

        assertEquals(listOf("code note"), opened.map { it.title })
    }

    fun testEnterOnAFreeNoteDoesNothing() {
        repo.create(NewNote.free("free")).get()
        val panel = newPanel()
        showNote(panel, "free")

        press(panel, KeyEvent.VK_ENTER)

        assertTrue(opened.isEmpty())
    }

    fun testKeysDoNothingWhileTheNewSelectionIsStillLoading() {
        repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 1, "a")).get()
        val b = repo.create(NewNote.code(projectKey(), "src/B.kt", 1, 1, "b")).get()
        val panel = newPanel()
        showNote(panel, "a")
        val release = java.util.concurrent.CountDownLatch(1)
        dbService.database.submit { release.await(10, java.util.concurrent.TimeUnit.SECONDS) }

        panel.list.selectedIndex = (0 until panel.list.model.size).first { panel.list.model.getElementAt(it).summary.id == b.id }
        press(panel, KeyEvent.VK_ENTER)
        press(panel, KeyEvent.VK_DELETE)
        release.countDown()

        assertTrue("the old note's detail must not act", opened.isEmpty())
        awaitUntil("b shown") { panel.detail.isVisible && panel.detail.titleLabel.text == "b" }
        assertEquals(2, repo.list(projectKey(), false).get().size)
    }

    fun testDeleteRemovesTheSelectedNoteAndTheListFollows() {
        repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 1, "keep")).get()
        val gone = repo.create(NewNote.code(projectKey(), "src/B.kt", 1, 1, "gone")).get()
        val panel = newPanel()
        showNote(panel, "gone")

        press(panel, KeyEvent.VK_DELETE)

        awaitUntil("note deleted") { repo.get(gone.id).get() == null }
        awaitUntil("list refreshed") { (0 until panel.list.model.size).map { panel.list.model.getElementAt(it).title } == listOf("keep") }
    }
}
