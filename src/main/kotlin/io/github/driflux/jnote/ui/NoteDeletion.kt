package io.github.driflux.jnote.ui

import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.DatabaseClosedException
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.Note
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException

class DeletedNote(val note: Note, val tagIds: List<Long>)

class NoteDeletion(private val database: Database) {
    private val notes = NoteRepository(database)

        fun delete(id: Long, uid: String): CompletableFuture<DeletedNote?> =
        notes.deleteReturningTagIds(id, uid).thenApply { it?.let { (note, tagIds) -> DeletedNote(note, tagIds) } }

        fun undo(deleted: DeletedNote): CompletableFuture<Note> {
        val restored = try {
            notes.restore(deleted.note, deleted.tagIds)
        } catch (e: RejectedExecutionException) {
            CompletableFuture.failedFuture(e)
        }
        return restored.exceptionallyCompose { error ->
            val cause = if (error is CompletionException) error.cause ?: error else error
            if (cause is DatabaseClosedException || cause is RejectedExecutionException) {
                CompletableFuture.failedFuture(IllegalStateException(JNoteBundle.message("toolWindow.error.restore.databaseSwitched"), cause))
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
    }

    companion object {
        const val UNDO_TIMEOUT_MS = 8_000L

                fun onCurrentDatabase(): NoteDeletion = NoteDeletion(DatabaseService.getInstance().database)
    }
}
