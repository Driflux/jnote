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
import io.github.driflux.jnote.db.SavedNote
import io.github.driflux.jnote.model.LocationConfirmation
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.project.ProjectKeyService
import io.github.driflux.jnote.ui.CodeTargets
import io.github.driflux.jnote.ui.NoteDraft
import io.github.driflux.jnote.ui.NoteRows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

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
            .thenComposeAsync({ key -> repository().create(draft.toNewNote(key, anchor), draft.tagNames()) }, background)
            .thenApplyAsync({ note -> publish(); note }, ui)

    fun saveFree(draft: NoteDraft): CompletableFuture<Note> =
        CompletableFuture.supplyAsync({ draft.toFreeNote() }, background)
            .thenComposeAsync({ repository().create(it, draft.tagNames()) }, background)
            .thenApplyAsync({ note -> publish(); note }, ui)

    /**
     * 편집한 글과 태그만 한 트랜잭션으로 저장한다. 앵커와 상태는 목록을 읽은 뒤 위치 갱신이 바꿨을 수 있어 [note]의 값을 쓰지 않는다.
     * 그 사이 지워졌다면 null로 끝나며, 목록을 새로 고치도록 이때도 알린다. APPROX 노트는 편집이 곧 위치 확인이므로
     * [confirmLocation]처럼 함께 확인한다. 파일을 읽지 못하거나 위치가 그 사이 바뀌었으면 글만 저장하고 APPROX로 남긴다.
     */
    fun update(project: Project, note: Note, draft: NoteDraft): CompletableFuture<SavedNote?> =
        // 검증 오류도 future로 드러나고 repository()가 EDT에서 평가되지 않도록 background에서 시작하고 이어 간다
        CompletableFuture.supplyAsync({ draft.toText() }, background)
            .thenComposeAsync({ text ->
                val confirm = if (note.status == NoteStatus.APPROX) confirmation(project, note) else null
                repository().updateText(note.uid, text, draft.tagNames(), confirm)
            }, background)
            .thenApplyAsync({ saved -> publish(); saved }, ui)

    /**
     * 근사 위치(APPROX) 노트를 사용자가 본 위치로 굳힌다. 파일이 없거나 줄 범위가 문서 밖이면
     * 예외로 끝나고 DB는 그대로다. 그 사이 지워졌다면 null로, 위치가 바뀌었다면 locationMoved로 끝난다.
     */
    fun confirmLocation(project: Project, note: Note): CompletableFuture<SavedNote?> =
        CompletableFuture.supplyAsync({
            confirmation(project, note) ?: error(
                JNoteBundle.message("toolWindow.error.confirm.unreadable", note.relPath.orEmpty(), NoteRows.lineRange(note.startLine, note.endLine)),
            )
        }, background)
            .thenComposeAsync({ repository().confirmLocation(note.uid, it) }, background)
            .thenApplyAsync({ saved -> publish(); saved }, ui)

    /**
     * [note]의 줄 범위와 그 범위의 지금 코드. 텍스트는 노트를 만들 때와 같은 규칙(상한 포함)으로 읽는다.
     * 파일이 없거나 범위가 문서 밖이면 null. 파일을 읽으므로 EDT 밖에서 부른다.
     */
    private fun confirmation(project: Project, note: Note): LocationConfirmation? {
        val relPath = note.relPath ?: return null
        val start = note.startLine ?: return null
        val end = note.endLine ?: return null
        val file = CodeTargets.find(project, relPath) ?: return null
        val snapshot = ReadAction.compute<String?, Throwable> {
            FileDocumentManager.getInstance().getDocument(file)?.let { CodeAnchors.fromLines(project, file, it, start, end)?.snapshot }
        } ?: return null
        return LocationConfirmation(relPath, start, end, snapshot)
    }
}
