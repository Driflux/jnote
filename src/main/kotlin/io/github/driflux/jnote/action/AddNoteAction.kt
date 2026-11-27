package io.github.driflux.jnote.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.anchor.AnchorLines
import io.github.driflux.jnote.anchor.AnchorNameResolver
import io.github.driflux.jnote.anchor.PsiAnchorNameResolver
import io.github.driflux.jnote.anchor.AnchorDraft
import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.anchor.CodeAnchors
import io.github.driflux.jnote.ui.AddNoteDialog
import io.github.driflux.jnote.ui.NoteDraft
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException

class AddNoteAction(
    private val askUser: (Project, CodeAnchor) -> NoteDraft? = AddNoteDialog::ask,
    private val saver: NoteSaver = NoteSaver(),
    private val nameResolver: AnchorNameResolver = PsiAnchorNameResolver(),
) : AnAction(), DumbAware {
    init {
        templatePresentation.text = JNoteBundle.message("action.addNote.text")
        templatePresentation.description = JNoteBundle.message("action.addNote.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && e.getData(CommonDataKeys.EDITOR) != null &&
            file != null && AnchorLines.relativePath(project.basePath, file.path) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val draft = CodeAnchors.fromEditor(project, editor) ?: return
        resolveAnchor(project, draft)
    }

    internal fun addNote(project: Project, draft: AnchorDraft) = resolveAnchor(project, draft)

    private fun resolveAnchor(project: Project, draft: AnchorDraft) {
        ReadAction.nonBlocking(Callable { resolveOrNameless(project, draft) })
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { anchor -> askAndSave(project, anchor) }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError { cause ->
                if (cause !is CancellationException && cause !is ProcessCanceledException && !project.isDisposed) {
                    AddNoteNotifier.failed(project, cause)
                }
            }
    }

    private fun resolveOrNameless(project: Project, draft: AnchorDraft): CodeAnchor = try {
        CodeAnchors.resolve(project, draft, nameResolver)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CodeAnchor(draft.relPath, draft.startLine, draft.endLine, draft.snapshot, null)
    }

    internal fun askAndSave(project: Project, anchor: CodeAnchor) {
        if (project.isDisposed) return
        val note = askUser(project, anchor) ?: return
        if (project.isDisposed) return
        saver.save(project, anchor, note).whenComplete { saved, error ->
            if (project.isDisposed) return@whenComplete
            if (error == null) {
                AddNoteNotifier.saved(project, saved)
            } else {
                AddNoteNotifier.failed(project, (error as? CompletionException)?.cause ?: error)
            }
        }
    }
}