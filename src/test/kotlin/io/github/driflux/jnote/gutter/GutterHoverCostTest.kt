package io.github.driflux.jnote.gutter

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path

class GutterHoverCostTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()

    override fun tearDown() {
        try {
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
        } finally {
            super.tearDown()
        }
    }

    private fun measure(rounds: Int, repeats: Int, block: (Int) -> Unit): String {
        repeat(repeats) { block(it) }
        val perRound = (1..rounds).map {
            val start = System.nanoTime()
            repeat(repeats) { block(it) }
            (System.nanoTime() - start) / 1e3 / repeats
        }.sorted()
        return "median %.3f us, min %.3f, max %.3f (%d rounds x %d)".format(perRound[rounds / 2], perRound.first(), perRound.last(), rounds, repeats)
    }

    fun testPreviewLookupCostOnA5000LineFileWith20Notes() {
        if (System.getenv("JNOTE_PERF") == "1") previewCost()
    }

    private fun previewCost() {
        val dir = Files.createTempDirectory("jnote-hover-cost").toRealPath()
        val databases = io.github.driflux.jnote.db.DatabaseService.getInstance()
        databases.switchTo(dir.resolve("jnote.db"))
        try {
            val text = (1..5_000).joinToString("\n") { "    fun f$it(x: Int) = x + $it" }
            val path = Path.of(project.basePath!!, "src", "PreviewCost.txt")
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!.also { created += it }
            myFixture.openFileInEditor(file)
            val editor = myFixture.editor as EditorEx
            val key = com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread<String> { project.getService(io.github.driflux.jnote.project.ProjectKeyService::class.java).resolve().key }.get()
            val repo = io.github.driflux.jnote.db.NoteRepository(databases.database)
            repeat(20) { repo.create(io.github.driflux.jnote.model.NewNote.code(key, "src/PreviewCost.txt", 100 + it * 240, 103 + it * 240, "note $it", snapshot = "a\nb\nc\nd\ne")).get() }
            val service = project.getService(GutterNoteService::class.java)
            val deadline = System.currentTimeMillis() + 10_000
            while (service.notesFor(file, "src/PreviewCost.txt") == null) {
                check(System.currentTimeMillis() < deadline) { "cache not loaded" }
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(10)
            }
            val document = editor.document
            service.notesCovering(document, 0)
            println("JNOTE-PERF preview lookup (notesCovering) per line change, 5000 lines, 20 notes: " + measure(9, 20_000) { service.notesCovering(document, (it % 4_900)) })
            val controller = GutterPreviewController(
                io.github.driflux.jnote.ui.DelayScheduler { _, _ -> io.github.driflux.jnote.ui.Cancellable { } },
                { service.notesCovering(document, it) },
                { _, _, _, _ -> null },
            )
            println("JNOTE-PERF preview hover() per line change incl. scheduling, 5000 lines, 20 notes: " + measure(9, 20_000) { controller.hover(it % 4_900, 5) })
            controller.dispose()
        } finally {
            databases.switchTo(io.github.driflux.jnote.db.DatabasePaths.default())
            dir.toFile().deleteRecursively()
        }
    }

    fun testHoverCostOnA5000LineFile() {
        if (System.getenv("JNOTE_PERF") == "1") hoverCost()
    }

    private fun hoverCost() {
        val text = (1..5_000).joinToString("\n") { "    fun f$it(x: Int) = x + $it" }
        val path = Path.of(project.basePath!!, "src", "HoverCost.txt")
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!.also { created += it }
        myFixture.openFileInEditor(file)
        val editor = myFixture.editor as EditorEx
        val lineHeight = editor.lineHeight
        val base = GutterGeometry.of(editor)
        val geometry = GutterGeometry(base.lineAt, { true }, base.onAddIcon)
        val listener = GutterAddNoteListener(editor, project, geometry) { _, _ -> }
        fun move(y: Int) = listener.mouseMoved(MouseEvent(editor.gutterComponentEx, MouseEvent.MOUSE_MOVED, 0L, 0, 5, y, 0, false))

        println("JNOTE-PERF 5000 lines, mouseMoved on the same line: " + measure(9, 20_000) { move(2_500 * lineHeight + 1) })
        println("JNOTE-PERF 5000 lines, mouseMoved onto a new line every event (worst case): " + measure(9, 5_000) { move((it % 4_000) * lineHeight + 1) })
        repeat(500) { editor.markupModel.addRangeHighlighter(null, it * 100, it * 100 + 5, 0, com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE) }
        println("JNOTE-PERF 5000 lines + 500 other highlighters, new line every event incl. gutter update on the EDT queue: " + measure(9, 500) {
            move((it % 4_000) * lineHeight + 1)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            editor.gutterComponentEx.preferredSize
        })
        val outside = GutterAddNoteListener(editor, project, GutterGeometry(base.lineAt, { false }, base.onAddIcon)) { _, _ -> }
        println("JNOTE-PERF 5000 lines, mouseMoved outside the icon area: " + measure(9, 20_000) {
            outside.mouseMoved(MouseEvent(editor.gutterComponentEx, MouseEvent.MOUSE_MOVED, 0L, 0, 50, 10, 0, false))
        })
        listener.dispose()

        val parent: Disposable = Disposer.newDisposable()
        try {
            GutterAddNoteAttacher.attach(project, editor.document, parent)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            println("JNOTE-PERF attach lookup per marker pass (editor already attached): " + measure(9, 20_000) {
                GutterAddNoteAttacher.attach(project, editor.document, parent)
            })
        } finally {
            Disposer.dispose(parent)
        }
    }
}