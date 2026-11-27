package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path

/** "현재 파일" 보기: 켜면 편집기에서 선택한 파일의 노트만 보이고, 파일을 바꾸면 따라간다. */
class CurrentFileFilterTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val panels = mutableListOf<JNotePanel>()
    private val created = mutableListOf<VirtualFile>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-curfile").toRealPath()
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

    private fun openFile(name: String): VirtualFile {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, "class X\n")
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += vFile
        myFixture.openFileInEditor(vFile)
        return vFile
    }

    private fun newPanel(): JNotePanel = JNotePanel(project).also {
        panels += it
        Disposer.register(testRootDisposable, it)
    }

    private fun titles(panel: JNotePanel) = (0 until panel.list.model.size).map { panel.list.model.getElementAt(it).title }

    private fun toggle(panel: JNotePanel) = panel.currentFileBox.doClick(0)

    private fun seed() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/CurA.kt", 1, 2, "in A")).get()
        repo.create(NewNote.code(key, "src/CurB.kt", 1, 2, "in B")).get()
        repo.create(NewNote.code("other/project", "src/CurA.kt", 1, 2, "other project A")).get()
        repo.create(NewNote.free("free")).get()
    }

    fun testOffByDefaultShowsEveryNoteOfTheProjectAndFreeNotes() {
        seed()
        openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }
        assertFalse(panel.currentFileBox.isSelected)
    }

    fun testTurningItOnKeepsOnlyTheNotesOfTheOpenFile() {
        seed()
        openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }

        toggle(panel)

        assertEquals(listOf("in A"), titles(panel))
    }

    fun testTurningItOffRestoresTheList() {
        seed()
        openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }
        toggle(panel)

        toggle(panel)

        assertEquals(3, titles(panel).size)
    }

    fun testListFollowsTheSelectedEditorFileWhileOn() {
        seed()
        openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }
        toggle(panel)

        openFile("CurB.kt")

        assertEquals(listOf("in B"), titles(panel))
    }

    fun testClosingEveryFileLeavesNothingAndShowsTheGuidance() {
        seed()
        val file = openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }
        toggle(panel)

        FileEditorManager.getInstance(project).closeFile(file)

        assertTrue(titles(panel).isEmpty())
        assertEquals(JNoteBundle.message("toolWindow.empty.currentFile.none.title"), panel.list.emptyText.text)
    }

    fun testNoFileMessageWinsOverAnActiveTagFilter() {
        seed()
        val tagged = repo.create(NewNote.code(projectKey(), "src/CurC.kt", 1, 1, "tagged")).get()
        val tags = TagRepository(dbService.database)
        tags.attach(tagged.id, tags.getOrCreate("x").get().id).get()
        val file = openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed with the tag chip") { titles(panel).size == 4 && panel.tagBar.componentCount == 1 }
        (panel.tagBar.getComponent(0) as javax.swing.JToggleButton).doClick(0)
        toggle(panel)

        FileEditorManager.getInstance(project).closeFile(file)

        assertEquals(JNoteBundle.message("toolWindow.empty.currentFile.none.title"), panel.list.emptyText.text)
    }

    fun testOpenFileWithoutNotesShowsTheNoNotesInFileMessage() {
        seed()
        openFile("CurEmpty.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }

        toggle(panel)

        assertEquals(JNoteBundle.message("toolWindow.empty.currentFile.title"), panel.list.emptyText.text)
    }

    fun testRenamingTheOpenFileIsFollowedOnTheNextReload() {
        seed()
        val file = openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }
        toggle(panel)
        runWriteAction { file.rename(this, "CurRenamed.kt") }
        repo.create(NewNote.code(projectKey(), "src/CurRenamed.kt", 1, 1, "after rename")).get()

        panel.reload()

        awaitUntil("renamed file's notes shown") { titles(panel) == listOf("after rename") }
    }

    fun testFilterSurvivesAReload() {
        seed()
        openFile("CurA.kt")
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 3 }
        toggle(panel)
        repo.create(NewNote.code(projectKey(), "src/CurA.kt", 5, 6, "second in A")).get()

        panel.reload()

        awaitUntil("reloaded within the file") { titles(panel) == listOf("second in A", "in A") }
    }
}
