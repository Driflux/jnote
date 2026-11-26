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

/** 지운 노트와 그때 붙어 있던 태그 id. 되돌리기에 필요한 전부다. */
class DeletedNote(val note: Note, val tagIds: List<Long>)

/**
 * 삭제와 되돌리기의 DB 쪽 절차. Swing과 알림을 몰라서 임시 DB로 테스트할 수 있다.
 * 지울 때 쓴 [database]를 그대로 붙들고 있어, 되돌리기는 항상 노트가 있던 DB로 돌아간다.
 */
class NoteDeletion(private val database: Database) {
    private val notes = NoteRepository(database)

    /** 노트와 태그를 읽고 지우는 일을 한 트랜잭션으로 한다. 이미 없는 노트면 null. */
    fun delete(id: Long): CompletableFuture<DeletedNote?> =
        notes.deleteReturningTagIds(id).thenApply { it?.let { (note, tagIds) -> DeletedNote(note, tagIds) } }

    /**
     * 지운 DB에 되살린다. 그 사이 DB 경로가 바뀌어 그 DB가 닫혔다면 다른 DB에 되살리지 않고 오류로 끝낸다.
     */
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
        /** 되돌리기 알림이 살아 있는 시간 */
        const val UNDO_TIMEOUT_MS = 8_000L

        /** 지금 쓰는 DB 기준. DB 경로가 바뀔 수 있어 지울 때마다 만들고, 그 인스턴스로 되돌린다. */
        fun onCurrentDatabase(): NoteDeletion = NoteDeletion(DatabaseService.getInstance().database)
    }
}
