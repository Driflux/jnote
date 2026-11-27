package io.github.driflux.jnote.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import java.awt.Dimension
import kotlin.math.min

/**
 * 스니펫을 읽기 전용 편집기로 보여 준다. 편집기는 스니펫이 있는 노트를 고를 때 만들고, 선택이 바뀌거나 닫히면 풀어 준다.
 * 아무것도 고르지 않았을 때는 편집기가 없으므로 목록을 보기만 하는 동안에는 비용이 없다. EDT에서만 쓴다.
 */
internal class SnippetViewer(private val project: Project) : JBPanel<SnippetViewer>(BorderLayout()) {
    private var editor: EditorEx? = null
    private var shownText: String? = null
    private var shownFileName: String? = null

    /** 지금 보이는 스니펫. 편집기가 없으면 null. */
    internal val text: String? get() = shownText

    internal val viewer: Editor? get() = editor

    /** 같은 스니펫을 다시 받으면(다시 읽기) 편집기를 그대로 둔다. [fileName]은 색을 고르는 데만 쓰며 파일을 읽지 않는다. */
    fun show(rawSnippet: String?, fileName: String?) {
        if (rawSnippet == null) return release()
        // 문서는 줄바꿈이 \n이어야 하는데, 노트에는 가져오기로 \r\n이 들어올 수 있다. 복사는 원문을 쓴다
        val snippet = StringUtil.convertLineSeparators(rawSnippet)
        if (editor != null && snippet == shownText && fileName == shownFileName) return
        release()
        val factory = EditorFactory.getInstance()
        val created = factory.createViewer(factory.createDocument(snippet), project, EditorKind.PREVIEW) as EditorEx
        // 아래 설정이 실패해도 방금 만든 편집기를 release()가 풀 수 있게 먼저 기록한다
        editor = created
        shownText = snippet
        shownFileName = fileName
        try {
            configure(created, snippet, fileName)
        } catch (e: Throwable) {
            release()
            throw e
        }
        revalidate()
    }

    private fun configure(created: EditorEx, snippet: String, fileName: String?) {
        created.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isAdditionalPageAtBottom = false
            additionalLinesCount = 0
            isCaretRowShown = false
        }
        // 파일 이름이 없으면 일반 텍스트다
        created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileName ?: "snippet.txt")
        // 긴 줄이 있으면 가로 스크롤바가 자리를 차지하므로 그만큼 더 준다
        val scrollbar = created.scrollPane.horizontalScrollBar.preferredSize.height
        created.component.preferredSize = Dimension(0, min(created.document.lineCount, MAX_VISIBLE_LINES) * created.lineHeight + PADDING + scrollbar)
        add(created.component, BorderLayout.CENTER)
    }

    fun release() {
        val old = editor ?: return
        editor = null
        shownText = null
        shownFileName = null
        remove(old.component)
        EditorFactory.getInstance().releaseEditor(old)
        revalidate()
    }

    private companion object {
        const val MAX_VISIBLE_LINES = 12
        const val PADDING = 6
    }
}
