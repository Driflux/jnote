package io.github.driflux.jnote.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.ui.AddNoteDialog
import io.github.driflux.jnote.ui.NoteDraft
import java.util.concurrent.CompletionException

/**
 * Tool Window 툴바의 "새 노트" 버튼. 앵커 없는 자유 노트를 만든다.
 * 인자는 테스트에서 다이얼로그와 저장소를 바꿔 끼우기 위한 것이다.
 */
class NewFreeNoteAction(
    private val askUser: (Project) -> NoteDraft? = AddNoteDialog::askFree,
    private val saver: NoteSaver = NoteSaver(),
) : AnAction(), DumbAware {
    init {
        templatePresentation.text = JNoteBundle.message("action.newFreeNote.text")
        templatePresentation.description = JNoteBundle.message("action.newFreeNote.description")
        templatePresentation.icon = AllIcons.General.Add
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        askAndSave(project)
    }

    internal fun askAndSave(project: Project) {
        // 다이얼로그가 열려 있는 동안 프로젝트가 닫힐 수 있다
        if (project.isDisposed) return
        val draft = askUser(project) ?: return
        if (project.isDisposed) return
        saver.saveFree(draft).whenComplete { saved, error ->
            if (project.isDisposed) return@whenComplete
            if (error == null) {
                AddNoteNotifier.saved(project, saved)
            } else {
                AddNoteNotifier.failed(project, (error as? CompletionException)?.cause ?: error)
            }
        }
    }

    companion object {
        const val ID = "JNote.NewFreeNote"
    }
}
