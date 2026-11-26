package io.github.driflux.jnote.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.project.ProjectKeyService
import java.awt.BorderLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ListSelectionModel

class JNotePanel(private val project: Project) : JBPanel<JNotePanel>(BorderLayout()), Disposable {
    private class Loaded(val projectKey: String, val notes: List<Note>)

    private val model = CollectionListModel<NoteRow>()
    internal val list = JBList(model).apply {
        cellRenderer = NoteRowRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    // 늦게 끝난 옛 읽기가 새 결과를 덮지 않도록 번호를 매긴다
    private val generation = AtomicInteger()

    @Volatile
    private var disposed = false

    init {
        add(JBScrollPane(list), BorderLayout.CENTER)
        // 구독을 먼저 걸어 첫 읽기 사이에 오는 알림을 놓치지 않는다
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { reload() })
        reload()
    }

    /** 어느 스레드에서 불러도 된다. 읽기는 EDT 밖에서, 화면 갱신은 EDT에서 한다. */
    fun reload() {
        val ticket = generation.incrementAndGet()
        load(allProjects = false).whenComplete { loaded, error ->
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || ticket != generation.get()) return@invokeLater
                if (loaded != null) show(loaded) else ToolWindowNotifier.loadFailed(project, error.cause ?: error)
            }
        }
    }

    private fun load(allProjects: Boolean): CompletableFuture<Loaded> =
        // 프로젝트 키는 git 설정 파일을 읽으므로 DB 스레드가 아니라 풀 스레드에서 구한다
        CompletableFuture.supplyAsync({ project.service<ProjectKeyService>().resolve().key }, AppExecutorUtil.getAppExecutorService())
            .thenCompose { key ->
                NoteRepository(DatabaseService.getInstance().database).list(key, allProjects).thenApply { Loaded(key, it) }
            }

    private fun show(loaded: Loaded) {
        val free = JNoteBundle.message("toolWindow.subtitle.free")
        model.replaceAll(loaded.notes.map { NoteRows.of(it, loaded.projectKey, free) })
    }

    override fun dispose() {
        disposed = true
    }
}
