package io.github.driflux.jnote.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.relocate.PathRecovery
import io.github.driflux.jnote.relocate.Recovery
import java.util.concurrent.CompletableFuture

/**
 * "코드로 이동". 파일 찾기는 풀 스레드에서, 열기는 EDT에서 한다.
 * 호출한 쪽이 future를 버려도 실패가 묻히지 않도록 실패는 여기서 알림으로 알린다.
 */
internal object CodeNavigator {
    /** 풀 스레드에서 정한 이동할 곳. [file]이 null이면 파일이 없다. [notesChanged]면 노트를 고쳤으므로 EDT에서 알린다. */
    internal class Target(val file: VirtualFile?, val line: Int, val notesChanged: Boolean)

    /**
     * 이동했으면 true. 파일이 없거나 실패하면 알림을 띄우고 false(실패는 예외 완료). 이동할 곳이 없는 노트도 false.
     * CODE 노트의 파일이 없으면 [recover]로 한 번 다시 찾고 결과를 노트에 쓴다.
     */
    fun goTo(
        project: Project,
        note: Note,
        find: (Project, String) -> VirtualFile? = CodeTargets::find,
        recover: (Project, Note) -> Recovery = PathRecovery::recover,
    ): CompletableFuture<Boolean> =
        open(project, note.kind, note.relPath, note.startLine, whenMissing = {
            if (note.kind == NoteKind.CODE) recovered(project, note, recover) else Target(null, 0, false)
        }, find = find)

    internal fun open(
        project: Project,
        kind: NoteKind,
        relPath: String?,
        startLine: Int?,
        whenMissing: () -> Target = { Target(null, 0, false) },
        find: (Project, String) -> VirtualFile? = CodeTargets::find,
    ): CompletableFuture<Boolean> {
        if (project.isDisposed) return CompletableFuture.completedFuture(false)
        if (relPath == null) {
            // 이동할 곳이 없는 것이 정상인 FREE 노트와 달리, 경로가 없는 CODE 노트는 알려야 한다
            if (kind == NoteKind.CODE) ToolWindowNotifier.noPath(project)
            return CompletableFuture.completedFuture(false)
        }
        val line = (startLine ?: 1) - 1
        val done = CompletableFuture<Boolean>()
        fun fail(cause: Throwable) {
            if (!project.isDisposed) ToolWindowNotifier.goToFailed(project, cause)
            done.completeExceptionally(cause)
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val target = find(project, relPath)?.let { Target(it, line, false) } ?: whenMissing()
                // 만료 조건을 주면 프로젝트가 닫힐 때 작업이 버려져 future가 영영 끝나지 않으므로 안에서 확인한다
                ApplicationManager.getApplication().invokeLater({
                    try {
                        if (target.notesChanged) ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
                        if (project.isDisposed) {
                            done.complete(false)
                        } else if (target.file == null) {
                            ToolWindowNotifier.fileMissing(project, relPath)
                            done.complete(false)
                        } else {
                            OpenFileDescriptor(project, target.file, target.line, 0).navigate(true)
                            done.complete(true)
                        }
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }, ModalityState.defaultModalityState())
            } catch (e: Throwable) {
                fail(e)
            }
        }
        return done
    }

    /** 풀 스레드에서 다시 찾고, 찾았든 못 찾았든 새 앵커를 노트에 쓴다. updated_at은 그대로다. */
    private fun recovered(project: Project, note: Note, recover: (Project, Note) -> Recovery): Target {
        val recovery = recover(project, note)
        val update = when (recovery) {
            is Recovery.Found -> recovery.update
            is Recovery.NotFound -> recovery.update
            Recovery.Skipped -> return Target(null, 0, false)
        }
        val changed = NoteRepository(DatabaseService.getInstance().database).updateAnchors(listOf(update)).get() > 0
        val file = (recovery as? Recovery.Found)?.file
        return Target(file, update.startLine - 1, changed)
    }
}
