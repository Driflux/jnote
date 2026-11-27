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

/** 여러 줄 노트 하나의 거터 막대. 줄은 1-based, 끝 줄 포함이고 마커 패스가 RangeMarker로 따라간 현재 줄이다. */
internal data class RangeBar(val noteId: Long, val startLine: Int, val endLine: Int)

internal object RangeBarSpec {
    /** 막대를 그릴 노트: 거터에 올라가는 상태(OK, APPROX)이고 두 줄 이상에 걸친 것. */
    internal fun of(notes: List<CachedNote>): List<RangeBar> =
        GutterLines.group(notes).flatMap { it.notes }
            .filter { it.endLine > it.startLine }
            .map { RangeBar(it.id, it.startLine, it.endLine) }
            .sortedBy { it.noteId }
}

/**
 * 문서의 마크업 모델에 막대 하이라이터를 맞춰 둔다. 에디터 배경은 칠하지 않고 거터에만 그린다.
 * 마커 패스(백그라운드)가 [update]를 부르면 값이 달라졌을 때만 EDT에서 하이라이터를 고치므로,
 * 노트가 없는 파일은 맵 조회 한 번이다. 하이라이터는 편집을 따라가므로 패스 사이에도 막대가 코드와 함께 움직인다.
 */
internal class RangeBars(private val project: Project) {
    private class Applied(val bar: RangeBar, val highlighter: RangeHighlighter)

    private val requested = ConcurrentHashMap<Document, List<RangeBar>>()

    @Volatile
    private var disposed = false

    // EDT에서만 건드린다
    private val applied = HashMap<Document, MutableMap<Long, Applied>>()

    fun update(document: Document, bars: List<RangeBar>) {
        val before = requested[document].orEmpty()
        if (before == bars) return
        if (bars.isEmpty()) requested.remove(document) else requested[document] = bars
        // 줄 번호는 이 시점의 문서 기준이므로, 적용 전에 문서가 바뀌었는지 알아보려고 수정 스탬프를 함께 넘긴다
        val stamp = document.modificationStamp
        ApplicationManager.getApplication().invokeLater({ apply(document, bars, stamp) }, ModalityState.any(), project.disposed)
    }

    /** 파일을 닫았을 때. 다시 열면 같은 막대라도 새로 그리도록 요청 기록도 지운다. */
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
        // 그 사이 더 새 요청이 왔다면 그 요청의 적용이 따로 예약되어 있다
        if (disposed || requested[document].orEmpty() != bars) return
        // 편집이나 파일 닫기가 먼저 일어났다면 이 줄 번호는 낡았다. 요청 기록을 지워 다음 패스가 다시 요청하게 한다
        if (document.modificationStamp != stamp || EditorFactory.getInstance().editors(document, project).findAny().isEmpty) {
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

/** 줄 번호 칸 오른쪽의 좁은 틈에 그리는 가는 세로 막대. 거터 칸의 위치는 편집기마다 다르므로 그릴 때 읽는다. */
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
