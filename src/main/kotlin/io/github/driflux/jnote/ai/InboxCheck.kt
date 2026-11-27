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
 * Tool Window가 활성화될 때 inbox를 한 번 훑고, 기다리는 파일이 있으면 그 프로젝트에 알린다. 감시나 폴링은 하지 않는다.
 * 앱 서비스라 창이 여럿이어도 훑기는 한 번에 하나다. plugin.xml에 등록하지 않아 처음 요청 전에는 아무 비용도 없다.
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

    private class Shown(val files: List<InboxFile>, val notification: Notification)

    private val lock = Any()
    private var scanning = false
    private var rescanFor: Project? = null

    // EDT에서만 읽고 쓴다. 알림은 프로젝트(창)마다 따로 떠야 그 창에서 Review를 누를 수 있다
    private val shown = HashMap<Project, Shown>()

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
        try {
            background.execute {
                val files = try {
                    inbox().scan()
                } catch (e: Exception) {
                    thisLogger().warn("inbox scan failed", e)
                    null
                }
                ui.execute {
                    try {
                        if (files != null) update(project, files)
                    } finally {
                        next()?.let(::scan)
                    }
                }
            }
        } catch (e: Exception) {
            // 실행기가 거절하면 다음 요청이 새로 시작할 수 있도록 상태를 푼다
            synchronized(lock) {
                scanning = false
                rescanFor = null
            }
            throw e
        }
    }

    /** 예약된 재확인이 있으면 그 프로젝트를, 없으면 훑기를 끝낸다. */
    private fun next(): Project? = synchronized(lock) {
        rescanFor.also {
            rescanFor = null
            if (it == null) scanning = false
        }
    }

    private fun update(project: Project, files: List<InboxFile>) {
        shown.keys.removeIf { it.isDisposed }
        val current = shown[project]
        if (files.isEmpty()) {
            current?.notification?.expire()
            shown.remove(project)
            return
        }
        // 이 창에 같은 파일 묶음(이름, 크기, 수정 시각)을 이미 알렸고 그 알림이 떠 있으면 다시 알리지 않는다
        if (current != null && current.files == files && !current.notification.isExpired) return
        if (project.isDisposed) return
        current?.notification?.expire()
        shown[project] = Shown(files, present(project, files))
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
