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
import io.github.driflux.jnote.model.NoteSummary
import java.nio.file.Path
import java.time.LocalDate

val SELECTED_NOTES: DataKey<List<NoteSummary>> = DataKey.create("JNote.SelectedNotes")

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
