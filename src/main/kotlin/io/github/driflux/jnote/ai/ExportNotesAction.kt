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
    private val start: (Project, Path, Set<Long>?) -> Unit,
) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = e.project != null
        e.presentation.isEnabled = e.project != null && (!selectedOnly || selectedIds(e).isNotEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ids = if (selectedOnly) selectedIds(e).takeIf { it.isNotEmpty() } ?: return else null
        val target = askTarget(project) ?: return
        start(project, target, ids)
    }

    private fun selectedIds(e: AnActionEvent): Set<Long> = e.getData(SELECTED_NOTES).orEmpty().mapTo(LinkedHashSet()) { it.id }

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

        fun startTask(project: Project, target: Path, ids: Set<Long>?) = ExportNotesTask(project, target, ids).queue()
    }
}

class ExportAllNotesAction(
    askTarget: (Project) -> Path? = ::askTarget,
    start: (Project, Path, Set<Long>?) -> Unit = ::startTask,
) : ExportNotesAction(false, askTarget, start) {
    init {
        templatePresentation.text = JNoteBundle.message("action.exportAllNotes.text")
        templatePresentation.description = JNoteBundle.message("action.exportAllNotes.description")
    }
}

class ExportSelectedNotesAction(
    askTarget: (Project) -> Path? = ::askTarget,
    start: (Project, Path, Set<Long>?) -> Unit = ::startTask,
) : ExportNotesAction(true, askTarget, start) {
    init {
        templatePresentation.text = JNoteBundle.message("action.exportSelectedNotes.text")
        templatePresentation.description = JNoteBundle.message("action.exportSelectedNotes.description")
    }
}
