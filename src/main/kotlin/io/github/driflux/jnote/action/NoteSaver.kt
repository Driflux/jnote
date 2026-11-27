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
            .thenApplyAsync({ note -> publish(); note }, ui)

    fun saveFree(draft: NoteDraft): CompletableFuture<Note> =
        CompletableFuture.supplyAsync({ draft.toFreeNote() }, background)
            .thenComposeAsync({ repository().create(it, draft.tagNames()) }, background)
            .thenApplyAsync({ note -> publish(); note }, ui)

    fun update(project: Project, note: Note, draft: NoteDraft): CompletableFuture<Note?> =
        CompletableFuture.completedFuture(draft)
            .thenApply { it.applyTo(note) }
            .thenApplyAsync({ edited -> if (edited.status == NoteStatus.APPROX) confirmed(project, edited) ?: edited else edited }, background)
            .thenCompose { repository().update(it) }
            .thenCompose { updated -> if (updated == null) CompletableFuture.completedFuture(null) else withTags(updated, draft) }
            .thenApplyAsync({ updated -> publish(); updated }, ui)

    fun confirmLocation(project: Project, note: Note): CompletableFuture<Note?> =
        CompletableFuture.supplyAsync({
            confirmed(project, note) ?: error(
                JNoteBundle.message("toolWindow.error.confirm.unreadable", note.relPath.orEmpty(), NoteRows.lineRange(note.startLine, note.endLine)),
            )
        }, background)
            .thenCompose { repository().update(it) }
            .thenApplyAsync({ updated -> publish(); updated }, ui)

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
