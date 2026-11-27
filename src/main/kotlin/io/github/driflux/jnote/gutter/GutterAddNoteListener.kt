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

internal class GutterGeometry(
    val lineAt: (MouseEvent) -> Int,
    val inIconArea: (MouseEvent) -> Boolean,
    val onAddIcon: (MouseEvent) -> Boolean,
    /** 노트 아이콘과 범위 막대가 있는 칸 안인가. 줄 번호 칸은 제외한다. */
    val inPreviewArea: (MouseEvent) -> Boolean = { false },
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
                inPreviewArea = { e ->
                    e.x >= gutter.lineNumberAreaOffset + gutter.lineNumberAreaWidth &&
                        e.x < gutter.iconAreaOffset + maxOf(gutter.iconsAreaWidth, ICON_COLUMN_WIDTH)
                },
            )
        }
    }
}

internal class GutterAddNoteListener(
    private val editor: Editor,
    private val project: Project,
    private val geometry: GutterGeometry,
    private val preview: GutterPreviewController? = null,
    private val addNote: (Project, AnchorDraft) -> Unit = { target, draft -> AddNoteAction().addNote(target, draft) },
) : MouseAdapter() {
    private val drag = GutterDrag()
    private var highlighter: RangeHighlighter? = null
    private var selecting = false

    internal val shownLine: Int? get() = highlighter?.takeIf { it.isValid }?.let { editor.document.getLineNumber(it.startOffset) }

    override fun mouseMoved(e: MouseEvent) {
        if (drag.active || editor.isDisposed) return
        val line = geometry.lineAt(e)
        val inDocument = line >= 0 && line < editor.document.lineCount
        preview?.hover(if (inDocument && geometry.inPreviewArea(e)) line else null, e.y)
        if (!geometry.inIconArea(e) || !inDocument) {
            hide()
            return
        }
        if (line != shownLine) show(line)
    }

    override fun mouseExited(e: MouseEvent) {
        if (drag.active) return
        preview?.hover(null, 0)
        hide()
    }

    override fun mousePressed(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger || editor.isDisposed) return
        if (!geometry.onAddIcon(e)) return
        preview?.hover(null, 0)
        drag.press(clamp(geometry.lineAt(e)))
        GutterDragging.mark(e, true)
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
        GutterDragging.mark(e, false)
        if (!geometry.inIconArea(e)) hide()
        if (range != null && !editor.isDisposed) addNoteForLines(range.start, range.end)
    }

    fun dispose() {
        preview?.dispose()
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

internal object GutterDragging {
    private const val KEY = "jnote.gutter.dragging"

    fun mark(e: MouseEvent, dragging: Boolean) {
        (e.component as? JComponent)?.putClientProperty(KEY, if (dragging) true else null)
    }

    fun active(e: MouseEvent): Boolean = (e.component as? JComponent)?.getClientProperty(KEY) == true
}