package io.github.driflux.jnote.gutter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Condition
import com.intellij.openapi.project.Project
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.action.NoteSaver
import io.github.driflux.jnote.db.Database
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

internal class GutterNotePreviewActions(project: Project, database: Database) : PreviewActions {
    private val actions = GutterNoteActions(
        project,
        database,
        NoteSaver(repository = { NoteRepository(database) }),
    )

    override fun edit(noteId: Long, uid: String) {
        actions.edit(noteId, uid)
    }

    override fun open(noteId: Long, uid: String) {
        actions.open(noteId, uid)
    }

    override fun delete(noteId: Long, uid: String) {
        actions.delete(noteId, uid)
    }
}

internal class GutterNoteActions(
    private val project: Project,
    private val database: Database,
    private val saver: NoteSaver = NoteSaver(),
    private val askEdit: (Project, Note, List<String>) -> NoteDraft? = AddNoteDialog::edit,
) {
    fun open(noteId: Long, uid: String): CompletableFuture<*> {
        val done = CompletableFuture<Unit>()
        NoteRepository(database).get(noteId).whenComplete { note, error ->
            onEdt(done) {
                try {
                    val current = DatabaseService.getInstance().current().database === database
                    if (error == null && note?.uid == uid && current) NoteSelection.request(project, noteId)
                } finally {
                    done.complete(Unit)
                }
            }
        }
        return done
    }

    fun edit(noteId: Long, uid: String): CompletableFuture<*> {
        val done = CompletableFuture<Unit>()
        NoteRepository(database).get(noteId)
            .thenCompose { note ->
                if (note == null || note.uid != uid) CompletableFuture.completedFuture(null) else TagRepository(database).tagsOf(noteId).thenApply { note to it }
            }
            .whenComplete { loaded, readError ->
                onEdt(done) {
                    try {
                        when {
                            readError != null -> {
                                ToolWindowNotifier.editFailed(project, unwrap(readError))
                                done.complete(Unit)
                            }
                            loaded == null -> {
                                publishChanged()
                                done.complete(Unit)
                            }
                            else -> askAndSave(loaded.first, loaded.second.map { it.name }, done)
                        }
                    } catch (e: Throwable) {
                        done.completeExceptionally(e)
                        throw e
                    }
                }
            }
        return done
    }

    fun delete(noteId: Long, uid: String): CompletableFuture<*> {
        val deletion = NoteDeletion(database)
        return deletion.delete(noteId, uid).whenComplete { deleted, error ->
            onEdt {
                when {
                    error != null -> ToolWindowNotifier.deleteFailed(project, unwrap(error))
                    deleted != null -> {
                        publishChanged()
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

    private fun onEdt(done: CompletableFuture<*>? = null, block: () -> Unit) = ApplicationManager.getApplication().invokeLater(
        { block() },
        ModalityState.defaultModalityState(),
        Condition<Any?> { project.isDisposed.also { if (it) done?.cancel(false) } },
    )
}
