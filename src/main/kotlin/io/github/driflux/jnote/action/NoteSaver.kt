package io.github.driflux.jnote.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.project.ProjectKeyService
import io.github.driflux.jnote.ui.NoteDraft
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 코드 노트를 EDT 밖에서 저장하고, 저장이 끝나면 UI 스레드에서 [NotesChanged]를 한 번 알린다.
 * 실패는 반환된 future가 예외로 완료되는 것으로 드러난다.
 */
class NoteSaver(
    private val repository: () -> NoteRepository = { NoteRepository(DatabaseService.getInstance().database) },
    private val projectKey: (Project) -> String = { it.service<ProjectKeyService>().resolve().key },
    private val background: Executor = AppExecutorUtil.getAppExecutorService(),
    private val ui: Executor = Executor { ApplicationManager.getApplication().invokeLater(it, ModalityState.nonModal()) },
    private val publish: () -> Unit = {
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
    },
) {
    fun save(project: Project, anchor: CodeAnchor, draft: NoteDraft): CompletableFuture<Note> =
        CompletableFuture.supplyAsync({ projectKey(project) }, background)
            .thenCompose { key -> repository().create(draft.toNewNote(key, anchor)) }
            // DB 스레드에서 알리면 구독자가 DB를 기다리다 막힐 수 있어 UI 스레드로 옮긴다
            .thenApplyAsync({ note -> publish(); note }, ui)

    fun saveFree(draft: NoteDraft): CompletableFuture<Note> =
        // 검증 오류도 future로 드러나고, 검증과 repository() 평가가 EDT 밖에서 일어나도록 background에서 시작한다
        CompletableFuture.supplyAsync({ draft.toFreeNote() }, background)
            .thenCompose { repository().create(it) }
            .thenApplyAsync({ note -> publish(); note }, ui)

    /**
     * 편집한 값으로 노트를 갱신한다(앵커와 태그는 그대로). 그 사이 지워졌다면 null로 끝나며,
     * 목록을 새로 고치도록 이때도 알린다.
     */
    fun update(note: Note, draft: NoteDraft): CompletableFuture<Note?> =
        // 검증이나 저장소 오류가 호출 쪽으로 던져지지 않고 future로 드러나도록 체인 안에서 만든다
        CompletableFuture.completedFuture(draft)
            .thenApply { it.applyTo(note) }
            .thenCompose { repository().update(it) }
            .thenApplyAsync({ updated -> publish(); updated }, ui)
}
