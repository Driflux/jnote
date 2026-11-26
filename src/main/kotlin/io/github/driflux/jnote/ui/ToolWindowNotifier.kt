package io.github.driflux.jnote.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.settings.DbPathNotifier

/** Tool Window가 보여주는 알림. 오류는 삼키지 않고 이유와 함께 알린다. */
internal object ToolWindowNotifier {
    fun loadFailed(project: Project, cause: Throwable) {
        NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
            .createNotification(
                JNoteBundle.message("toolWindow.error.load.title"),
                JNoteBundle.message("toolWindow.error.load.content", cause.message ?: cause.javaClass.simpleName),
                NotificationType.ERROR,
            )
            .notify(project)
    }

    /** 재배치는 이후 이슈에서 다루므로 지금은 파일이 없다는 사실만 알린다. */
    fun fileMissing(project: Project, relPath: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
            .createNotification(
                JNoteBundle.message("toolWindow.error.fileMissing.title"),
                JNoteBundle.message("toolWindow.error.fileMissing.content", relPath),
                NotificationType.WARNING,
            )
            .notify(project)
    }
}
