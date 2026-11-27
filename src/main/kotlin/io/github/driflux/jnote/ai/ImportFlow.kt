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

class ImportFlow(
    private val project: Project,
    private val importer: () -> NoteImporter = { NoteImporter(DatabaseService.getInstance().database) },
    private val askApproval: (Project, ImportPlan, List<ImportProblem>) -> Boolean = ImportPreviewDialog::ask,
    private val publish: () -> Unit = {
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
    },
) {
    fun start(sources: List<ImportSource>, onApplied: (ImportResult) -> Unit = {}) {
        PlanTask(onApplied, null) { checkCanceled -> ImportPreparation.prepare(sources, checkCanceled) }.queue()
    }

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
            plan = if (ready.notes.isEmpty()) ImportPlan(emptyList()) else unwrap { chosen.plan(ready.notes) { indicator.checkCanceled() }.get() }
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
                if (!project.isDisposed) PlanTask(onApplied, target) { prepared }.queue()
                return
            }
            val done = result ?: return
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
