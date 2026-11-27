package io.github.driflux.jnote.gutter

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
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

internal class GutterGeometry(
    val lineAt: (MouseEvent) -> Int,
    val inIconArea: (MouseEvent) -> Boolean,
    val onAddIcon: (MouseEvent) -> Boolean,
) {
    companion object {
        val ICON_COLUMN_WIDTH: Int get() = GutterIcons.ADD.iconWidth + 2

        fun of(editor: EditorEx): GutterGeometry {
            val gutter = editor.gutterComponentEx
            return GutterGeometry(
                lineAt = { e -> EditorUtil.yPositionToLogicalLine(editor, e) },
                inIconArea = { e ->
                    e.x >= gutter.annotationsAreaOffset + gutter.annotationsAreaWidth &&
                        e.x < gutter.iconAreaOffset + maxOf(gutter.iconsAreaWidth, ICON_COLUMN_WIDTH)
                },
                onAddIcon = { e -> gutter.getGutterRenderer(e.point) is AddNoteGutterRenderer },
            )
        }
    }
}

internal class GutterAddNoteListener(
    private val editor: Editor,
    private val project: Project,
    private val geometry: GutterGeometry,
    private val addNote: (Project, AnchorDraft) -> Unit = { target, draft -> AddNoteAction().addNote(target, draft) },
) : MouseAdapter() {
    private val drag = GutterDrag()
    private var highlighter: RangeHighlighter? = null
    private var selecting = false

    // 드래그 동안만 등록된다. EditorImpl은 이 리스너가 소비한 release를 거터에 넘기지 않으므로(ADR 0115),
    // "+" 밖에서 놓으면 그 자리 아이콘의 클릭 액션이 돌지 않고, "+" 위에서 놓으면 "+"의 클릭 액션이 그대로 돈다.
    private val releaseGuard = object : EditorMouseListener {
        override fun mouseReleased(event: EditorMouseEvent) {
            val e = event.mouseEvent
            if (e.component === editor.gutter && !geometry.onAddIcon(e)) event.consume()
        }
    }

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
        drag.press(clamp(geometry.lineAt(e))) { editor.removeEditorMouseListener(releaseGuard) }
        editor.addEditorMouseListener(releaseGuard)
    }

    override fun mouseDragged(e: MouseEvent) {
        if (editor.isDisposed) return
        if (!drag.active) return
        val range = drag.drag(clamp(geometry.lineAt(e)))
        if (range == null) {
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
        if (!geometry.inIconArea(e)) hide()
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
        added.gutterIconRenderer = AddNoteGutterRenderer(onClick = { if (drag.active) shownLine?.let { addNoteForLines(it, it) } })
        highlighter = added
    }

    private fun hide() {
        highlighter?.let { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        highlighter = null
    }

    private fun addNoteForLines(startLine: Int, endLine: Int) {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val draft = CodeAnchors.fromLines(project, file, document, startLine + 1, endLine + 1) ?: return
        addNote(project, draft)
    }
}