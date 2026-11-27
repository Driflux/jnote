package io.github.driflux.jnote.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.text.StringUtil
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
                is FileAlreadyExistsException -> JNoteBundle.message("notification.dbPath.targetExists.content", escaped(cause.file))
                is DbPathSwitcher.UnusableTarget -> {
                    thisLogger().warn("Cannot open ${cause.target} as a JNote database", cause.cause)
                    JNoteBundle.message("notification.dbPath.unusableTarget.content", escaped(cause.target))
                }
                else -> JNoteBundle.message("notification.dbPath.failed.content", reason(cause))
            },
        )
    }

    private fun closeFailed(cause: DbPathSwitcher.OldDatabaseNotClosed) = show(
        JNoteBundle.message("notification.dbPath.closeFailed.title"),
        JNoteBundle.message("notification.dbPath.closeFailed.content", escaped(cause.target), reason(cause.cause)),
        NotificationType.WARNING,
    )

    private fun reason(cause: Throwable): String = escaped(cause.message ?: cause.javaClass.simpleName)

    private fun escaped(value: Any?): String = StringUtil.escapeXmlEntities(value.toString())

    private fun show(title: String, content: String, type: NotificationType = NotificationType.ERROR) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(null)
    }
}
