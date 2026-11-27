package io.github.driflux.jnote.gutter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.action.NoteSaver
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.ui.AddNoteDialog
import io.github.driflux.jnote.ui.DeletedNote
import io.github.driflux.jnote.ui.NoteDeletion
import io.github.driflux.jnote.ui.NoteDraft
import io.github.driflux.jnote.ui.NoteSelection
import io.github.driflux.jnote.ui.ToolWindowNotifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** 미리보기 버튼을 [GutterNoteActions]에 잇는다. 결과 future는 알림이 처리하므로 기다리지 않는다. */
internal class GutterNotePreviewActions(project: Project) : PreviewActions {
    private val actions = GutterNoteActions(project)

    override fun edit(noteId: Long) {
        actions.edit(noteId)
    }

    override fun open(noteId: Long) = actions.open(noteId)

    override fun delete(noteId: Long) {
        actions.delete(noteId)
    }
}

/**
 * 미리보기의 수정, Tool Window에서 열기, 삭제. Tool Window 패널과 같은 협력 객체(저장기, 삭제 절차, 알림)를 쓰며,
 * DB는 DB 스레드에서만 읽고 쓰고 다이얼로그와 알림은 EDT에서 띄운다. 미리보기는 캐시된 값만 갖고 있으므로 수정은 노트를 다시 읽어 온다.
 */
internal class GutterNoteActions(
    private val project: Project,
    private val saver: NoteSaver = NoteSaver(),
    private val askEdit: (Project, Note, List<String>) -> NoteDraft? = AddNoteDialog::edit,
) {
    /** EDT에서 부른다. */
    fun open(noteId: Long) = NoteSelection.request(project, noteId)

    fun edit(noteId: Long): CompletableFuture<*> {
        val done = CompletableFuture<Unit>()
        val database = DatabaseService.getInstance().database
        NoteRepository(database).get(noteId)
            .thenCompose { note ->
                if (note == null) CompletableFuture.completedFuture(null) else TagRepository(database).tagsOf(noteId).thenApply { note to it }
            }
            .whenComplete { loaded, readError ->
                onEdt {
                    when {
                        readError != null -> {
                            ToolWindowNotifier.editFailed(project, unwrap(readError))
                            done.complete(Unit)
                        }
                        // 그 사이 지워진 노트라면 목록만 새로 고친다
                        loaded == null -> {
                            publishChanged()
                            done.complete(Unit)
                        }
                        else -> askAndSave(loaded.first, loaded.second.map { it.name }, done)
                    }
                }
            }
        return done
    }

    fun delete(noteId: Long): CompletableFuture<*> {
        val deletion = NoteDeletion.onCurrentDatabase()
        return deletion.delete(noteId).whenComplete { deleted, error ->
            onEdt {
                when {
                    error != null -> ToolWindowNotifier.deleteFailed(project, unwrap(error))
                    deleted != null -> {
                        publishChanged()
                        // 되돌리기는 지운 그 DB로 가야 하므로 같은 NoteDeletion을 알림 동작까지 들고 간다
                        ToolWindowNotifier.deleted(project) { undo(deletion, deleted) }
                    }
                    else -> publishChanged()
                }
            }
        }
    }

    private fun askAndSave(note: Note, tags: List<String>, done: CompletableFuture<Unit>) {
        val draft = askEdit(project, note, tags)
        if (draft == null) {
            done.complete(Unit)
            return
        }
        saver.update(project, note, draft).whenComplete { _, error ->
            if (error != null) onEdt { ToolWindowNotifier.editFailed(project, unwrap(error)) }
            done.complete(Unit)
        }
    }

    private fun undo(deletion: NoteDeletion, deleted: DeletedNote) {
        deletion.undo(deleted).whenComplete { _, error ->
            onEdt { if (error == null) publishChanged() else ToolWindowNotifier.restoreFailed(project, unwrap(error)) }
        }
    }

    private fun publishChanged() = ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()

    private fun unwrap(error: Throwable) = (error as? CompletionException)?.cause ?: error

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({ block() }, project.disposed)
}
