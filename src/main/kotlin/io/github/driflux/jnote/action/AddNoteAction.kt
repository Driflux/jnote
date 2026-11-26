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
import io.github.driflux.jnote.anchor.AnchorDraft
import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.anchor.CodeAnchors
import io.github.driflux.jnote.ui.AddNoteDialog
import io.github.driflux.jnote.ui.NoteDraft
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException

/**
 * 에디터 선택(없으면 캐럿 줄)으로 코드 노트를 만든다.
 * EDT에서는 문서만 읽어 초안을 만들고, 클래스 이름은 non-blocking 읽기 액션에서 구한 뒤
 * UI 스레드에서 다이얼로그를 띄운다. 저장은 [NoteSaver]가 EDT 밖에서 한다.
 * 인자는 테스트에서 다이얼로그와 저장소를 바꿔 끼우기 위한 것이다.
 */
class AddNoteAction(
    private val askUser: (Project, CodeAnchor) -> NoteDraft? = AddNoteDialog::ask,
    private val saver: NoteSaver = NoteSaver(),
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

    private fun resolveAnchor(project: Project, draft: AnchorDraft) {
        ReadAction.nonBlocking(Callable { CodeAnchors.resolve(project, draft) })
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { anchor -> askAndSave(project, anchor) }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError { cause ->
                if (cause !is CancellationException && cause !is ProcessCanceledException) {
                    AddNoteNotifier.failed(project, cause)
                }
            }
    }

    private fun askAndSave(project: Project, anchor: CodeAnchor) {
        val note = askUser(project, anchor) ?: return
        saver.save(project, anchor, note).whenComplete { saved, error ->
            if (error == null) {
                AddNoteNotifier.saved(project, saved)
            } else {
                AddNoteNotifier.failed(project, (error as? CompletionException)?.cause ?: error)
            }
        }
    }
}
