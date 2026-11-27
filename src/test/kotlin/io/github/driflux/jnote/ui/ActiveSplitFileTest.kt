package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingConstants

class ActiveSplitFileTest : FileEditorManagerTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val panels = mutableListOf<JNotePanel>()
    private val created = mutableListOf<VirtualFile>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-split").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            try {
                panels.forEach { Disposer.dispose(it) }
                awaitUntil("pending loads drained") { panels.all { it.isIdle } }
                runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
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

    private fun projectFile(name: String): VirtualFile {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, "class X\n")
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!.also { created += it }
    }

    private fun titles(panel: JNotePanel) = (0 until panel.list.model.size).map { panel.list.model.getElementAt(it).title }

    private fun twoSplits(): Pair<EditorWindow, EditorWindow> {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/SplitA.kt", 1, 1, "in A")).get()
        repo.create(NewNote.code(key, "src/SplitB.kt", 1, 1, "in B")).get()
        val manager = manager!!
        manager.openFile(projectFile("SplitA.kt"), true)
        val left = manager.currentWindow!!
        val right = left.split(SwingConstants.VERTICAL, true, projectFile("SplitB.kt"), true)!!
        return left to right
    }

    private fun activate(window: EditorWindow) {
        window.setAsCurrentWindow(true)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun newPanel(): JNotePanel = JNotePanel(project).also {
        panels += it
        Disposer.register(testRootDisposable, it)
    }

    fun testCurrentFileIsTheFileOfTheActiveSplit() {
        val (left, right) = twoSplits()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 2 }
        activate(right)
        activate(left)

        panel.currentFileBox.doClick(0)

        assertEquals(listOf("in A"), titles(panel))
    }

    fun testListFollowsTheActiveSplit() {
        val (left, right) = twoSplits()
        activate(right)
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 2 }
        panel.currentFileBox.doClick(0)

        activate(left)

        awaitUntil("list follows the left split") { titles(panel) == listOf("in A") }
    }
}
