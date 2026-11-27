package io.github.driflux.jnote.ai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.settings.DbPathNotifier
import java.util.concurrent.Executor

/**
 * Tool Window가 포커스를 받을 때 inbox를 한 번 훑고, 기다리는 파일이 있으면 알린다. 감시나 폴링은 하지 않는다.
 * 앱 서비스라 창이 여럿이어도 inbox는 하나로 다룬다. plugin.xml에 등록하지 않아 처음 요청 전에는 아무 비용도 없다.
 * 인자는 테스트에서 inbox 위치, 실행 스레드, 알림을 바꿔 끼우기 위한 것이다.
 */
@Service(Service.Level.APP)
class InboxCheck internal constructor(
    private val inbox: () -> Inbox,
    private val background: Executor,
    private val ui: Executor,
    private val present: (Project, List<InboxFile>) -> Notification,
) {
    constructor() : this(
        { Inbox() },
        AppExecutorUtil.getAppExecutorService(),
        Executor { ApplicationManager.getApplication().invokeLater(it, ModalityState.nonModal()) },
        ::notifyWaiting,
    )

    private val lock = Any()
    private var scanning = false
    private var rescanFor: Project? = null

    // 아래 둘은 EDT에서만 읽고 쓴다
    private var notified: List<InboxFile>? = null
    private var notification: Notification? = null

    /** EDT에서 부른다. 이미 훑는 중이면 끝난 뒤 한 번 더 훑도록 예약만 한다(예약은 한 번까지 합친다). */
    fun request(project: Project) {
        synchronized(lock) {
            if (scanning) {
                rescanFor = project
                return
            }
            scanning = true
        }
        scan(project)
    }

    private fun scan(project: Project) {
        background.execute {
            val files = try {
                inbox().scan()
            } catch (e: Exception) {
                thisLogger().warn("inbox scan failed", e)
                null
            }
            ui.execute {
                if (files != null) update(project, files)
                val next = synchronized(lock) {
                    rescanFor.also {
                        rescanFor = null
                        if (it == null) scanning = false
                    }
                }
                if (next != null) scan(next)
            }
        }
    }

    private fun update(project: Project, files: List<InboxFile>) {
        val current = notification
        if (files.isEmpty()) {
            current?.expire()
            notification = null
            notified = null
            return
        }
        // 같은 파일 묶음(이름, 크기, 수정 시각)을 이미 알렸고 그 알림이 떠 있으면 다시 알리지 않는다
        if (files == notified && current != null && !current.isExpired) return
        if (project.isDisposed) return
        current?.expire()
        notified = files
        notification = present(project, files)
    }

    companion object {
        fun getInstance(): InboxCheck = service()

        private fun notifyWaiting(project: Project, files: List<InboxFile>): Notification =
            NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
                .createNotification(
                    JNoteBundle.message("notification.inbox.waiting.title"),
                    JNoteBundle.message("notification.inbox.waiting.content", files.size),
                    NotificationType.INFORMATION,
                )
                .also { it.notify(project) }
    }
}
