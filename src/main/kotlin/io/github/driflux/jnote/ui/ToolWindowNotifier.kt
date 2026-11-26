package io.github.driflux.jnote.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.settings.DbPathNotifier
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object ToolWindowNotifier {
    fun loadFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.load", cause)

    fun deleteFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.delete", cause)

    fun restoreFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.restore", cause)

    fun goToFailed(project: Project, cause: Throwable) {
        // 알림 내용은 HTML이므로 예외 메시지를 이스케이프한다
        val reason = StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName)
        NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
            .createNotification(
                JNoteBundle.message("toolWindow.error.goTo.title"),
                JNoteBundle.message("toolWindow.error.goTo.content", reason),
                NotificationType.ERROR,
            )
            .notify(project)
    }

    fun noPath(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
            .createNotification(
                JNoteBundle.message("toolWindow.error.goTo.title"),
                JNoteBundle.message("toolWindow.error.noPath.content"),
                NotificationType.WARNING,
            )
            .notify(project)
    }

    /** 재배치는 이후 이슈에서 다루므로 지금은 파일이 없다는 사실만 알린다. */
    fun fileMissing(project: Project, relPath: String) {
        group().createNotification(
            JNoteBundle.message("toolWindow.error.fileMissing.title"),
            JNoteBundle.message("toolWindow.error.fileMissing.content", StringUtil.escapeXmlEntities(relPath)),
            NotificationType.WARNING,
        ).notify(project)
    }

    /**
     * "노트를 삭제했습니다" 알림. [onUndo]는 되돌리기를 누를 때, [expireAfterMs] 뒤에는 알림이 사라진다.
     * 만료 작업은 알림이 먼저 사라지면(되돌리기, 닫기) 취소한다. [schedule]은 테스트에서 바꿔 끼운다.
     */
    fun deleted(
        project: Project,
        expireAfterMs: Long = NoteDeletion.UNDO_TIMEOUT_MS,
        schedule: (Runnable, Long) -> ScheduledFuture<*> = { task, delayMs ->
            AppExecutorUtil.getAppScheduledExecutorService().schedule(task, delayMs, TimeUnit.MILLISECONDS)
        },
        onUndo: () -> Unit,
    ): Notification {
        val notification = group().createNotification(
            JNoteBundle.message("toolWindow.notification.deleted"),
            NotificationType.INFORMATION,
        )
        notification.addAction(NotificationAction.createSimpleExpiring(JNoteBundle.message("toolWindow.notification.undo")) { onUndo() })
        notification.notify(project)
        val expiry = schedule({ notification.expire() }, expireAfterMs)
        notification.whenExpired { expiry.cancel(false) }
        return notification
    }

    private fun failed(project: Project, keyPrefix: String, cause: Throwable) {
        group().createNotification(
            JNoteBundle.message("$keyPrefix.title"),
            JNoteBundle.message("$keyPrefix.content", StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName)),
            NotificationType.ERROR,
        ).notify(project)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
}
