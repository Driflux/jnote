package io.github.driflux.jnote.gutter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.util.concurrent.ConcurrentHashMap

internal data class RangeBar(val noteId: Long, val startLine: Int, val endLine: Int)

internal object RangeBarSpec {
    internal fun of(notes: List<CachedNote>): List<RangeBar> =
        GutterLines.group(notes).flatMap { it.notes }
            .filter { it.endLine > it.startLine }
            .map { RangeBar(it.id, it.startLine, it.endLine) }
            .sortedBy { it.noteId }
}

internal class RangeBars(private val project: Project) {
    private class Applied(val bar: RangeBar, val highlighter: RangeHighlighter)

    private val requested = ConcurrentHashMap<Document, List<RangeBar>>()

    @Volatile
    private var disposed = false

    private val applied = HashMap<Document, MutableMap<Long, Applied>>()

    fun update(document: Document, bars: List<RangeBar>) {
        val before = requested[document].orEmpty()
        if (before == bars) return
        if (bars.isEmpty()) requested.remove(document) else requested[document] = bars
        val stamp = document.modificationStamp
        ApplicationManager.getApplication().invokeLater({ apply(document, bars, stamp) }, ModalityState.any(), project.disposed)
    }

    fun forget(document: Document) {
        requested.remove(document)
        runOnEdt { removeAll(document) }
    }

    fun dispose() {
        disposed = true
        requested.clear()
        runOnEdt { applied.keys.toList().forEach(::removeAll) }
    }

    private fun apply(document: Document, bars: List<RangeBar>, stamp: Long) {
        if (disposed || requested[document].orEmpty() != bars) return
        if (bars.isNotEmpty() && (document.modificationStamp != stamp || EditorFactory.getInstance().editors(document, project).findAny().isEmpty)) {
            requested.remove(document, bars)
            return
        }
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val current = applied.getOrPut(document) { HashMap() }
        val wanted = bars.associateBy { it.noteId }
        for (id in current.keys.toList()) {
            val existing = current[id]!!
            if (wanted[id] != existing.bar || !existing.highlighter.isValid) {
                if (existing.highlighter.isValid) markup.removeHighlighter(existing.highlighter)
                current.remove(id)
            }
        }
        for (bar in bars) {
            if (bar.noteId in current || bar.startLine < 1 || bar.endLine > document.lineCount) continue
            val highlighter = markup.addRangeHighlighter(
                null,
                document.getLineStartOffset(bar.startLine - 1),
                document.getLineEndOffset(bar.endLine - 1),
                HighlighterLayer.FIRST,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.lineMarkerRenderer = RangeBarRenderer
            current[bar.noteId] = Applied(bar, highlighter)
        }
        if (current.isEmpty()) applied.remove(document)
    }

    private fun removeAll(document: Document) {
        val current = applied.remove(document) ?: return
        val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return
        current.values.forEach { if (it.highlighter.isValid) markup.removeHighlighter(it.highlighter) }
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block, ModalityState.any())
    }

    internal fun appliedFor(document: Document): List<RangeBar> =
        applied[document]?.values?.map { it.bar }?.sortedBy { it.noteId }.orEmpty()
}

internal object RangeBarRenderer : LineMarkerRendererEx {
    const val BAR_WIDTH = 2
    private val COLOR = JBColor(0x3574F0, 0x548AF7)

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.CUSTOM

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val gutter = (editor as? EditorEx)?.gutterComponentEx ?: return
        val x = gutter.lineNumberAreaOffset + gutter.lineNumberAreaWidth + 1
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = COLOR
            g2.fillRoundRect(x, r.y + 1, BAR_WIDTH, maxOf(r.height - 2, 1), BAR_WIDTH, BAR_WIDTH)
        } finally {
            g2.dispose()
        }
    }
}
