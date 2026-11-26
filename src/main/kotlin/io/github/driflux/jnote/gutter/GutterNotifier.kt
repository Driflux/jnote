package io.github.driflux.jnote.gutter

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationsConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.settings.DbPathNotifier

/** 거터가 보여 주는 알림. 알림 내용은 HTML이므로 경로와 예외 메시지를 이스케이프한다. */
internal object GutterNotifier {
    /** 위치 저장 실패만 담는 그룹. 이 그룹만 끄면 다른 JNote 알림은 그대로 보인다. */
    const val POSITION_SAVE_GROUP_ID = "JNote.PositionSave"

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
        NotificationGroupManager.getInstance().getNotificationGroup(POSITION_SAVE_GROUP_ID)
            .createNotification(
                JNoteBundle.message("gutter.error.save.title"),
                JNoteBundle.message(
                    "gutter.error.save.content",
                    StringUtil.escapeXmlEntities(relPath),
                    StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName),
                ),
                NotificationType.ERROR,
            )
            // 표시 유형을 NONE으로 바꾸면 Settings | Appearance & Behavior | Notifications에서 다시 켤 수 있다
            .addAction(
                NotificationAction.createSimpleExpiring(JNoteBundle.message("gutter.error.save.dontShowAgain")) {
                    NotificationsConfiguration.getNotificationsConfiguration()
                        .setDisplayType(POSITION_SAVE_GROUP_ID, NotificationDisplayType.NONE)
                },
            )
            .notify(project)
    }
}
