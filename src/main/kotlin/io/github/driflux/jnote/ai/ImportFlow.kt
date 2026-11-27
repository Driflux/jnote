package io.github.driflux.jnote.ai

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.settings.DbPathNotifier
import java.util.concurrent.ExecutionException

/**
 * 읽기·해석·계획(백그라운드) → 미리보기(EDT) → 반영(백그라운드) 순서로 가져온다. 두 백그라운드 작업 모두 취소할 수 있다.
 * 미리보기 뒤 DB가 바뀌어 반영이 [PlanOutdatedException]으로 끝나면 다시 계획해 미리보기를 보여 준다.
 * 인자는 테스트에서 DB, 대화상자, 알림 발행을 바꿔 끼우기 위한 것이다.
 */
class ImportFlow(
    private val project: Project,
    private val importer: () -> NoteImporter = { NoteImporter(DatabaseService.getInstance().database) },
    private val askApproval: (Project, ImportPlan, List<ImportProblem>) -> Boolean = ImportPreviewDialog::ask,
    private val publish: () -> Unit = {
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
    },
) {
    /** [onApplied]는 반영을 마쳤거나 반영할 것이 없을 때 EDT에서 한 번 불린다. 취소, 실패, 거절 때는 불리지 않는다. */
    fun start(sources: List<ImportSource>, onApplied: (ImportResult) -> Unit = {}) {
        PlanTask(onApplied) { ImportPreparation.prepare(sources) }.queue()
    }

    private inner class PlanTask(
        private val onApplied: (ImportResult) -> Unit,
        private val prepare: () -> PreparedImport,
    ) : Task.Backgroundable(project, JNoteBundle.message("task.import.reading"), true) {
        @Volatile
        private var prepared: PreparedImport? = null

        @Volatile
        private var plan: ImportPlan? = null

        override fun run(indicator: ProgressIndicator) {
            val ready = prepare()
            indicator.checkCanceled()
            prepared = ready
            plan = if (ready.notes.isEmpty()) ImportPlan(emptyList()) else unwrap { importer().plan(ready.notes).get() }
        }

        override fun onSuccess() {
            val ready = prepared ?: return
            val planned = plan ?: return
            when {
                planned.creates.isEmpty() && planned.updates.isEmpty() && ready.notes.isEmpty() && ready.problems.isNotEmpty() ->
                    notify("notification.import.failed.title", ImportMessages.problemList(ready.problems), NotificationType.ERROR)
                planned.creates.isEmpty() && planned.updates.isEmpty() -> {
                    notify(
                        "notification.import.nothing.title",
                        join(JNoteBundle.message("notification.import.nothing.content"), ImportMessages.problemList(ready.problems)),
                        if (ready.problems.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
                    )
                    onApplied(ImportResult(0, 0, planned.unchanged.size))
                }
                project.isDisposed -> Unit
                askApproval(project, planned, ready.problems) -> ApplyTask(planned, ready, onApplied).queue()
            }
        }

        override fun onThrowable(error: Throwable) = failed(error)
    }

    private inner class ApplyTask(
        private val plan: ImportPlan,
        private val prepared: PreparedImport,
        private val onApplied: (ImportResult) -> Unit,
    ) : Task.Backgroundable(project, JNoteBundle.message("task.import.applying"), true) {
        @Volatile
        private var result: ImportResult? = null

        @Volatile
        private var outdated = false

        override fun run(indicator: ProgressIndicator) {
            try {
                result = unwrap { importer().apply(plan) { indicator.checkCanceled() }.get() }
            } catch (_: PlanOutdatedException) {
                outdated = true
            }
        }

        override fun onSuccess() {
            if (outdated) {
                // 쓴 것이 없으므로 같은 노트로 다시 계획해 새 미리보기를 보여 준다
                PlanTask(onApplied) { prepared }.queue()
                return
            }
            val done = result ?: return
            publish()
            val content = join(
                JNoteBundle.message("notification.import.done.content", done.created, done.updated, done.unchanged),
                ImportMessages.problemList(prepared.problems),
            )
            notify(
                "notification.import.done.title",
                content,
                if (prepared.problems.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
            )
            onApplied(done)
        }

        override fun onThrowable(error: Throwable) = failed(error)
    }

    // DB 스레드에서 난 취소와 예외를 작업 스레드의 것으로 되돌린다
    private fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun failed(error: Throwable) {
        if (error is ProcessCanceledException) return
        val reason = ImportMessages.escape(error.message ?: error.javaClass.simpleName)
        notify("notification.import.failed.title", JNoteBundle.message("notification.import.failed.content", reason), NotificationType.ERROR)
    }

    private fun join(head: String, tail: String) = if (tail.isEmpty()) head else "$head<br>$tail"

    private fun notify(titleKey: String, content: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID)
            .createNotification(JNoteBundle.message(titleKey), content, type)
            .notify(project)
    }
}
