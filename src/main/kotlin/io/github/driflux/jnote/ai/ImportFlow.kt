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
        PlanTask(onApplied, null) { checkCanceled -> ImportPreparation.prepare(sources, checkCanceled) }.queue()
    }

    /**
     * [bound]가 null이면 지금의 DB를 잡는다. 미리보기와 반영은 같은 [NoteImporter]를 써서, 그 사이 DB 경로가 바뀌면
     * 다른 DB에 쓰지 않고 닫힌 DB 오류로 끝난다.
     */
    private inner class PlanTask(
        private val onApplied: (ImportResult) -> Unit,
        private val bound: NoteImporter?,
        private val prepare: (checkCanceled: () -> Unit) -> PreparedImport,
    ) : Task.Backgroundable(project, JNoteBundle.message("task.import.reading"), true) {
        @Volatile
        private var prepared: PreparedImport? = null

        @Volatile
        private var plan: ImportPlan? = null

        @Volatile
        private var target: NoteImporter? = null

        override fun run(indicator: ProgressIndicator) {
            val ready = prepare { indicator.checkCanceled() }
            indicator.checkCanceled()
            val chosen = bound ?: importer()
            plan = if (ready.notes.isEmpty()) ImportPlan(emptyList()) else unwrap { chosen.plan(ready.notes).get() }
            target = chosen
            prepared = ready
        }

        override fun onSuccess() {
            if (project.isDisposed) return
            val ready = prepared ?: return
            val planned = plan ?: return
            val chosen = target ?: return
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
                askApproval(project, planned, ready.problems) && !project.isDisposed -> ApplyTask(chosen, planned, ready, onApplied).queue()
            }
        }

        override fun onThrowable(error: Throwable) = failed(error)
    }

    private inner class ApplyTask(
        private val target: NoteImporter,
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
                result = unwrap { target.apply(plan) { indicator.checkCanceled() }.get() }
            } catch (_: PlanOutdatedException) {
                outdated = true
            }
        }

        override fun onSuccess() {
            if (outdated) {
                // 쓴 것이 없으므로 같은 노트, 같은 DB로 다시 계획해 새 미리보기를 보여 준다
                if (!project.isDisposed) PlanTask(onApplied, target) { prepared }.queue()
                return
            }
            val done = result ?: return
            // 커밋은 끝났으므로 다른 창도 새로 읽도록 프로젝트가 닫혔어도 알린다. 이 프로젝트의 후속 처리만 건너뛴다
            publish()
            if (project.isDisposed) return
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
