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

    fun update(project: Project, note: Note, draft: NoteDraft): CompletableFuture<SavedNote?> =
        CompletableFuture.supplyAsync({ draft.toText() }, background)
            .thenComposeAsync({ text ->
                val confirm = if (note.status == NoteStatus.APPROX) confirmation(project, note) else null
                repository().updateText(note.uid, text, draft.tagNames(), confirm)
            }, background)
            .thenApplyAsync({ saved -> publish(); saved }, ui)

    fun confirmLocation(project: Project, note: Note): CompletableFuture<SavedNote?> =
        CompletableFuture.supplyAsync({
            confirmation(project, note) ?: error(
                JNoteBundle.message("toolWindow.error.confirm.unreadable", note.relPath.orEmpty(), NoteRows.lineRange(note.startLine, note.endLine)),
            )
        }, background)
            .thenComposeAsync({ repository().confirmLocation(note.uid, it) }, background)
            .thenApplyAsync({ saved -> publish(); saved }, ui)

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
