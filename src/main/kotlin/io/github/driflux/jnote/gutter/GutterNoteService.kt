package io.github.driflux.jnote.gutter

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.anchor.AnchorLines
import io.github.driflux.jnote.db.DatabaseClosedException
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로젝트별 거터 노트 캐시. plugin.xml에 등록하지 않는 라이트 서비스라 마커 제공자가 처음 부를 때
 * 만들어지며, 그때 [NotesChanged]를 구독한다. 서비스가 프로젝트마다 하나이고 프로젝트 키는 바뀌지 않으므로
 * 캐시 항목은 상대 경로만으로 (프로젝트 키, 상대 경로)를 가리킨다.
 */
@Service(Service.Level.PROJECT)
internal class GutterNoteService(private val project: Project) : Disposable {
    private val cache = FileNoteCache(::load)

    // 캐시를 채운 DB. 다른 DB의 노트를 보여 주지 않도록 바뀌면 캐시를 통째로 버린다
    private var seenDatabase: DatabaseService.Current? = null
    private val failureReported = AtomicBoolean(false)

    @Volatile
    private var disposed = false

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { onNotesChanged() })
    }

    internal val isIdle: Boolean get() = cache.isIdle

    /**
     * 파일의 노트. 아직 못 읽었으면 null을 돌려주고 EDT 밖에서 읽기를 시작하며, 읽은 값이 그릴 것을 바꾸면
     * 그 파일만 데몬을 다시 돌린다. 하이라이팅을 막지 않는다.
     */
    fun notesFor(file: VirtualFile, relPath: String): List<CachedNote>? {
        if (disposed) return null
        discardIfDatabaseChanged()
        return cache.lookup(relPath, onChanged = { restart(file) }, onFailed = { failed(file, relPath, it) })
    }

    private fun discardIfDatabaseChanged() {
        val now = DatabaseService.getInstance().current()
        synchronized(this) {
            val seen = seenDatabase
            // 아직 열리지 않았던 DB가 같은 경로에서 처음 열린 것은 전환이 아니다
            val same = seen != null && seen.path == now.path && (seen.database == null || seen.database === now.database)
            seenDatabase = now
            if (same) return
            cache.clear()
            failureReported.set(false)
        }
    }

    private fun onNotesChanged() {
        cache.invalidate()
        failureReported.set(false)
        // 열린 파일만 미리 다시 읽는다. 값이 같으면 데몬을 다시 돌리지 않고, 닫힌 파일은 다음에 열 때 읽는다
        ApplicationManager.getApplication().invokeLater({
            if (disposed) return@invokeLater
            for (file in FileEditorManager.getInstance(project).openFiles) {
                val relPath = AnchorLines.relativePath(project.basePath, file.path) ?: continue
                if (cache.contains(relPath)) notesFor(file, relPath)
            }
        }, project.disposed)
    }

    private fun load(relPath: String): CompletableFuture<List<CachedNote>> =
        // 프로젝트 키는 git 설정 파일을 읽으므로 DB 스레드가 아니라 풀 스레드에서 구한다
        CompletableFuture.supplyAsync({ project.service<ProjectKeyService>().resolve().key }, AppExecutorUtil.getAppExecutorService())
            .thenCompose { key ->
                val databases = DatabaseService.getInstance()
                // 노트를 한 번도 저장하지 않았다면 파일을 열었다는 이유만으로 DB 파일을 만들지 않는다
                if (databases.openDatabaseOrNull() == null && !Files.exists(databases.currentPath())) {
                    return@thenCompose CompletableFuture.completedFuture(emptyList())
                }
                val database = databases.database
                val notes = NoteRepository(database).listByPath(key, relPath)
                val tags = TagRepository(database).tagsByPath(key, relPath)
                notes.thenCombine(tags) { found, tagsByNote -> found.mapNotNull { CachedNote.of(it, tagsByNote[it.id].orEmpty()) } }
            }

    private fun failed(file: VirtualFile, relPath: String, error: Throwable) {
        val cause = if (error is CompletionException) error.cause ?: error else error
        // 읽는 사이 DB 경로가 바뀌었다. 다음 패스가 새 DB를 보고 캐시를 버린 뒤 다시 읽는다
        if (cause is DatabaseClosedException) {
            restart(file)
            return
        }
        // 파일마다 같은 실패를 알리지 않도록 다음 변경이나 전환 전까지 한 번만 알린다
        if (!failureReported.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            if (!disposed) GutterNotifier.loadFailed(project, relPath, cause)
        }, project.disposed)
    }

    // 하이라이팅 중에는 데몬을 다시 시작할 수 없으므로 EDT로 넘긴다
    private fun restart(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater({
            if (disposed || !file.isValid) return@invokeLater
            PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }, project.disposed)
    }

    override fun dispose() {
        disposed = true
        cache.clear()
    }
}
