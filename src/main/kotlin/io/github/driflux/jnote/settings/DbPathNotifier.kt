package io.github.driflux.jnote.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import io.github.driflux.jnote.JNoteBundle
import java.nio.file.FileAlreadyExistsException

/** 오류는 삼키지 않고 이유와 해결 방법을 알림으로 보여 준다. */
object DbPathNotifier {
    const val GROUP_ID = "JNote"

    fun invalid(reason: DbPathChange.Reason) =
        error(JNoteBundle.message("notification.dbPath.invalid.title"), JNoteBundle.message(reason.messageKey))

    fun failed(cause: Throwable) = error(
        JNoteBundle.message("notification.dbPath.failed.title"),
        when (cause) {
            // 새 위치의 파일은 덮어쓰지 않으므로, 쓰기 권한이 아니라 그 파일이 원인이라고 알려 준다
            is FileAlreadyExistsException -> JNoteBundle.message("notification.dbPath.targetExists.content", cause.file)
            else -> JNoteBundle.message("notification.dbPath.failed.content", cause.message ?: cause.javaClass.simpleName)
        },
    )

    private fun error(title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, NotificationType.ERROR)
            .notify(null)
    }
}
