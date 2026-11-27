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

internal class SnippetViewer(private val project: Project) : JBPanel<SnippetViewer>(BorderLayout()) {
    private var editor: EditorEx? = null
    private var shownText: String? = null
    private var shownFileName: String? = null

        internal val text: String? get() = shownText

    internal val viewer: Editor? get() = editor

        fun show(rawSnippet: String?, fileName: String?) {
        if (rawSnippet == null) return release()
        val snippet = StringUtil.convertLineSeparators(rawSnippet)
        if (editor != null && snippet == shownText && fileName == shownFileName) return
        release()
        val factory = EditorFactory.getInstance()
        val created = factory.createViewer(factory.createDocument(snippet), project, EditorKind.PREVIEW) as EditorEx
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
        created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileName ?: "snippet.txt")
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
