package io.github.driflux.jnote.gutter

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.settings.DbPathNotifier

/** 거터가 보여 주는 알림. 알림 내용은 HTML이므로 경로와 예외 메시지를 이스케이프한다. */
internal object GutterNotifier {
    fun loadFailed(project: Project, relPath: String, cause: Throwable) {
        NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
            .createNotification(
                JNoteBundle.message("gutter.error.load.title"),
                JNoteBundle.message(
                    "gutter.error.load.content",
                    StringUtil.escapeXmlEntities(relPath),
                    StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName),
                ),
                NotificationType.ERROR,
            )
            .notify(project)
    }

    fun saveFailed(project: Project, relPath: String, cause: Throwable) {
        NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
            .createNotification(
                JNoteBundle.message("gutter.error.save.title"),
                JNoteBundle.message(
                    "gutter.error.save.content",
                    StringUtil.escapeXmlEntities(relPath),
                    StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName),
                ),
                NotificationType.ERROR,
            )
            .notify(project)
    }
}
