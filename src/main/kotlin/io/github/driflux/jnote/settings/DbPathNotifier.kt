package io.github.driflux.jnote.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import io.github.driflux.jnote.JNoteBundle
import java.nio.file.FileAlreadyExistsException

/** 오류는 삼키지 않고 이유와 해결 방법을 알림으로 보여 준다. */
object DbPathNotifier {
    const val GROUP_ID = "JNote"

    fun invalid(reason: DbPathChange.Reason) =
        show(JNoteBundle.message("notification.dbPath.invalid.title"), JNoteBundle.message(reason.messageKey))

    fun busy() = show(
        JNoteBundle.message("notification.dbPath.invalid.title"),
        JNoteBundle.message("notification.dbPath.busy.content"),
        NotificationType.WARNING,
    )

    fun failed(cause: Throwable) = show(
        JNoteBundle.message("notification.dbPath.failed.title"),
        when (cause) {
            // 새 위치의 파일은 덮어쓰지 않으므로, 쓰기 권한이 아니라 그 파일이 원인이라고 알려 준다
            is FileAlreadyExistsException -> JNoteBundle.message("notification.dbPath.targetExists.content", cause.file)
            is DbPathSwitcher.UnusableTarget -> {
                // SQLite가 준 자세한 이유는 사용자가 고칠 수 있는 정보가 아니라 로그에만 남긴다
                thisLogger().warn("Cannot open ${cause.target} as a JNote database", cause.cause)
                JNoteBundle.message("notification.dbPath.unusableTarget.content", cause.target)
            }
            else -> JNoteBundle.message("notification.dbPath.failed.content", cause.message ?: cause.javaClass.simpleName)
        },
    )

    private fun show(title: String, content: String, type: NotificationType = NotificationType.ERROR) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(null)
    }
}
