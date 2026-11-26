package io.github.driflux.jnote.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.Note

object AddNoteNotifier {
    private const val GROUP_ID = "JNote"

    fun saved(project: Project, note: Note) =
        notify(project, NotificationType.INFORMATION, JNoteBundle.message("action.addNote.saved.title"), escape(note.title))

    fun failed(project: Project, cause: Throwable) = notify(
        project,
        NotificationType.ERROR,
        JNoteBundle.message("action.addNote.failed.title"),
        JNoteBundle.message("action.addNote.failed.content", escape(cause.message ?: cause.javaClass.simpleName)),
    )

    // 알림 내용은 HTML로 그려지므로 사용자 입력과 예외 메시지를 이스케이프한다
    private fun escape(text: String) = StringUtil.escapeXmlEntities(text)

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
