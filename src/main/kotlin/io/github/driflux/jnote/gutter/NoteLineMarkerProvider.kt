package io.github.driflux.jnote.gutter

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.anchor.AnchorLines
import io.github.driflux.jnote.relocate.OpenValidationService
import io.github.driflux.jnote.ui.NoteSelection
import javax.swing.Icon

/**
 * 캐시에 아직 없는 파일은 마커 없이 돌려주고, 읽기가 끝나면 [GutterNoteService]가 그 파일의 데몬을 다시 돌린다.
 * 요소마다 부르는 getLineMarkerInfo가 아니라 요소 목록마다 한 번 부르는 collectSlowLineMarkers를 쓰므로,
 * 노트가 없는 파일은 캐시 조회 한 번으로 끝난다.
 */
class NoteLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {
    override fun getName(): String = JNoteBundle.message("gutter.provider.name")

    override fun getIcon(): Icon = GutterIcons.NOTE

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return
        // 템플릿 언어처럼 한 파일에 루트가 여럿이면 루트마다 불리므로 기본 언어 루트에서만 그린다
        val viewProvider = file.viewProvider
        if (viewProvider.getPsi(viewProvider.baseLanguage) != file) return
        val virtualFile = file.virtualFile ?: return
        val relPath = AnchorLines.relativePath(project.basePath, virtualFile.path) ?: return
        val gutterService = project.service<GutterNoteService>()
        FileDocumentManager.getInstance().getCachedDocument(virtualFile)?.let { gutterService.attachGutterInteraction(it) }
        val notes = gutterService.notesFor(virtualFile, relPath)
        if (notes.isNullOrEmpty()) return
        val document = PsiDocumentManager.getInstance(project).getCachedDocument(file) ?: return
        project.service<OpenValidationService>().validateOncePerOpen(document, relPath)
        val targets = GutterLines.group(project.service<GutterNoteService>().track(document, relPath, notes)).mapNotNull { target(it, document) }
        if (targets.isEmpty()) return

        // 잎 요소는 파일을 겹치지 않게 나누므로 목표마다 그 위치를 담은 잎 하나에만 마커가 붙는다.
        // 패스는 보이는 영역과 나머지로 나눠 두 번 부르므로, 이 목록의 잎에 든 목표만 맡는다
        for (element in elements) {
            if (element.firstChild != null) continue
            val range = element.textRange
            var next = firstAtOrAfter(targets, range.startOffset)
            while (next < targets.size && targets[next].offset < range.endOffset) {
                result += marker(project, element, targets[next])
                next++
            }
        }
    }

    private class Target(val group: LineGroup, val offset: Int, val lineEnd: Int)

    /** 줄이 비었으면 줄 시작, 문서 밖이면 null. */
    private fun target(group: LineGroup, document: Document): Target? {
        if (group.line < 1 || group.line > document.lineCount) return null
        val start = document.getLineStartOffset(group.line - 1)
        val end = document.getLineEndOffset(group.line - 1)
        val text = document.charsSequence
        var offset = start
        while (offset < end && text[offset].isWhitespace()) offset++
        return Target(group, if (offset < end) offset else start, end)
    }

    // targets는 줄 순서, 곧 위치 순서다
    private fun firstAtOrAfter(targets: List<Target>, offset: Int): Int {
        var low = 0
        var high = targets.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (targets[mid].offset < offset) low = mid + 1 else high = mid
        }
        return low
    }

    private fun marker(project: Project, leaf: PsiElement, target: Target): LineMarkerInfo<PsiElement> {
        val group = target.group
        // 툴팁은 HTML로 그려지므로 제목을 이스케이프한다
        val tooltip = group.notes.joinToString("<br>") { StringUtil.escapeXmlEntities(it.title) }
        val accessibleName = group.notes.joinToString(", ") { it.title }
        // 마커의 줄은 범위 시작 위치로 정해지므로, 여러 줄에 걸친 잎(일반 텍스트 등)도 시작 줄에 놓인다
        val range = TextRange(target.offset, maxOf(target.offset, minOf(target.lineEnd, leaf.textRange.endOffset)))
        return LineMarkerInfo(
            leaf,
            range,
            if (group.approxOnly) GutterIcons.APPROX else GutterIcons.NOTE,
            { tooltip },
            GutterIconNavigationHandler { event, _ ->
                val single = group.notes.singleOrNull()
                if (single != null) NoteSelection.request(project, single.id) else NoteChooser.show(event, project, group.notes)
            },
            GutterIconRenderer.Alignment.RIGHT,
            { accessibleName },
        )
    }
}
