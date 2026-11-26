package io.github.driflux.jnote.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.anchor.AnchorLines

/** 인자는 테스트에서 작업 시작을 바꿔 끼우기 위한 것이다. */
class RelocateNotesAction(
    private val start: (Project, String) -> Unit = { project, relPath -> RelocateNotesTask(project, relPath).queue() },
) : AnAction(), DumbAware {
    init {
        templatePresentation.text = JNoteBundle.message("action.relocateNotes.text")
        templatePresentation.description = JNoteBundle.message("action.relocateNotes.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = relPathOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val relPath = relPathOf(e) ?: return
        start(project, relPath)
    }

    // 문자열 비교뿐인 가벼운 검사라 update에서도 부른다
    private fun relPathOf(e: AnActionEvent): String? {
        val project = e.project ?: return null
        if (e.getData(CommonDataKeys.EDITOR) == null) return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return AnchorLines.relativePath(project.basePath, file.path)
    }
}
