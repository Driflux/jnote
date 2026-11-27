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

/**
 * 거터 마우스 처리 비용 측정. 시간은 기계마다 달라 단정하지 않고 출력만 하며, JNOTE_PERF=1일 때만 돈다.
 * 실행: JNOTE_PERF=1 ./gradlew test --tests '*GutterHoverCostTest*' -i | grep JNOTE-PERF
 */
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

    fun testHoverCostOnA5000LineFile() {
        if (System.getenv("JNOTE_PERF") != "1") return
        val text = (1..5_000).joinToString("\n") { "    fun f$it(x: Int) = x + $it" }
        val path = Path.of(project.basePath!!, "src", "HoverCost.txt")
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!.also { created += it }
        myFixture.openFileInEditor(file)
        val editor = myFixture.editor as EditorEx
        val lineHeight = editor.lineHeight
        val base = GutterGeometry.of(editor)
        // 아이콘 영역 판정만 고정한다. 좌표 변환과 줄 계산은 실제 코드이다
        val geometry = GutterGeometry(base.lineAt, { true }, base.onAddIcon)
        val listener = GutterAddNoteListener(editor, project, geometry) { _, _ -> }
        fun move(y: Int) = listener.mouseMoved(MouseEvent(editor.gutterComponentEx, MouseEvent.MOUSE_MOVED, 0L, 0, 5, y, 0, false))

        println("JNOTE-PERF 5000 lines, mouseMoved on the same line: " + measure(9, 20_000) { move(2_500 * lineHeight + 1) })
        println("JNOTE-PERF 5000 lines, mouseMoved onto a new line every event (worst case): " + measure(9, 5_000) { move((it % 4_000) * lineHeight + 1) })
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
