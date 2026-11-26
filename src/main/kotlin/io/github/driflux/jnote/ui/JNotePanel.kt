package io.github.driflux.jnote.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.action.NoteSaver
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.project.ProjectKeyService
import java.awt.BorderLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ListSelectionModel

class JNotePanel internal constructor(
    private val project: Project,
    scheduler: DelayScheduler,
    private val saver: NoteSaver = NoteSaver(),
    private val askEdit: (Project, Note) -> NoteDraft? = AddNoteDialog::edit,
) : JBPanel<JNotePanel>(BorderLayout()), Disposable {
    constructor(project: Project) : this(project, AppDelayScheduler)

    private class Loaded(val projectKey: String, val notes: List<Note>)

    private val model = CollectionListModel<NoteRow>()
    internal val list = JBList(model).apply {
        cellRenderer = NoteRowRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    internal val detail = NoteDetailPanel(onGoToCode = { CodeNavigator.goTo(project, it) }, onDelete = { delete(it) }, onEdit = { edit(it) })

    // reload()가 어느 스레드에서든 불리므로 Swing 부품 대신 이 값을 읽는다
    @Volatile
    private var allProjects = false

    internal val allProjectsBox = JBCheckBox(JNoteBundle.message("toolWindow.allProjects")).apply {
        addActionListener {
            allProjects = isSelected
            reload()
        }
    }
    internal val spinnerIcon = AsyncProcessIcon("JNote").apply { isVisible = false }

    // 닫힌 뒤에 늦게 도착한 타이머가 이미 정리된 아이콘을 다시 돌리지 못하게 한다
    internal val spinner = DelayedSpinner(scheduler, onVisibleChanged = {
        if (!disposed) {
            spinnerIcon.isVisible = it
            if (it) spinnerIcon.resume() else spinnerIcon.suspend()
        }
    })

    // 아직 끝나지 않은 읽기 수. 테스트가 DB 경로를 바꾸기 전에 읽기가 끝났는지 확인하는 데 쓴다
    private val pendingLoads = AtomicInteger()
    internal val isIdle: Boolean get() = pendingLoads.get() == 0

    // EDT에서만 바꾼다
    internal var state = ToolWindowState()
        private set

    // 늦게 끝난 옛 읽기가 새 결과를 덮지 않도록 번호를 매긴다
    private val generation = AtomicInteger()

    @Volatile
    private var disposed = false

    init {
        Disposer.register(this, spinnerIcon)
        list.addListSelectionListener { if (!it.valueIsAdjusting) detail.show(list.selectedValue?.note) }
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(allProjectsBox, BorderLayout.WEST)
            add(spinnerIcon, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(OnePixelSplitter(true, 0.5f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = detail
        }, BorderLayout.CENTER)
        // 구독을 먼저 걸어 첫 읽기 사이에 오는 알림을 놓치지 않는다
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { reload() })
        reload()
    }

    /** 어느 스레드에서 불러도 된다. 읽기는 EDT 밖에서, 화면 갱신은 EDT에서 한다. */
    fun reload() {
        val ticket = generation.incrementAndGet()
        val allProjects = allProjects
        pendingLoads.incrementAndGet()
        onEdt { if (!disposed) spinner.start() }
        load(allProjects).whenComplete { loaded, error ->
            pendingLoads.decrementAndGet()
            onEdt {
                if (disposed || ticket != generation.get()) return@onEdt
                spinner.stop()
                if (loaded != null) show(loaded) else ToolWindowNotifier.loadFailed(project, error.cause ?: error)
            }
        }
    }

    internal fun delete(note: Note): CompletableFuture<*> =
        deleteOn(NoteDeletion.onCurrentDatabase(), note)

    // 되돌리기는 지운 그 DB로 가야 하므로 같은 NoteDeletion을 알림 동작까지 들고 간다
    private fun deleteOn(deletion: NoteDeletion, note: Note): CompletableFuture<*> =
        deletion.delete(note.id).whenComplete { deleted, error ->
            onEdt {
                when {
                    error != null -> ToolWindowNotifier.deleteFailed(project, error.cause ?: error)
                    deleted != null -> {
                        publishChanged()
                        ToolWindowNotifier.deleted(project) { undo(deletion, deleted) }
                    }
                    // 이미 지워진 노트라면 목록만 새로 고친다
                    else -> publishChanged()
                }
            }
        }

    /** 다이얼로그를 띄워 편집하고, 확인하면 EDT 밖에서 저장한다. 취소하면 바로 끝나는 future를 돌려준다. */
    internal fun edit(note: Note): CompletableFuture<*> {
        val draft = askEdit(project, note) ?: return CompletableFuture.completedFuture(null)
        // 저장소가 알림을 UI 스레드로 옮겨 보내므로 여기서는 실패만 알린다
        return saver.update(note, draft).whenComplete { _, error ->
            if (error != null) onEdt { ToolWindowNotifier.editFailed(project, (error as? CompletionException)?.cause ?: error) }
        }
    }

    private fun undo(deletion: NoteDeletion, deleted: DeletedNote) {
        deletion.undo(deleted).whenComplete { _, error ->
            onEdt { if (error == null) publishChanged() else ToolWindowNotifier.restoreFailed(project, (error as? CompletionException)?.cause ?: error) }
        }
    }

    private fun publishChanged() =
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ block() }, project.disposed)
    }

    private fun load(allProjects: Boolean): CompletableFuture<Loaded> =
        // 프로젝트 키는 git 설정 파일을 읽으므로 DB 스레드가 아니라 풀 스레드에서 구한다
        CompletableFuture.supplyAsync({ project.service<ProjectKeyService>().resolve().key }, AppExecutorUtil.getAppExecutorService())
            .thenCompose { key ->
                NoteRepository(DatabaseService.getInstance().database).list(key, allProjects).thenApply { Loaded(key, it) }
            }

    private fun show(loaded: Loaded) {
        val free = JNoteBundle.message("toolWindow.subtitle.free")
        val selectedId = list.selectedValue?.note?.id
        val rows = loaded.notes.map { NoteRows.of(it, loaded.projectKey, free) }
        state = ToolWindowState(rows, allProjects)
        model.replaceAll(rows)
        showEmptyText()
        // 다시 읽어도 보던 노트를 유지하고, 그 사이 지워졌다면 상세를 비운다
        val index = model.items.indexOfFirst { it.note.id == selectedId }
        if (index >= 0) list.selectedIndex = index else detail.show(null)
    }

    private fun showEmptyText() {
        val text = list.emptyText
        if (state.showEmptyState) {
            text.text = JNoteBundle.message("toolWindow.empty.title")
            text.appendLine(emptyHint())
        } else {
            text.clear()
        }
    }

    // 단축키는 사용자가 바꿀 수 있고 메뉴 이름은 액션의 문구이므로, 둘 다 실제 값에서 가져온다
    private fun emptyHint(): String {
        val action = JNoteBundle.message("action.addNote.text")
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText(ADD_NOTE_ACTION_ID)
        return if (shortcut.isEmpty()) {
            JNoteBundle.message("toolWindow.empty.hint.noShortcut", action)
        } else {
            JNoteBundle.message("toolWindow.empty.hint", action, shortcut)
        }
    }

    override fun dispose() {
        disposed = true
        // 대기 중인 타이머를 취소하고 보이는 상태를 끈다. DelayedSpinner는 EDT에서만 쓴다
        UIUtil.invokeLaterIfNeeded { spinner.stop() }
    }

    companion object {
        internal const val ADD_NOTE_ACTION_ID = "JNote.AddNote"
    }
}
