package io.github.driflux.jnote.ui

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class RelocateButtonTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val panels = mutableListOf<JNotePanel>()
    private val created = mutableListOf<VirtualFile>()
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-relocate-button").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification
                }
            },
        )
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

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)
    }

    private fun projectKey(): String =
        ApplicationManager.getApplication().executeOnPooledThread<String> { project.service<ProjectKeyService>().resolve().key }.get()

    private fun panelShowing(notes: Int): JNotePanel {
        val panel = JNotePanel(project).also { panels += it }
        awaitUntil("list loaded") { panel.list.model.size == notes }
        return panel
    }

    fun testShownOnlyForCodeNotes() {
        repo.create(NewNote.code(projectKey(), "src/R.kt", 1, 1, "code")).get()
        repo.create(NewNote.free("free")).get()
        val panel = panelShowing(2)
        for (index in 0 until 2) {
            panel.list.selectedIndex = index
            val note = panel.list.selectedValue.note
            assertEquals(note.title, note.relPath != null, panel.detail.relocateButton.isVisible)
        }
    }

    fun testHiddenForNotesOfAnotherProject() {
        repo.create(NewNote.code("other/repo", "src/R.kt", 1, 1, "elsewhere")).get()
        val panel = panelShowing(0)
        panel.allProjectsBox.doClick()
        awaitUntil("all projects loaded") { panel.list.model.size == 1 }
        panel.list.selectedIndex = 0
        assertTrue(panel.detail.goToCodeButton.isVisible)
        assertFalse(panel.detail.relocateButton.isVisible)
    }

    fun testRelocatesTheSelectedNoteInItsFile() {
        val path = Path.of(project.basePath!!, "src", "R.kt")
        Files.createDirectories(path.parent)
        Files.writeString(path, "// pushed down\n// again\nfun moved() = 42\n")
        created += LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val note = repo.create(NewNote.code(projectKey(), "src/R.kt", 1, 1, "moved", snapshot = "fun moved() = 42")).get()
        val panel = panelShowing(1)
        panel.list.selectedIndex = 0

        panel.detail.relocateButton.doClick()
        awaitUntil("no notification") { notifications.isNotEmpty() }

        assertEquals(JNoteBundle.message("notification.relocate.done.content", 1, 0, 0), notifications.single().content)
        assertEquals(3, repo.get(note.id).get()?.startLine)
    }
}
