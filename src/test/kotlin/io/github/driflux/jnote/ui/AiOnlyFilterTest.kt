package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
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

/** "AI 노트만" 보기: 켜면 AI 출처가 있는 노트만 보이고, 끄면 모두 돌아온다. */
class AiOnlyFilterTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val panels = mutableListOf<JNotePanel>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-ai").toRealPath()
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

    private fun seed() {
        val key = projectKey()
        repo.create(NewNote.code(key, "src/A.kt", 1, 1, "by hand")).get()
        repo.create(NewNote.code(key, "src/B.kt", 1, 1, "from claude", aiSource = "Claude")).get()
        repo.create(NewNote.free("free from gpt", aiSource = "GPT")).get()
        repo.create(NewNote.free("free by hand")).get()
    }

    fun testOffByDefault() {
        seed()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 4 }
        assertFalse(panel.aiOnlyBox.isSelected)
    }

    fun testOnKeepsOnlyNotesWithAnAiSourceAndOffRestoresAll() {
        seed()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 4 }

        panel.aiOnlyBox.doClick(0)
        assertEquals(setOf("from claude", "free from gpt"), titles(panel).toSet())

        panel.aiOnlyBox.doClick(0)
        assertEquals(4, titles(panel).size)
    }

    fun testCombinesWithTheKindFilter() {
        seed()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 4 }
        panel.aiOnlyBox.doClick(0)

        panel.filterCombo.selectedItem = NoteFilter.FREE

        assertEquals(listOf("free from gpt"), titles(panel))
    }

    fun testSurvivesAReloadAndPicksUpNewAiNotes() {
        seed()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 4 }
        panel.aiOnlyBox.doClick(0)
        repo.create(NewNote.code(projectKey(), "src/C.kt", 1, 1, "imported", aiSource = "Claude")).get()
        repo.create(NewNote.code(projectKey(), "src/D.kt", 1, 1, "typed")).get()

        panel.reload()

        awaitUntil("reloaded within the filter") { titles(panel).toSet() == setOf("imported", "from claude", "free from gpt") }
    }

    fun testEmptyStateSaysThereAreNoAiNotes() {
        repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 1, "by hand")).get()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 1 }

        panel.aiOnlyBox.doClick(0)

        assertTrue(titles(panel).isEmpty())
        assertEquals(JNoteBundle.message("toolWindow.empty.ai.title"), panel.list.emptyText.text)
    }

    fun testAiMessageWinsOverTheTagMessageWhenTheAiFilterIsTheReason() {
        val hand = repo.create(NewNote.code(projectKey(), "src/A.kt", 1, 1, "by hand")).get()
        val tags = io.github.driflux.jnote.db.TagRepository(dbService.database)
        tags.attach(hand.id, tags.getOrCreate("x").get().id).get()
        val panel = newPanel()
        awaitUntil("chip shown") { panel.tagBar.componentCount == 1 }
        (panel.tagBar.getComponent(0) as javax.swing.JToggleButton).doClick(0)
        assertEquals(1, titles(panel).size)

        panel.aiOnlyBox.doClick(0)

        assertEquals(JNoteBundle.message("toolWindow.empty.ai.title"), panel.list.emptyText.text)
    }

    fun testSelectedNoteLeavingTheFilterClearsTheDetail() {
        seed()
        val panel = newPanel()
        awaitUntil("listed") { titles(panel).size == 4 }
        panel.list.selectedIndex = (0 until panel.list.model.size).first { panel.list.model.getElementAt(it).title == "by hand" }
        awaitUntil("detail shown") { panel.detail.isVisible }

        panel.aiOnlyBox.doClick(0)

        assertFalse(panel.detail.isVisible)
    }
}
