package io.github.driflux.jnote.ai

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.settings.DbPathNotifier
import java.awt.datatransfer.DataFlavor

/** 클립보드 글은 메모리에서 바로 읽고, 해석부터는 [ImportFlow]가 백그라운드에서 한다. 인자는 테스트에서 흐름을 바꿔 끼우기 위한 것이다. */
class ImportFromClipboardAction(
    private val start: (Project, List<ImportSource>) -> Unit = { project, sources -> ImportFlow(project).start(sources) },
) : AnAction(), DumbAware {
    init {
        templatePresentation.text = JNoteBundle.message("action.importFromClipboard.text")
        templatePresentation.description = JNoteBundle.message("action.importFromClipboard.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        if (text.isNullOrBlank()) {
            NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
                .createNotification(
                    JNoteBundle.message("notification.importClipboard.empty.title"),
                    JNoteBundle.message("notification.importClipboard.empty.content"),
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }
        start(project, listOf(ImportSource(JNoteBundle.message("import.source.clipboard")) { text }))
    }
}
