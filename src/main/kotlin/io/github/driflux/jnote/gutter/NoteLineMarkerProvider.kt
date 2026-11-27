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

class NoteLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {
    override fun getName(): String = JNoteBundle.message("gutter.provider.name")

    override fun getIcon(): Icon = GutterIcons.NOTE

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        val project = file.project
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return
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

    private fun target(group: LineGroup, document: Document): Target? {
        if (group.line < 1 || group.line > document.lineCount) return null
        val start = document.getLineStartOffset(group.line - 1)
        val end = document.getLineEndOffset(group.line - 1)
        val text = document.charsSequence
        var offset = start
        while (offset < end && text[offset].isWhitespace()) offset++
        return Target(group, if (offset < end) offset else start, end)
    }

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
        val tooltip = group.notes.joinToString("<br>") { StringUtil.escapeXmlEntities(it.title) }
        val accessibleName = group.notes.joinToString(", ") { it.title }
        val range = TextRange(target.offset, maxOf(target.offset, minOf(target.lineEnd, leaf.textRange.endOffset)))
        return LineMarkerInfo(
            leaf,
            range,
            if (group.approxOnly) GutterIcons.APPROX else GutterIcons.NOTE,
            { tooltip },
            GutterIconNavigationHandler { event, _ ->
                if (GutterDragging.active(event)) return@GutterIconNavigationHandler
                val single = group.notes.singleOrNull()
                if (single != null) NoteSelection.request(project, single.id) else NoteChooser.show(event, project, group.notes)
            },
            GutterIconRenderer.Alignment.RIGHT,
            { accessibleName },
        )
    }
}