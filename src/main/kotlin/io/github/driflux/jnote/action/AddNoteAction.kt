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

/**
 * EDT에서는 문서만 읽어 초안을 만들고, 클래스 이름은 non-blocking 읽기 액션에서 구한 뒤
 * UI 스레드에서 다이얼로그를 띄운다. 저장은 [NoteSaver]가 EDT 밖에서 한다.
 * 인자는 테스트에서 다이얼로그와 저장소를 바꿔 끼우기 위한 것이다.
 */
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
        // 문자열 비교뿐인 가벼운 검사만 한다
        e.presentation.isEnabledAndVisible = project != null && e.getData(CommonDataKeys.EDITOR) != null &&
            file != null && AnchorLines.relativePath(project.basePath, file.path) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val draft = CodeAnchors.fromEditor(project, editor) ?: return
        resolveAnchor(project, draft)
    }

    /** 거터의 "+" 클릭이나 드래그가 만든 초안으로 노트 추가를 시작한다. */
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

    // 클래스 이름은 선택 값이라 해석이 실패해도 노트 저장을 막지 않는다. 취소는 그대로 전파한다
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
        // 다이얼로그가 열려 있는 동안 프로젝트가 닫힐 수 있다
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
