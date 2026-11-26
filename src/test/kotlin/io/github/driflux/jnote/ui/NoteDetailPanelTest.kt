package io.github.driflux.jnote.ui

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
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

class NoteDetailPanelTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-detail").toRealPath()
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

    private fun newPanel(expected: Int): JNotePanel {
        val panel = JNotePanel(project)
        Disposer.register(testRootDisposable, panel)
        awaitUntil("list loaded") { panel.list.model.size == expected }
        return panel
    }

    fun testSelectingANoteShowsItsDetail() {
        val key = projectKey()
        repo.create(
            NewNote.code(
                key, "src/Foo.kt", 3, 5, "why locked", body = "because", className = "Foo", snapshot = "lock()",
                aiSource = "Claude", prompt = "explain",
            ),
        ).get()
        val panel = newPanel(1)
        assertFalse(panel.detail.isVisible)

        panel.list.selectedIndex = 0
        val d = panel.detail
        assertTrue(d.isVisible)
        assertEquals("why locked", d.titleLabel.text)
        assertEquals("Foo", d.classLabel.text)
        assertEquals("src/Foo.kt:L3-5", d.locationLabel.text)
        assertEquals("lock()", d.snippetArea.text)
        assertEquals("because", d.bodyArea.text)
        assertEquals("Claude", d.aiSourceLabel.text)
        assertEquals("explain", d.aiPromptArea.text)
        assertTrue(d.goToCodeButton.isVisible)
    }

    fun testFreeNoteHidesGoToCodeAndUsesMonospaceBody() {
        repo.create(NewNote.free("query", "select 1")).get()
        val panel = newPanel(1)
        panel.list.selectedIndex = 0
        assertFalse(panel.detail.goToCodeButton.isVisible)
        assertEquals("select 1", panel.detail.bodyArea.text)
        assertFalse(panel.detail.locationLabel.isVisible)
    }

    fun testGoToCodeOpensTheFileAtTheStartLine() {
        val key = projectKey()
        myFixture.addFileToProject("src/Foo.kt", "line1\nline2\nline3\nline4\n")
        val note = repo.create(NewNote.code(key, "src/Foo.kt", 3, 4, "n")).get()

        val opened = PlatformTestUtil.waitForFuture(CodeNavigator.goTo(project, note), 10_000)

        assertTrue(opened)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("Foo.kt", editor.virtualFile.name)
        assertEquals(2, editor.caretModel.logicalPosition.line)
    }

    fun testGoToCodeWithMissingFileNotifiesAndOpensNothing() {
        val key = projectKey()
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        val note = repo.create(NewNote.code(key, "src/Gone.kt", 1, 1, "n")).get()

        val opened = PlatformTestUtil.waitForFuture(CodeNavigator.goTo(project, note), 10_000)

        assertFalse(opened)
        assertTrue(shown.any { it.content.contains("src/Gone.kt") })
        assertNull(FileEditorManager.getInstance(project).selectedTextEditor)
    }

    fun testGoToCodeRejectsPathsOutsideTheProject() {
        val key = projectKey()
        val note = repo.create(NewNote.code(key, "../outside.kt", 1, 1, "n")).get()
        assertFalse(PlatformTestUtil.waitForFuture(CodeNavigator.goTo(project, note), 10_000))
    }

    fun testFreeNoteHasNoTarget() {
        val note = repo.create(NewNote.free("q")).get()
        assertFalse(CodeNavigator.goTo(project, note).get())
    }
}
