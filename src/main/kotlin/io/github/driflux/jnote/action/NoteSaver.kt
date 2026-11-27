package io.github.driflux.jnote.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.anchor.CodeAnchors
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.project.ProjectKeyService
import io.github.driflux.jnote.ui.CodeTargets
import io.github.driflux.jnote.ui.NoteDraft
import io.github.driflux.jnote.ui.NoteRows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 코드 노트를 EDT 밖에서 저장하고, 저장이 끝나면 UI 스레드에서 [NotesChanged]를 한 번 알린다.
 * 실패는 반환된 future가 예외로 완료되는 것으로 드러난다.
 */
class NoteSaver(
    private val repository: () -> NoteRepository = { NoteRepository(DatabaseService.getInstance().database) },
    private val tags: () -> TagRepository = { TagRepository(DatabaseService.getInstance().database) },
    private val projectKey: (Project) -> String = { it.service<ProjectKeyService>().resolve().key },
    private val background: Executor = AppExecutorUtil.getAppExecutorService(),
    private val ui: Executor = Executor { ApplicationManager.getApplication().invokeLater(it, ModalityState.nonModal()) },
    private val publish: () -> Unit = {
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
    },
) {
    fun save(project: Project, anchor: CodeAnchor, draft: NoteDraft): CompletableFuture<Note> =
        CompletableFuture.supplyAsync({ projectKey(project) }, background)
            .thenComposeAsync({ key -> repository().create(draft.toNewNote(key, anchor), draft.tagNames()) }, background)
            // DB 스레드에서 알리면 구독자가 DB를 기다리다 막힐 수 있어 UI 스레드로 옮긴다
            .thenApplyAsync({ note -> publish(); note }, ui)

    fun saveFree(draft: NoteDraft): CompletableFuture<Note> =
        // 검증 오류도 future로 드러나고, 검증과 repository() 평가가 EDT 밖에서 일어나도록 background에서 시작한다.
        // thenCompose는 앞 단계가 이미 끝났으면 호출 스레드에서 돌 수 있어 thenComposeAsync로 background를 강제한다
        CompletableFuture.supplyAsync({ draft.toFreeNote() }, background)
            .thenComposeAsync({ repository().create(it, draft.tagNames()) }, background)
            .thenApplyAsync({ note -> publish(); note }, ui)

    /**
     * 편집한 값으로 노트를 갱신한다(앵커는 그대로, 태그는 [draft]의 목록으로 맞춘다). 그 사이 지워졌다면 null로 끝나며,
     * 목록을 새로 고치도록 이때도 알린다. APPROX 노트는 편집이 곧 위치 확인이므로 [confirmLocation]처럼
     * OK와 지금 코드의 스냅샷으로 바꿔 함께 저장한다. 파일을 읽지 못하면 편집만 저장하고 APPROX로 남긴다.
     */
    fun update(project: Project, note: Note, draft: NoteDraft): CompletableFuture<Note?> =
        // 검증이나 저장소 오류가 호출 쪽으로 던져지지 않고 future로 드러나도록 체인 안에서 만든다
        CompletableFuture.completedFuture(draft)
            .thenApply { it.applyTo(note) }
            .thenApplyAsync({ edited -> if (edited.status == NoteStatus.APPROX) confirmed(project, edited) ?: edited else edited }, background)
            .thenCompose { repository().update(it) }
            // 편집은 태그도 바꿀 수 있으므로 지워진 노트(null)가 아니면 목록을 맞춘다
            .thenCompose { updated -> if (updated == null) CompletableFuture.completedFuture(null) else withTags(updated, draft) }
            .thenApplyAsync({ updated -> publish(); updated }, ui)

    /**
     * 근사 위치(APPROX) 노트를 사용자가 확인한 위치로 굳힌다. 파일이 없거나 줄 범위가 문서 밖이면
     * 예외로 끝나고 DB는 그대로다. 그 사이 지워졌다면 null로 끝난다.
     */
    fun confirmLocation(project: Project, note: Note): CompletableFuture<Note?> =
        CompletableFuture.supplyAsync({
            confirmed(project, note) ?: error(
                JNoteBundle.message("toolWindow.error.confirm.unreadable", note.relPath.orEmpty(), NoteRows.lineRange(note.startLine, note.endLine)),
            )
        }, background)
            .thenCompose { repository().update(it) }
            .thenApplyAsync({ updated -> publish(); updated }, ui)

    /**
     * 지금 코드의 그 줄 범위를 스냅샷으로 삼은 OK 노트. 텍스트는 노트를 만들 때와 같은 규칙(상한 포함)으로 읽는다.
     * 파일이 없거나 범위가 문서 밖이면 null. 파일을 읽으므로 EDT 밖에서 부른다.
     */
    private fun confirmed(project: Project, note: Note): Note? {
        val relPath = note.relPath ?: return null
        val start = note.startLine ?: return null
        val end = note.endLine ?: return null
        val file = CodeTargets.find(project, relPath) ?: return null
        val snapshot = ReadAction.compute<String?, Throwable> {
            FileDocumentManager.getInstance().getDocument(file)?.let { CodeAnchors.fromLines(project, file, it, start, end)?.snapshot }
        } ?: return null
        return note.copy(status = NoteStatus.OK, snapshot = snapshot)
    }

    private fun withTags(note: Note, draft: NoteDraft): CompletableFuture<Note> =
        tags().replaceTags(note.id, draft.tagNames()).thenApply { note }
}
