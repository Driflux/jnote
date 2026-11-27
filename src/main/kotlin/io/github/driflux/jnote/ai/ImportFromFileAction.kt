package io.github.driflux.jnote.ai

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import java.nio.file.Files
import java.nio.file.Path

class ImportFromFileAction(
    private val askFile: (Project) -> Path? = ::askFile,
    private val start: (Project, List<ImportSource>) -> Unit = { project, sources -> ImportFlow(project).start(sources) },
) : AnAction(), DumbAware {
    init {
        templatePresentation.text = JNoteBundle.message("action.importFromFile.text")
        templatePresentation.description = JNoteBundle.message("action.importFromFile.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = askFile(project) ?: return
        start(project, listOf(ImportSource(path.fileName.toString()) { Files.readString(path) }))
    }

    private companion object {
        fun askFile(project: Project): Path? {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                .withTitle(JNoteBundle.message("dialog.importFile.title"))
                .withDescription(JNoteBundle.message("dialog.importFile.description"))
            return FileChooser.chooseFile(descriptor, project, null)?.toNioPath()
        }
    }
}
