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
}
