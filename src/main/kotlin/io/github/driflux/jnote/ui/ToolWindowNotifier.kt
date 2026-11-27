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

    fun editFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.edit", cause)

    fun deleteFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.delete", cause)

    fun restoreFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.restore", cause)

    fun confirmFailed(project: Project, cause: Throwable) {
        val reason = StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName)
        group().createNotification(
            JNoteBundle.message("toolWindow.error.confirm.title"),
            JNoteBundle.message("toolWindow.error.confirm.content", reason),
            NotificationType.ERROR,
        ).notify(project)
    }

    fun copyFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.copy", cause)
    fun locationMoved(project: Project, textSaved: Boolean) {
        group().createNotification(
            JNoteBundle.message("toolWindow.locationMoved.title"),
            JNoteBundle.message(if (textSaved) "toolWindow.locationMoved.contentEdited" else "toolWindow.locationMoved.content"),
            NotificationType.WARNING,
        ).notify(project)
    }

    fun renameTagFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.tagRename", cause)

    fun deleteTagFailed(project: Project, cause: Throwable) = failed(project, "toolWindow.error.tagDelete", cause)

    fun tagExists(project: Project, name: String) {
        group().createNotification(
            JNoteBundle.message("toolWindow.error.tagExists.title"),
            JNoteBundle.message("toolWindow.error.tagExists.content", StringUtil.escapeXmlEntities(name)),
            NotificationType.WARNING,
        ).notify(project)
    }

    fun goToFailed(project: Project, cause: Throwable) {
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

    fun fileMissing(project: Project, relPath: String) {
        group().createNotification(
            JNoteBundle.message("toolWindow.error.fileMissing.title"),
            JNoteBundle.message("toolWindow.error.fileMissing.content", StringUtil.escapeXmlEntities(relPath)),
            NotificationType.WARNING,
        ).notify(project)
    }

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
