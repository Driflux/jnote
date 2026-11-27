package io.github.driflux.jnote.ai

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.settings.DbPathNotifier
import java.nio.file.Path
import java.util.concurrent.ExecutionException

/** 취소 가능한 백그라운드 내보내기. 인자 [exporter]는 테스트에서 DB를 바꿔 끼우기 위한 것이다. */
class ExportNotesTask(
    project: Project,
    private val target: Path,
    private val selectedUids: Set<String>?,
    private val exporter: () -> NoteExporter = { NoteExporter(DatabaseService.getInstance().database) },
) : Task.Backgroundable(project, JNoteBundle.message("task.export.title"), true) {
    // run이 백그라운드에서 쓰고 onSuccess가 EDT에서 읽는다
    @Volatile
    private var written = 0

    override fun run(indicator: ProgressIndicator) {
        written = exporter().export(target, selectedUids) { indicator.checkCanceled() }
    }

    override fun onSuccess() {
        group().createNotification(
            JNoteBundle.message("notification.export.done.title"),
            JNoteBundle.message("notification.export.done.content", written, StringUtil.escapeXmlEntities(target.toString())),
            NotificationType.INFORMATION,
        ).notify(project)
    }

    override fun onThrowable(error: Throwable) {
        val cause = if (error is ExecutionException) error.cause ?: error else error
        // 알림 내용은 HTML이므로 예외 메시지를 이스케이프한다
        val reason = StringUtil.escapeXmlEntities(cause.message ?: cause.javaClass.simpleName)
        group().createNotification(
            JNoteBundle.message("notification.export.failed.title"),
            JNoteBundle.message("notification.export.failed.content", reason),
            NotificationType.ERROR,
        ).notify(project)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
}
