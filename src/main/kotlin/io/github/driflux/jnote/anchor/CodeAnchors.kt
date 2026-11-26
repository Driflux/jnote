package io.github.driflux.jnote.anchor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/**
 * EDT에서 문서만으로 모은 앵커 값. PSI가 필요한 클래스 이름은 [CodeAnchors.resolve]가 나중에 채운다.
 * [nameOffset]은 시작 줄의 첫 공백 아닌 글자 위치다.
 */
data class AnchorDraft(
    val file: VirtualFile,
    val relPath: String,
    val startLine: Int,
    val endLine: Int,
    val snapshot: String,
    val nameOffset: Int,
)

/** 앵커를 모으는 두 진입점(에디터 선택, 명시적 줄 범위)과 클래스 이름 해석. */
object CodeAnchors {
    /**
     * 주 캐럿의 선택으로 초안을 만든다. 선택이 없으면 캐럿 줄 하나를 쓴다.
     * 문서만 읽으므로 EDT에서 불러도 가볍다. 프로젝트 밖 파일이면 null.
     */
    fun fromEditor(project: Project, editor: Editor): AnchorDraft? {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val caret = editor.caretModel.primaryCaret
        val (from, to) = if (caret.hasSelection()) caret.selectionStart to caret.selectionEnd else caret.offset to caret.offset
        val fromLine = document.getLineNumber(from)
        val toLine = document.getLineNumber(to)
        val range = AnchorLines.ofSelection(
            fromLine, from - document.getLineStartOffset(fromLine),
            toLine, to - document.getLineStartOffset(toLine),
        )
        return draft(project, file, document, range)
    }

    /** 1-based, 끝 줄 포함 범위로 초안을 만든다. 거터 드래그처럼 거꾸로 온 범위는 바로잡고, 문서 밖이면 null. */
    fun fromLines(project: Project, file: VirtualFile, document: Document, startLine: Int, endLine: Int): AnchorDraft? {
        val range = LineRange(minOf(startLine, endLine), maxOf(startLine, endLine))
        if (range.start < 1 || range.end > document.lineCount) return null
        return draft(project, file, document, range)
    }

    /** 읽기 액션 안에서 호출한다. PSI를 읽어 클래스 이름을 채운 최종 앵커를 만든다. */
    fun resolve(project: Project, draft: AnchorDraft, resolver: AnchorNameResolver = PsiAnchorNameResolver()): CodeAnchor {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val psiFile = PsiManager.getInstance(project).findFile(draft.file)
        val className = psiFile?.let { resolver.resolve(it, draft.nameOffset) }
        return CodeAnchor(draft.relPath, draft.startLine, draft.endLine, draft.snapshot, className)
    }

    private fun draft(project: Project, file: VirtualFile, document: Document, range: LineRange): AnchorDraft? {
        val relPath = AnchorLines.relativePath(project.basePath, file.path) ?: return null
        val startOffset = document.getLineStartOffset(range.start - 1)
        val endOffset = document.getLineEndOffset(range.end - 1)
        val text = document.charsSequence
        val snapshot = AnchorLines.snapshotOf(text, startOffset, endOffset)
        val lineEnd = document.getLineEndOffset(range.start - 1)
        var nameOffset = startOffset
        while (nameOffset < lineEnd && text[nameOffset].isWhitespace()) nameOffset++
        return AnchorDraft(file, relPath, range.start, range.end, snapshot, nameOffset)
    }
}
