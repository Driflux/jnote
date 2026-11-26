package io.github.driflux.jnote.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.settings.DbPathNotifier
import java.util.concurrent.ExecutionException

internal object RelocateNotifier {
    fun relocated(project: Project, fileName: String, updates: List<AnchorUpdate>) {
        val content = if (updates.isEmpty()) {
            JNoteBundle.message("notification.relocate.none.content")
        } else {
            JNoteBundle.message(
                "notification.relocate.done.content",
                updates.count { it.status == NoteStatus.OK },
                updates.count { it.status == NoteStatus.APPROX },
                updates.count { it.status == NoteStatus.ORPHAN },
            )
        }
        group().createNotification(title("notification.relocate.done.title", fileName), content, NotificationType.INFORMATION)
            .notify(project)
    }

    fun failed(project: Project, fileName: String, error: Throwable) {
        val cause = if (error is ExecutionException) error.cause ?: error else error
        // 알림 내용은 HTML이므로 예외 메시지를 이스케이프한다
        val reason = StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName)
        group().createNotification(
            title("notification.relocate.failed.title", fileName),
            JNoteBundle.message("notification.relocate.failed.content", reason),
            NotificationType.ERROR,
        ).notify(project)
    }

    // 알림 제목도 HTML로 그려진다
    private fun title(key: String, fileName: String) = JNoteBundle.message(key, StringUtil.escapeXmlEntities(fileName))

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
}
