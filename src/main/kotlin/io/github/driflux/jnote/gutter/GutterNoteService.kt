package io.github.driflux.jnote.gutter

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.anchor.AnchorLines
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.DatabaseClosedException
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
internal class GutterNoteService(private val project: Project) : Disposable, PreviewSource {
    private val cache = FileNoteCache(::load)
    private val positions = NotePositions()
    private val bars = RangeBars(project)

    private var seenDatabase: DatabaseService.Current? = null
    private val failureReported = AtomicBoolean(false)

    @Volatile
    private var disposed = false

    init {
        val appBus = ApplicationManager.getApplication().messageBus.connect(this)
        appBus.subscribe(NotesChanged.TOPIC, NotesChangedListener { onNotesChanged() })
        appBus.subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) = writeBack(document)
            },
        )
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (source.isFileOpen(file)) return
                    val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
                    writeBack(document)
                    positions.forget(document)
                    bars.forget(document)
                }
            },
        )
    }

    internal val isIdle: Boolean get() = cache.isIdle

    internal val rangeBars: RangeBars get() = bars

    internal fun attachGutterInteraction(document: Document) {
        if (!disposed) GutterAddNoteAttacher.attach(project, document, this, { disposed }, this)
    }

    /**
     * 거터 호버 미리보기가 쓰는, [line](0-based)을 범위 안에 두는 노트. 메모리 캐시와 RangeMarker만 읽고 읽기를 시작하지 않으므로
     * EDT에서 불러도 DB나 파일에 닿지 않는다. 아직 읽지 못했거나 다른 DB로 바뀐 뒤의 캐시는 비어 있는 것으로 본다(마커 패스가 채운다).
     */
    internal fun notesCovering(document: Document, line: Int): List<CachedNote> {
        if (disposed) return emptyList()
        val file = FileDocumentManager.getInstance().getFile(document) ?: return emptyList()
        val relPath = AnchorLines.relativePath(project.basePath, file.path) ?: return emptyList()
        if (!cacheMatchesCurrentDatabase()) return emptyList()
        val notes = cache.peek(relPath)
        if (notes.isNullOrEmpty()) return emptyList()
        return NotePreviewText.covering(track(document, relPath, notes), line + 1)
    }

    override fun notesAt(document: Document, line: Int): List<CachedNote> = notesCovering(document, line)

    override fun database(): Database? = synchronized(this) { seenDatabase?.database }

    private fun cacheMatchesCurrentDatabase(): Boolean {
        val seen = synchronized(this) { seenDatabase } ?: return false
        val now = DatabaseService.getInstance().current()
        return seen.path == now.path && (seen.database == null || seen.database === now.database)
    }

    internal fun isTracking(document: Document): Boolean = positions.isTracking(document)

    fun notesFor(file: VirtualFile, relPath: String): List<CachedNote>? {
        if (disposed) return null
        discardIfDatabaseChanged()
        return cache.lookup(relPath, onChanged = { restart(file) }, onFailed = { failed(file, relPath, it) })
    }

    fun track(document: Document, relPath: String, notes: List<CachedNote>): List<CachedNote> {
        val database = synchronized(this) { seenDatabase?.database } ?: return notes
        return positions.sync(document, relPath, database, notes)
    }

    private fun writeBack(document: Document) {
        val writeBack = positions.writeBackFor(document) ?: return
        NoteRepository(writeBack.database).updateAnchors(writeBack.updates).whenComplete { changed, error ->
            val cause = if (error is CompletionException) error.cause ?: error else error
            when {
                cause == null -> {
                    positions.confirm(document, writeBack)
                    if (changed > 0) {
                        ApplicationManager.getApplication().invokeLater({
                            if (!disposed) ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
                        }, ModalityState.nonModal())
                    }
                }
                cause is DatabaseClosedException -> {}
                else -> ApplicationManager.getApplication().invokeLater({
                    if (!disposed) GutterNotifier.saveFailed(project, writeBack.relPath, cause)
                }, project.disposed)
            }
        }
    }

    private fun discardIfDatabaseChanged() {
        val now = DatabaseService.getInstance().current()
        synchronized(this) {
            val seen = seenDatabase
            val same = seen != null && seen.path == now.path && (seen.database == null || seen.database === now.database)
            seenDatabase = now
            if (same) return
            cache.clear()
            positions.clear()
            failureReported.set(false)
        }
    }

    private fun onNotesChanged() {
        cache.invalidate()
        failureReported.set(false)
        ApplicationManager.getApplication().invokeLater({
            if (disposed) return@invokeLater
            for (file in FileEditorManager.getInstance(project).openFiles) {
                val relPath = AnchorLines.relativePath(project.basePath, file.path) ?: continue
                if (cache.contains(relPath)) notesFor(file, relPath)
            }
        }, project.disposed)
    }

    private fun load(relPath: String): CompletableFuture<List<CachedNote>> =
        CompletableFuture.supplyAsync({ project.service<ProjectKeyService>().resolve().key }, AppExecutorUtil.getAppExecutorService())
            .thenCompose { key ->
                val databases = DatabaseService.getInstance()
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
        if (cause is DatabaseClosedException) {
            restart(file)
            return
        }
        if (!failureReported.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            if (!disposed) GutterNotifier.loadFailed(project, relPath, cause)
        }, project.disposed)
    }

    private fun restart(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater({
            if (disposed || !file.isValid) return@invokeLater
            PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }, project.disposed)
    }

    override fun dispose() {
        disposed = true
        cache.clear()
        positions.clear()
        bars.dispose()
    }
}