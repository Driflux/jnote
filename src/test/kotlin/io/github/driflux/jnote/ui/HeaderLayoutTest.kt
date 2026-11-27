package io.github.driflux.jnote.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.NewNote
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path

class HeaderLayoutTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val dbService get() = DatabaseService.getInstance()
    private val panels = mutableListOf<JNotePanel>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-header").toRealPath()
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

    /** Swing의 validate 한 번처럼 위에서 아래로 한 번만 배치한다. 화면이 없는 테스트에서는 validate가 배치하지 않는다. */
    private fun layOutOnce(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach { layOutOnce(it) }
    }

    private fun resize(panel: JNotePanel, width: Int) {
        panel.setSize(width, 600)
        layOutOnce(panel)
    }

    private fun rowsOf(container: Container): Int =
        1 + container.components.filter { it.isVisible }.zipWithNext().count { (before, after) -> after.x <= before.x }

    private fun assertFitsInItsParent(component: Component) {
        if (!component.isVisible) return
        val parent = component.parent
        assertTrue(
            "${component.javaClass.simpleName} at y=${component.y} h=${component.height} does not fit in a parent of height ${parent.height}",
            component.y >= 0 && component.y + component.height <= parent.height,
        )
        if (component is Container) component.components.forEach { assertFitsInItsParent(it) }
    }

    fun testNarrowingWrapsTheFilterRowWithoutClippingIt() {
        val panel = newPanel()
        awaitUntil("first load") { panel.state.notes != null }
        resize(panel, 2000)
        assertEquals(1, rowsOf(panel.filterRow))

        resize(panel, 160)

        assertTrue("narrow enough to wrap", rowsOf(panel.filterRow) > 1)
        assertFitsInItsParent(panel.filterBar)
    }

    fun testNarrowingWrapsTheTagBarWithoutClippingIt() {
        val repo = NoteRepository(dbService.database)
        val tags = TagRepository(dbService.database)
        val note = repo.create(NewNote.free("tagged")).get()
        listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot").forEach { tags.attach(note.id, tags.getOrCreate(it).get().id).get() }
        val panel = newPanel()
        awaitUntil("chips shown") { panel.tagBar.componentCount == 6 }
        resize(panel, 2000)
        assertEquals(1, rowsOf(panel.tagBar))

        resize(panel, 160)

        assertTrue("narrow enough to wrap", rowsOf(panel.tagBar) > 1)
        assertFitsInItsParent(panel.filterBar)
    }
}
