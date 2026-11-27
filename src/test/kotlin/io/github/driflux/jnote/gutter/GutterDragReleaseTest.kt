package io.github.driflux.jnote.gutter

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.anchor.AnchorDraft
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

// 실제 거터에 마우스 이벤트를 보내 플랫폼의 release 처리(아이콘 클릭 액션 실행)까지 함께 돌린다.
class GutterDragReleaseTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()
    private val added = mutableListOf<AnchorDraft>()
    private val ran = mutableListOf<String>()

    override fun tearDown() {
        try {
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
        } finally {
            super.tearDown()
        }
    }

    private fun openFile(name: String): EditorEx {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, (1..10).joinToString("\n") { "line $it" })
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += file
        myFixture.openFileInEditor(file)
        return myFixture.editor as EditorEx
    }

    private fun install(editor: EditorEx): Disposable {
        val gutter = editor.gutterComponentEx
        val listener = GutterAddNoteListener(editor, project, GutterGeometry.of(editor)) { _, draft -> added += draft }
        gutter.addMouseListener(listener)
        gutter.addMouseMotionListener(listener)
        gutter.setInitialIconAreaWidth(GutterGeometry.ICON_COLUMN_WIDTH)
        val installed = Disposer.newDisposable()
        Disposer.register(installed) {
            gutter.removeMouseListener(listener)
            gutter.removeMouseMotionListener(listener)
            listener.dispose()
        }
        Disposer.register(testRootDisposable, installed)
        return installed
    }

    private fun icon(editor: EditorEx, line: Int, name: String, alignment: GutterIconRenderer.Alignment = GutterIconRenderer.Alignment.LEFT): GutterIconRenderer {
        // 인덱싱 중에도 거터가 아이콘을 건너뛰지 않도록 DumbAware로 만든다.
        val renderer = object : GutterIconRenderer(), DumbAware {
            override fun getIcon(): Icon = AllIcons.Actions.Execute

            override fun getAlignment(): Alignment = alignment

            override fun getClickAction(): AnAction = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    ran += name
                }
            }

            override fun equals(other: Any?): Boolean = this === other

            override fun hashCode(): Int = System.identityHashCode(this)
        }
        editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.LAST).gutterIconRenderer = renderer
        return renderer
    }

    private fun centerOf(editor: EditorEx, renderer: GutterIconRenderer): Point {
        editor.gutterComponentEx.revalidateMarkup()
        return editor.gutterComponentEx.getCenterPoint(renderer)!!
    }

    private fun showPlus(editor: EditorEx, line: Int): Point {
        val gutter = editor.gutterComponentEx
        val y = editor.logicalPositionToXY(LogicalPosition(line, 0)).y + editor.lineHeight / 2
        send(editor, MouseEvent.MOUSE_MOVED, Point(gutter.iconAreaOffset + 1, y))
        val plus = editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer as? AddNoteGutterRenderer }.single()
        return centerOf(editor, plus)
    }

    private fun send(
        editor: EditorEx,
        id: Int,
        at: Point,
        button: Int = if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1,
        held: Int = if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED) InputEvent.BUTTON1_DOWN_MASK else 0,
    ): MouseEvent {
        val gutter = editor.gutterComponentEx
        return MouseEvent(gutter, id, 0L, held, at.x, at.y, 1, false, button).also { gutter.dispatchEvent(it) }
    }

    private fun click(editor: EditorEx, at: Point) {
        send(editor, MouseEvent.MOUSE_PRESSED, at)
        send(editor, MouseEvent.MOUSE_RELEASED, at)
    }

    private fun dragAndRelease(editor: EditorEx, from: Point, to: Point) {
        send(editor, MouseEvent.MOUSE_PRESSED, from)
        send(editor, MouseEvent.MOUSE_DRAGGED, to)
        send(editor, MouseEvent.MOUSE_RELEASED, to)
    }

    fun testARangeReleasedOverAnotherIconAddsTheRangeWithoutRunningThatIcon() {
        val editor = openFile("ReleaseOverRun.txt")
        install(editor)
        val run = icon(editor, 6, "run")
        val plus = showPlus(editor, 2)

        dragAndRelease(editor, plus, centerOf(editor, run))

        assertEquals(emptyList<String>(), ran)
        assertEquals(3 to 7, added.single().let { it.startLine to it.endLine })
    }

    fun testAReleaseOnTheStartLineOverAnotherIconRunsNothing() {
        val editor = openFile("ReleaseSameLine.txt")
        install(editor)
        val other = icon(editor, 2, "other", GutterIconRenderer.Alignment.RIGHT)
        val plus = showPlus(editor, 2)

        dragAndRelease(editor, plus, centerOf(editor, other))

        assertEquals(emptyList<String>(), ran)
        assertTrue(added.isEmpty())
    }

    fun testClickingThePlusStillAddsANoteForItsLine() {
        val editor = openFile("ReleaseClick.txt")
        install(editor)
        val plus = showPlus(editor, 4)

        click(editor, plus)

        assertEquals(5 to 5, added.single().let { it.startLine to it.endLine })
    }

    fun testAnotherIconRunsAgainOnceTheDragIsOver() {
        val editor = openFile("ReleaseAfter.txt")
        install(editor)
        val run = icon(editor, 6, "run")
        val plus = showPlus(editor, 2)
        dragAndRelease(editor, plus, centerOf(editor, run))

        click(editor, centerOf(editor, run))

        assertEquals(listOf("run"), ran)
    }

    fun testAnotherIconRunsAgainWhenTheListenerIsRemovedMidDrag() {
        val editor = openFile("ReleaseDisposed.txt")
        val installed = install(editor)
        val run = icon(editor, 6, "run")
        val plus = showPlus(editor, 2)
        send(editor, MouseEvent.MOUSE_PRESSED, plus)

        Disposer.dispose(installed)
        click(editor, centerOf(editor, run))

        assertEquals(listOf("run"), ran)
    }

    fun testAClickOnAnotherIconAfterALostReleaseRunsOnlyThatIcon() {
        val editor = openFile("ReleaseLost.txt")
        install(editor)
        val run = icon(editor, 6, "run")
        val plus = showPlus(editor, 2)
        send(editor, MouseEvent.MOUSE_PRESSED, plus)
        send(editor, MouseEvent.MOUSE_DRAGGED, centerOf(editor, run))

        click(editor, centerOf(editor, run))

        assertEquals(listOf("run"), ran)
        assertTrue(added.isEmpty())
    }

    fun testARightClickDuringADragNeitherEndsItNorLetsTheLeftReleaseRunAnIcon() {
        val editor = openFile("ReleaseRight.txt")
        install(editor)
        val run = icon(editor, 6, "run")
        val plus = showPlus(editor, 2)
        val at = centerOf(editor, run)
        send(editor, MouseEvent.MOUSE_PRESSED, plus)
        send(editor, MouseEvent.MOUSE_DRAGGED, at)

        send(editor, MouseEvent.MOUSE_PRESSED, at, MouseEvent.BUTTON3, InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK)
        val rightRelease = send(editor, MouseEvent.MOUSE_RELEASED, at, MouseEvent.BUTTON3, InputEvent.BUTTON1_DOWN_MASK)
        assertFalse(rightRelease.isConsumed)
        assertTrue(added.isEmpty())
        send(editor, MouseEvent.MOUSE_RELEASED, at)

        assertEquals(emptyList<String>(), ran)
        assertEquals(3 to 7, added.single().let { it.startLine to it.endLine })
    }

    fun testADragWithoutTheLeftButtonAfterALostReleaseLeavesTheSelection() {
        val editor = openFile("ReleaseLostRightDrag.txt")
        install(editor)
        val plus = showPlus(editor, 2)
        val y = { line: Int -> editor.logicalPositionToXY(LogicalPosition(line, 0)).y + editor.lineHeight / 2 }
        send(editor, MouseEvent.MOUSE_PRESSED, plus)
        send(editor, MouseEvent.MOUSE_DRAGGED, Point(plus.x, y(6)))
        val document = editor.document
        assertEquals(document.getLineStartOffset(2) to document.getLineEndOffset(6), editor.selectionModel.let { it.selectionStart to it.selectionEnd })

        send(editor, MouseEvent.MOUSE_PRESSED, Point(plus.x, y(6)), MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK)
        send(editor, MouseEvent.MOUSE_DRAGGED, Point(plus.x, y(8)), held = InputEvent.BUTTON3_DOWN_MASK)

        assertEquals(document.getLineStartOffset(2) to document.getLineEndOffset(6), editor.selectionModel.let { it.selectionStart to it.selectionEnd })
    }

    fun testARangeReleasedOutsideTheGutterAddsTheRangeAndEndsTheDrag() {
        val editor = openFile("ReleaseOutside.txt")
        install(editor)
        val run = icon(editor, 6, "run")
        val plus = showPlus(editor, 2)
        val outside = Point(editor.gutterComponentEx.preferredSize.width + 40, centerOf(editor, run).y)

        dragAndRelease(editor, plus, outside)
        click(editor, centerOf(editor, run))

        assertEquals(3 to 7, added.single().let { it.startLine to it.endLine })
        assertEquals(listOf("run"), ran)
    }

    fun testARangeReleasedOnAFoldAnchorAddsTheRangeAndLeavesTheFoldAsItWas() {
        val editor = openFile("ReleaseFold.txt")
        install(editor)
        val document = editor.document
        var fold: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperation {
            fold = editor.foldingModel.addFoldRegion(document.getLineStartOffset(6), document.getLineEndOffset(8), "...")
            fold!!.isExpanded = false
        }
        val plus = showPlus(editor, 2)
        val gutter = editor.gutterComponentEx
        val y = editor.logicalPositionToXY(LogicalPosition(6, 0)).y + editor.lineHeight / 2
        val anchor = (gutter.foldingAreaOffset until gutter.foldingAreaOffset + 40).map { Point(it, y) }.first { gutter.findFoldingAnchorAt(it.x, it.y) === fold }

        dragAndRelease(editor, plus, anchor)

        assertEquals(3 to 7, added.single().let { it.startLine to it.endLine })
        assertFalse(fold!!.isExpanded)
    }
}
