package io.github.driflux.jnote.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.project.ProjectKeyService
import io.github.driflux.jnote.relocate.FileRelocation
import io.github.driflux.jnote.ui.CodeTargets
import io.github.driflux.jnote.ui.ToolWindowNotifier

/**
 * 열린 에디터의 저장하지 않은 내용까지 본다. 찾는 도중 취소하면 DB를 바꾸지 않는다.
 * 인자는 테스트에서 저장소와 프로젝트 키를 바꿔 끼우기 위한 것이다.
 */
class RelocateNotesTask(
    project: Project,
    private val relPath: String,
    private val repository: () -> NoteRepository = { NoteRepository(DatabaseService.getInstance().database) },
    private val projectKey: (Project) -> String = { it.service<ProjectKeyService>().resolve().key },
) : Task.Backgroundable(project, JNoteBundle.message("task.relocate.title", fileName(relPath)), true) {
    private sealed interface Outcome {
        data class Relocated(val updates: List<AnchorUpdate>, val changed: Int) : Outcome

        data object FileMissing : Outcome
    }

    // run이 백그라운드에서 쓰고, 끝난 뒤 onFinished가 EDT에서 읽는다. 쓰기 전에 끝나면(취소, 실패) null이다
    @Volatile
    private var outcome: Outcome? = null

    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        val file = CodeTargets.find(project, relPath)
        if (file == null) {
            outcome = Outcome.FileMissing
            return
        }
        val repo = repository()
        val notes = repo.listByPath(projectKey(project), relPath).get()
        val text = ReadAction.compute<String?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file)?.text }
            ?: error("cannot read $relPath as text")
        val updates = FileRelocation.relocate(notes, text.lines(), isCancelled = { indicator.isCanceled }) { done, total ->
            indicator.fraction = done.toDouble() / total
        } ?: throw ProcessCanceledException()
        outcome = Outcome.Relocated(updates, repo.updateAnchors(updates).get())
    }

    // 쓰기가 시작된 뒤 누른 취소는 쓰기를 멈추지 못하는데 플랫폼은 onCancel을 부르므로, onSuccess가 아니라 여기서 알린다
    override fun onFinished() {
        when (val done = outcome) {
            null -> Unit
            is Outcome.Relocated -> {
                if (done.changed > 0) {
                    ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
                }
                RelocateNotifier.relocated(project, fileName(relPath), done.updates)
            }
            Outcome.FileMissing -> ToolWindowNotifier.fileMissing(project, relPath)
        }
    }

    override fun onThrowable(error: Throwable) = RelocateNotifier.failed(project, fileName(relPath), error)

    private companion object {
        fun fileName(relPath: String) = relPath.substringAfterLast('/')
    }
}
