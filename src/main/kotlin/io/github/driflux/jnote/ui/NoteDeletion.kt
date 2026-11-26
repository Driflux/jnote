package io.github.driflux.jnote.ui

import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.Note
import java.util.concurrent.CompletableFuture

/** 지운 노트와 그때 붙어 있던 태그 id. 되돌리기에 필요한 전부다. */
class DeletedNote(val note: Note, val tagIds: List<Long>)

/** 삭제와 되돌리기의 DB 쪽 절차. Swing과 알림을 몰라서 임시 DB로 테스트할 수 있다. */
class NoteDeletion(private val notes: NoteRepository, private val tags: TagRepository) {
    /** 지우기 전에 노트와 태그를 읽어 둔다. 이미 없는 노트면 null. */
    fun delete(id: Long): CompletableFuture<DeletedNote?> = notes.get(id).thenCompose { note ->
        if (note == null) {
            CompletableFuture.completedFuture(null)
        } else {
            tags.tagsOf(id).thenCompose { attached ->
                notes.delete(id).thenApply { removed -> if (removed) DeletedNote(note, attached.map { it.id }) else null }
            }
        }
    }

    fun undo(deleted: DeletedNote): CompletableFuture<Note> = notes.restore(deleted.note, deleted.tagIds)

    companion object {
        /** 되돌리기 알림이 살아 있는 시간 */
        const val UNDO_TIMEOUT_MS = 8_000L

        /** 지금 쓰는 DB 기준. DB 경로가 바뀔 수 있어 쓸 때마다 만든다. */
        fun onCurrentDatabase(): NoteDeletion {
            val db = DatabaseService.getInstance().database
            return NoteDeletion(NoteRepository(db), TagRepository(db))
        }
    }
}
