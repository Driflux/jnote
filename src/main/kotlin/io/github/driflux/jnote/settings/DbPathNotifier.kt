package io.github.driflux.jnote.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import io.github.driflux.jnote.JNoteBundle

/** 오류는 삼키지 않고 이유와 해결 방법을 알림으로 보여 준다. */
object DbPathNotifier {
    const val GROUP_ID = "JNote"

    fun invalid(reason: DbPathChange.Reason) =
        error(JNoteBundle.message("notification.dbPath.invalid.title"), JNoteBundle.message(reason.messageKey))

    fun failed(cause: Throwable) = error(
        JNoteBundle.message("notification.dbPath.failed.title"),
        JNoteBundle.message("notification.dbPath.failed.content", cause.message ?: cause.javaClass.simpleName),
    )

    private fun error(title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, NotificationType.ERROR)
            .notify(null)
    }
}
