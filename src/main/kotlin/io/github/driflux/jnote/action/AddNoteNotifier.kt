package io.github.driflux.jnote.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.Note

/** 노트 추가 결과 알림. 오류는 삼키지 않고 이유와 해결 방법을 보여 준다. */
object AddNoteNotifier {
    private const val GROUP_ID = "JNote"

    fun saved(project: Project, note: Note) =
        notify(project, NotificationType.INFORMATION, JNoteBundle.message("action.addNote.saved.title"), note.title)

    fun failed(project: Project, cause: Throwable) = notify(
        project,
        NotificationType.ERROR,
        JNoteBundle.message("action.addNote.failed.title"),
        JNoteBundle.message("action.addNote.failed.content", cause.message ?: cause.javaClass.simpleName),
    )

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
