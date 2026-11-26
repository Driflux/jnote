package io.github.driflux.jnote.relocate

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.db.DatabaseClosedException
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.AnchorUpdate
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.project.ProjectKeyService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 파일을 열면 그 파일의 노트를 지금 내용에서 다시 찾아 줄 범위와 상태를 고친다(intent.md 6절).
 * plugin.xml에 등록하지 않는 라이트 서비스라 노트가 있는 파일의 마커 패스가 처음 부를 때 만들어진다.
 * 열려 있는 동안의 위치는 RangeMarker(이슈 9)가 따라가므로, 편집 중인 코드를 스냅샷과 비교하지 않도록
 * 문서가 열린 동안 한 번만 검증한다. 같은 결과를 두 번 써도 바뀌는 것이 없으므로 따로 잠그지 않는다.
 */
@Service(Service.Level.PROJECT)
internal class OpenValidationService(private val project: Project) : Disposable {
    @Volatile
    private var disposed = false
    private val inFlight = AtomicInteger()

    /** 진행 중인 검증이 없다. 테스트가 늦은 검증을 기다리는 데 쓴다. */
    internal val isIdle: Boolean get() = inFlight.get() == 0

    /**
     * 마커 패스의 읽기 액션 안에서 부른다. 이 문서의 에디터 중 하나라도 이미 검증했다는 표시가 있으면
     * 분할처럼 새로 생긴 에디터에도 표시만 남긴다. 표시가 하나도 없으면 모두 표시하고 검증을 예약한다.
     * 에디터를 모두 닫았다 다시 열면 새 에디터에는 표시가 없으므로 다시 검증한다.
     */
    fun validateOncePerOpen(document: Document, relPath: String) {
        if (disposed) return
        val editors = EditorFactory.getInstance().editors(document, project).toList()
        if (editors.isEmpty()) return
        val validated = editors.any { it.getUserData(VALIDATED) == true }
        for (editor in editors) editor.putUserData(VALIDATED, true)
        if (!validated) validate(document, relPath)
    }

    /** 검증 한 번. 실제로 바뀐 노트 수로 끝나며, 바뀐 것이 있으면 UI 스레드에서 [NotesChanged]를 한 번 알린다. */
    internal fun validate(document: Document, relPath: String): CompletableFuture<Int> {
        inFlight.incrementAndGet()
        val pool = AppExecutorUtil.getAppExecutorService()
        // 프로젝트 키는 git 설정 파일을 읽으므로 DB 스레드가 아니라 풀 스레드에서 구한다
        return CompletableFuture.supplyAsync({ project.service<ProjectKeyService>().resolve().key }, pool)
            .thenCompose { key ->
                // 읽은 DB에만 쓴다. 그 사이 경로가 바뀌면 닫힌 DB라 DatabaseClosedException으로 끝난다
                val repository = NoteRepository(DatabaseService.getInstance().database)
                repository.listByPath(key, relPath)
                    // 계산이 DB 스레드를 붙잡지 않도록 풀 스레드로 옮긴다
                    .thenApplyAsync({ notes -> relocate(document, notes) }, pool)
                    .thenCompose { updates ->
                        if (updates.isNullOrEmpty() || disposed) CompletableFuture.completedFuture(0) else repository.updateAnchors(updates)
                    }
            }
            .whenComplete { changed, error ->
                try {
                    if (error != null) failed(relPath, error) else if (changed > 0) publishChanged()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
    }

    /** 취소되면 null. 문서가 바뀌면 그 사이 편집과 맞지 않는 줄을 쓰지 않도록 취소한다. */
    private fun relocate(document: Document, notes: List<Note>): List<AnchorUpdate>? {
        val (text, stamp) = ReadAction.compute<Pair<String, Long>, Throwable> { document.text to document.modificationStamp }
        // 스냅샷과 같은 규칙으로 줄을 나눈다
        val lines = text.lines()
        return FileRelocation.relocate(
            notes,
            lines,
            similarity = similarityAllowed(lines),
            isCancelled = { disposed || project.isDisposed || document.modificationStamp != stamp },
        )
    }

    private fun failed(relPath: String, error: Throwable) {
        val cause = if (error is CompletionException) error.cause ?: error else error
        // DB 경로가 바뀌어 옛 DB가 닫혔다
        if (cause is DatabaseClosedException) return
        // 같은 DB의 실패는 거터 읽기가 이미 알리므로 로그만 남긴다
        thisLogger().warn("Cannot validate notes of $relPath", cause)
    }

    // DB 스레드에서 알리면 구독자가 DB를 기다리다 막힐 수 있어 UI 스레드로 옮긴다
    private fun publishChanged() {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed) ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        }, ModalityState.nonModal(), project.disposed)
    }

    override fun dispose() {
        disposed = true
    }

    internal companion object {
        /** 이 줄 수 이하의 파일만 유사도까지 찾는다. 넘으면 정확 일치만 찾는다(intent.md 6절). 못 찾은 노트의 상태는 FileRelocation이 정한다. */
        private const val SIMILARITY_MAX_LINES = 5_000

        /**
         * 유사도까지 찾을 크기인지. 마지막 줄바꿈 뒤의 빈 조각은 줄로 세지 않으므로,
         * 줄바꿈으로 끝나는 5,000줄 파일도 5,000줄이다.
         */
        internal fun similarityAllowed(lines: List<String>): Boolean {
            val count = if (lines.lastOrNull()?.isEmpty() == true) lines.size - 1 else lines.size
            return count <= SIMILARITY_MAX_LINES
        }

        /** 이 문서가 이번에 열린 동안 이미 검증했다는 에디터 표시. */
        internal val VALIDATED = Key.create<Boolean>("jnote.openValidated")
    }
}
