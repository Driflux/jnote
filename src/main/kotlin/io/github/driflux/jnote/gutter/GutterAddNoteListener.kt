package io.github.driflux.jnote.gutter

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.action.AddNoteAction
import io.github.driflux.jnote.anchor.AnchorDraft
import io.github.driflux.jnote.anchor.CodeAnchors
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** 마우스 이벤트를 줄과 영역으로 읽는 부분. 헤드리스 테스트가 레이아웃 없이 바꿔 끼울 수 있게 분리했다. */
internal class GutterGeometry(
    /** 0-based 논리 줄. 문서 끝 아래면 줄 수 이상일 수 있다. */
    val lineAt: (MouseEvent) -> Int,
    /** "+"를 띄우는 영역 안인가. 줄 번호와 아이콘 칸이고, 주석 영역과 접기 영역은 제외한다. */
    val inIconArea: (MouseEvent) -> Boolean,
    /** 눌린 곳이 "+" 아이콘인가. */
    val onAddIcon: (MouseEvent) -> Boolean,
) {
    companion object {
        /** 렌더러가 아이콘 칸에 차지하는 폭. 아이콘 폭에 양쪽 여백 1픽셀씩이다. */
        val ICON_COLUMN_WIDTH: Int get() = GutterIcons.ADD.iconWidth + 2

        fun of(editor: EditorEx): GutterGeometry {
            val gutter = editor.gutterComponentEx
            return GutterGeometry(
                // 거터 자신이 클릭한 줄을 구하는 것과 같은 변환이다
                lineAt = { e -> EditorUtil.yPositionToLogicalLine(editor, e) },
                inIconArea = { e ->
                    // New UI는 아이콘이 없는 파일의 아이콘 칸 폭이 0이라 "+" 아이콘의 폭만큼은 영역으로 본다
                    e.x >= gutter.annotationsAreaOffset + gutter.annotationsAreaWidth &&
                        e.x < gutter.iconAreaOffset + maxOf(gutter.iconsAreaWidth, ICON_COLUMN_WIDTH)
                },
                onAddIcon = { e -> gutter.getGutterRenderer(e.point) is AddNoteGutterRenderer },
            )
        }
    }
}

/**
 * 편집기 하나의 거터에 달리는 마우스 리스너. 마우스가 올라간 줄에 "+"를 띄우고, "+"에서 시작한 드래그로 줄 범위를 고른다.
 * 이벤트마다 하는 일은 좌표 한 번 변환과 줄 비교뿐이고, 줄이 바뀔 때만 하이라이터를 옮긴다. 문서만 읽어 DB와 파일 I/O는 없다.
 * 클릭은 거터가 [AddNoteGutterRenderer]의 클릭 동작으로 처리하므로 여기서는 두 줄 이상의 드래그만 다룬다.
 */
internal class GutterAddNoteListener(
    private val editor: Editor,
    private val project: Project,
    private val geometry: GutterGeometry,
    private val addNote: (Project, AnchorDraft) -> Unit = { target, draft -> AddNoteAction().addNote(target, draft) },
) : MouseAdapter() {
    private val drag = GutterDrag()
    private var highlighter: RangeHighlighter? = null
    private var selecting = false

    internal val shownLine: Int? get() = highlighter?.takeIf { it.isValid }?.let { editor.document.getLineNumber(it.startOffset) }

    override fun mouseMoved(e: MouseEvent) {
        if (drag.active || editor.isDisposed) return
        val line = geometry.lineAt(e)
        if (!geometry.inIconArea(e) || line < 0 || line >= editor.document.lineCount) {
            hide()
            return
        }
        if (line != shownLine) show(line)
    }

    override fun mouseExited(e: MouseEvent) {
        if (!drag.active) hide()
    }

    override fun mousePressed(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger || editor.isDisposed) return
        if (!geometry.onAddIcon(e)) return
        drag.press(clamp(geometry.lineAt(e)))
        GutterDragging.mark(e, true)
    }

    override fun mouseDragged(e: MouseEvent) {
        if (editor.isDisposed) return
        if (!drag.active) return
        val range = drag.drag(clamp(geometry.lineAt(e)))
        if (range == null) {
            // 시작 줄로 되돌아오면 드래그가 만든 선택을 거둔다
            if (selecting) editor.selectionModel.removeSelection()
            selecting = false
            return
        }
        val document = editor.document
        editor.selectionModel.setSelection(document.getLineStartOffset(range.start), document.getLineEndOffset(range.end))
        selecting = true
    }

    override fun mouseReleased(e: MouseEvent) {
        if (!drag.active) return
        val range = drag.release(clamp(geometry.lineAt(e)))
        selecting = false
        GutterDragging.mark(e, false)
        if (range != null && !editor.isDisposed) addNoteForLines(range.start, range.end)
    }

    fun dispose() {
        drag.cancel()
        selecting = false
        hide()
    }

    private fun clamp(line: Int): Int = line.coerceIn(0, editor.document.lineCount - 1)

    private fun show(line: Int) {
        hide()
        val added = editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.LAST)
        added.gutterIconRenderer = AddNoteGutterRenderer(onClick = { shownLine?.let { addNoteForLines(it, it) } })
        highlighter = added
    }

    private fun hide() {
        highlighter?.let { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        highlighter = null
    }

    // 줄은 0-based, 앵커는 1-based이다
    private fun addNoteForLines(startLine: Int, endLine: Int) {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val draft = CodeAnchors.fromLines(project, file, document, startLine + 1, endLine + 1) ?: return
        addNote(project, draft)
    }
}

/**
 * 거터는 놓은 자리의 아이콘 클릭 동작을 누른 자리와 비교하지 않고 실행한다. "+"에서 시작한 드래그를 다른 줄의 노트 아이콘 위에서 놓아도
 * 그 아이콘이 열리지 않도록, 드래그 중임을 거터 컴포넌트에 표시하고 아이콘의 클릭 처리기가 이를 확인한다.
 * 거터의 놓기 처리가 우리 리스너보다 먼저 돌므로, 표시는 우리 놓기 처리가 끝낼 때까지 남아 있다.
 */
internal object GutterDragging {
    private const val KEY = "jnote.gutter.dragging"

    fun mark(e: MouseEvent, dragging: Boolean) {
        (e.component as? JComponent)?.putClientProperty(KEY, if (dragging) true else null)
    }

    fun active(e: MouseEvent): Boolean = (e.component as? JComponent)?.getClientProperty(KEY) == true
}
