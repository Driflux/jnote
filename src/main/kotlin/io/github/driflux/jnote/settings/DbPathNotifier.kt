package io.github.driflux.jnote.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import io.github.driflux.jnote.JNoteBundle
import java.nio.file.FileAlreadyExistsException

object DbPathNotifier {
    const val GROUP_ID = "JNote"

    fun invalid(reason: DbPathChange.Reason) =
        show(JNoteBundle.message("notification.dbPath.invalid.title"), JNoteBundle.message(reason.messageKey))

    fun busy() = show(
        JNoteBundle.message("notification.dbPath.invalid.title"),
        JNoteBundle.message("notification.dbPath.busy.content"),
        NotificationType.WARNING,
    )

    fun failed(cause: Throwable) {
        if (cause is DbPathSwitcher.OldDatabaseNotClosed) return closeFailed(cause)
        show(
            JNoteBundle.message("notification.dbPath.failed.title"),
            when (cause) {
                // 새 위치의 파일은 덮어쓰지 않으므로, 쓰기 권한이 아니라 그 파일이 원인이라고 알려 준다
                is FileAlreadyExistsException -> JNoteBundle.message("notification.dbPath.targetExists.content", cause.file)
                is DbPathSwitcher.UnusableTarget -> {
                    // SQLite가 준 자세한 이유는 사용자가 고칠 수 있는 정보가 아니라 로그에만 남긴다
                    thisLogger().warn("Cannot open ${cause.target} as a JNote database", cause.cause)
                    JNoteBundle.message("notification.dbPath.unusableTarget.content", cause.target)
                }
                else -> JNoteBundle.message("notification.dbPath.failed.content", reason(cause))
            },
        )
    }

    // 경로는 이미 바뀌었으므로 "이전 데이터베이스를 계속 사용한다"는 실패 문구 대신 새 위치를 쓰고 있다고 알린다
    private fun closeFailed(cause: DbPathSwitcher.OldDatabaseNotClosed) = show(
        JNoteBundle.message("notification.dbPath.closeFailed.title"),
        JNoteBundle.message("notification.dbPath.closeFailed.content", cause.target, reason(cause.cause)),
        NotificationType.WARNING,
    )

    private fun reason(cause: Throwable): String = cause.message ?: cause.javaClass.simpleName

    private fun show(title: String, content: String, type: NotificationType = NotificationType.ERROR) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(null)
    }
}
