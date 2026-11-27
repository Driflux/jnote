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

/** 마우스 이벤트를 줄과 영역으로 읽는 부분. 헤드리스 테스트가 레이아웃 없이 바꿔 끼울 수 있게 분리했다. */
internal class GutterGeometry(
    /** 0-based 논리 줄. 문서 끝 아래면 줄 수 이상일 수 있다. */
    val lineAt: (MouseEvent) -> Int,
    /** 아이콘 영역 안인가. 줄 번호와 접기 영역에서는 "+"를 띄우지 않는다. */
    val inIconArea: (MouseEvent) -> Boolean,
    /** 눌린 곳이 "+" 아이콘인가. */
    val onAddIcon: (MouseEvent) -> Boolean,
) {
    companion object {
        fun of(editor: EditorEx): GutterGeometry {
            val gutter = editor.gutterComponentEx
            return GutterGeometry(
                // 거터 자신이 클릭한 줄을 구하는 것과 같은 변환이다
                lineAt = { e -> EditorUtil.yPositionToLogicalLine(editor, e) },
                inIconArea = { e -> e.x >= gutter.iconAreaOffset && e.x < gutter.iconAreaOffset + gutter.iconsAreaWidth },
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
        if (geometry.onAddIcon(e)) drag.press(clamp(geometry.lineAt(e)))
    }

    override fun mouseDragged(e: MouseEvent) {
        if (editor.isDisposed) return
        val range = drag.drag(clamp(geometry.lineAt(e))) ?: return
        val document = editor.document
        editor.selectionModel.setSelection(document.getLineStartOffset(range.start), document.getLineEndOffset(range.end))
    }

    override fun mouseReleased(e: MouseEvent) {
        if (!drag.active) return
        val range = drag.release(clamp(geometry.lineAt(e)))
        if (range != null && !editor.isDisposed) addNoteForLines(range.start, range.end)
    }

    fun dispose() {
        drag.cancel()
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
