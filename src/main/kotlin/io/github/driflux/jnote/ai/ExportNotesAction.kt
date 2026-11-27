package io.github.driflux.jnote.ai

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.Note
import java.nio.file.Path
import java.time.LocalDate

/** Tool Window 목록에서 선택한 노트. 목록이 이 키로 내놓는다. */
val SELECTED_NOTES: DataKey<List<Note>> = DataKey.create("JNote.SelectedNotes")

/**
 * 저장 위치는 EDT에서 고르고, 읽기와 쓰기는 [ExportNotesTask]가 백그라운드에서 한다.
 * 인자는 테스트에서 파일 대화상자와 작업 시작을 바꿔 끼우기 위한 것이다.
 */
abstract class ExportNotesAction(
    private val selectedOnly: Boolean,
    private val askTarget: (Project) -> Path?,
    private val start: (Project, Path, Set<String>?) -> Unit,
) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = e.project != null
        e.presentation.isEnabled = e.project != null && (!selectedOnly || selectedUids(e).isNotEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // 대화상자가 열린 사이 다른 IDE가 노트를 지우고 같은 id로 새 노트를 만들 수 있어, 재사용되지 않는 uid로 넘긴다
        val uids = if (selectedOnly) selectedUids(e).takeIf { it.isNotEmpty() } ?: return else null
        val target = askTarget(project) ?: return
        start(project, target, uids)
    }

    private fun selectedUids(e: AnActionEvent): Set<String> = e.getData(SELECTED_NOTES).orEmpty().mapTo(LinkedHashSet()) { it.uid }

    internal companion object {
        fun askTarget(project: Project): Path? {
            val descriptor = FileSaverDescriptor(
                JNoteBundle.message("dialog.export.title"),
                JNoteBundle.message("dialog.export.description"),
                "json",
            )
            return FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save("jnote-notes-${LocalDate.now()}.json")
                ?.file
                ?.toPath()
        }

        fun startTask(project: Project, target: Path, uids: Set<String>?) = ExportNotesTask(project, target, uids).queue()
    }
}

class ExportAllNotesAction(
    askTarget: (Project) -> Path? = ::askTarget,
    start: (Project, Path, Set<String>?) -> Unit = ::startTask,
) : ExportNotesAction(false, askTarget, start) {
    init {
        templatePresentation.text = JNoteBundle.message("action.exportAllNotes.text")
        templatePresentation.description = JNoteBundle.message("action.exportAllNotes.description")
    }
}

class ExportSelectedNotesAction(
    askTarget: (Project) -> Path? = ::askTarget,
    start: (Project, Path, Set<String>?) -> Unit = ::startTask,
) : ExportNotesAction(true, askTarget, start) {
    init {
        templatePresentation.text = JNoteBundle.message("action.exportSelectedNotes.text")
        templatePresentation.description = JNoteBundle.message("action.exportSelectedNotes.description")
    }
}
