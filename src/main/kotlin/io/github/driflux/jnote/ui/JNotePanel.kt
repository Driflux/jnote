package io.github.driflux.jnote.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.OnePixelSplitter
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NoteSelectRequested
import io.github.driflux.jnote.NoteSelectRequestedListener
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.action.NoteSaver
import io.github.driflux.jnote.action.RelocateNotesTask
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.SearchRepository
import io.github.driflux.jnote.db.TagRenameResult
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.Tag
import io.github.driflux.jnote.project.ProjectKeyService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class JNotePanel internal constructor(
    private val project: Project,
    private val scheduler: DelayScheduler,
    private val saver: NoteSaver = NoteSaver(),
    private val askEdit: (Project, Note, List<String>) -> NoteDraft? = AddNoteDialog::edit,
    private val askTagName: (Project, Tag, List<Tag>) -> String? = TagDialogs::askNewName,
    private val confirmTagDelete: (Project, Tag) -> Boolean = TagDialogs::confirmDelete,
) : JBPanel<JNotePanel>(BorderLayout()), Disposable {
    constructor(project: Project) : this(project, AppDelayScheduler)

    private class Loaded(
        val projectKey: String,
        val notes: List<Note>,
        val dictionary: List<Tag>,
        val noteTags: Map<Long, List<Tag>>,
        val match: SearchMatch?,
    )

    private val model = CollectionListModel<NoteRow>()
    internal val list = JBList(model).apply {
        cellRenderer = NoteRowRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    internal val detail = NoteDetailPanel(
        onGoToCode = { CodeNavigator.goTo(project, it) },
        onDelete = { delete(it) },
        onEdit = { edit(it) },
        onRelocate = { note -> note.relPath?.let { RelocateNotesTask(project, it).queue() } },
        canRelocate = { note -> note.project != null && note.project == shownProjectKey },
        onConfirm = { confirmLocation(it) },
    )

    // 다시 찾기는 지금 프로젝트의 파일과 노트로만 하므로, "모든 프로젝트" 보기의 다른 프로젝트 노트에는 버튼을 숨긴다. EDT에서만 쓴다
    private var shownProjectKey: String? = null

    // reload()가 어느 스레드에서든 불리므로 Swing 부품 대신 이 값을 읽는다
    @Volatile
    private var allProjects = false

    internal val allProjectsBox = JBCheckBox(JNoteBundle.message("toolWindow.allProjects")).apply {
        addActionListener {
            allProjects = isSelected
            reload()
        }
    }
    internal val filterCombo = ComboBox(NoteFilter.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { JNoteBundle.message(it.labelKey) }
        // 필터는 이미 읽은 행에만 걸리므로 다시 읽지 않는다
        addActionListener {
            state = state.copy(filter = selectedItem as NoteFilter)
            render()
        }
    }
    // 사전이 다시 읽힐 때마다 칩을 새로 만든다. 켜고 끄는 것은 이 줄이 아니라 state가 기억한다
    internal val tagBar = JBPanel<JBPanel<*>>(WrapLayout(FlowLayout.LEFT, 4, 2)).apply { isVisible = false }
    // 검색창. 입력이 멈춘 뒤에 한 번만 검색하고, 검색은 EDT 밖에서 한다
    internal val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JNoteBundle.message("toolWindow.search.placeholder")
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onQueryTyped()
        })
    }
    private var searchTimer: Cancellable? = null

    // reload()가 어느 스레드에서든 불리므로 state 대신 이 값을 읽는다
    @Volatile
    private var query = ""

    // 늦게 끝난 옛 검색이 새 결과를 덮지 않도록 번호를 매긴다
    private val searchGeneration = AtomicInteger()

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
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(allProjectsBox)
                add(filterCombo)
            }, BorderLayout.WEST)
            add(spinnerIcon, BorderLayout.EAST)
        }
        add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 0, 8)
            add(searchField, BorderLayout.NORTH)
            add(header, BorderLayout.CENTER)
            add(tagBar, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(OnePixelSplitter(true, 0.5f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = detail
        }, BorderLayout.CENTER)
        // 구독을 먼저 걸어 첫 읽기 사이에 오는 알림을 놓치지 않는다
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { reload() })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NoteSelectRequested.TOPIC, NoteSelectRequestedListener { requested, noteId -> onSelectRequested(requested, noteId) })
        reload()
    }

    /** 어느 스레드에서 불러도 된다. 읽기는 EDT 밖에서, 화면 갱신은 EDT에서 한다. */
    fun reload() {
        val ticket = generation.incrementAndGet()
        val allProjects = allProjects
        val query = query
        pendingLoads.incrementAndGet()
        onEdt { if (!disposed) spinner.start() }
        load(allProjects, query).whenComplete { loaded, error ->
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

    internal fun edit(note: Note): CompletableFuture<*> {
        val done = CompletableFuture<Unit>()
        TagRepository(DatabaseService.getInstance().database).tagsOf(note.id).whenComplete { tags, readError ->
            onEdt {
                if (readError != null) {
                    ToolWindowNotifier.editFailed(project, (readError as? CompletionException)?.cause ?: readError)
                    done.complete(Unit)
                    return@onEdt
                }
                val draft = askEdit(project, note, tags.map { it.name })
                if (draft == null) {
                    done.complete(Unit)
                    return@onEdt
                }
                // 저장소가 알림을 UI 스레드로 옮겨 보내므로 여기서는 실패만 알린다
                saver.update(project, note, draft).whenComplete { _, error ->
                    if (error != null) onEdt { ToolWindowNotifier.editFailed(project, (error as? CompletionException)?.cause ?: error) }
                    done.complete(Unit)
                }
            }
        }
        return done
    }

    internal fun confirmLocation(note: Note): CompletableFuture<*> =
        saver.confirmLocation(project, note).whenComplete { _, error ->
            if (error != null) onEdt { ToolWindowNotifier.confirmFailed(project, (error as? CompletionException)?.cause ?: error) }
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

    // 프로젝트 키는 git 설정 파일을 읽으므로 DB 스레드가 아니라 풀 스레드에서 구한다
    private fun resolveKey(): CompletableFuture<String> =
        CompletableFuture.supplyAsync({ project.service<ProjectKeyService>().resolve().key }, AppExecutorUtil.getAppExecutorService())

    private fun load(allProjects: Boolean, query: String): CompletableFuture<Loaded> =
        resolveKey().thenCompose { key ->
            val database = DatabaseService.getInstance().database
            val tags = TagRepository(database)
            val notes = NoteRepository(database).list(key, allProjects)
            val dictionary = tags.list()
            val assignments = tags.assignments()
            // 노트가 바뀌어 다시 읽을 때도 일치 여부를 새로 구해야 새로 생긴 노트가 결과에 들어온다
            val match: CompletableFuture<SearchMatch?> =
                if (SearchQuery.isSearchable(query)) searchIds(key, query, allProjects).thenApply { it } else CompletableFuture.completedFuture(null)
            CompletableFuture.allOf(notes, dictionary, assignments, match)
                .thenApply { Loaded(key, notes.join(), dictionary.join(), assignments.join(), match.join()) }
        }

    private fun searchIds(key: String, query: String, allProjects: Boolean): CompletableFuture<SearchMatch> =
        SearchRepository(DatabaseService.getInstance().database).search(query, key, allProjects)
            .thenApply { SearchMatch(query, it.toSet()) }

    private fun onQueryTyped() {
        searchTimer?.cancel()
        searchTimer = scheduler.schedule(SEARCH_DEBOUNCE_MS) { applyQuery(searchField.text) }
    }

    /** EDT에서만 부른다. 입력이 최소 길이보다 짧으면 검색 없이 목록으로 돌아간다. */
    internal fun applyQuery(text: String) {
        searchTimer = null
        val normalized = SearchQuery.normalize(text)
        if (disposed || normalized == state.query) return
        query = normalized
        state = state.copy(query = normalized)
        val ticket = searchGeneration.incrementAndGet()
        if (!state.searching) {
            render()
            return
        }
        val allProjects = allProjects
        pendingLoads.incrementAndGet()
        // 노트 목록은 이미 읽어 두었으므로 일치하는 id만 새로 구한다
        resolveKey().thenCompose { searchIds(it, normalized, allProjects) }.whenComplete { match, error ->
            pendingLoads.decrementAndGet()
            onEdt {
                if (disposed || ticket != searchGeneration.get()) return@onEdt
                if (match != null) {
                    state = state.copy(match = match)
                    render()
                } else {
                    ToolWindowNotifier.loadFailed(project, error.cause ?: error)
                }
            }
        }
    }

    private fun show(loaded: Loaded) {
        val free = JNoteBundle.message("toolWindow.subtitle.free")
        val rows = loaded.notes.map { NoteRows.of(it, loaded.projectKey, free, loaded.noteTags[it.id].orEmpty()) }
        shownProjectKey = loaded.projectKey
        // 검색 없이 읽은 결과가 그 사이 도착한 검색 결과를 지우지 않게 한다
        state = state.copy(notes = rows, allProjects = allProjects, match = loaded.match ?: state.match).withTags(loaded.dictionary)
        rebuildTagBar()
        render()
    }

    internal fun tagMenu(tag: Tag): JPopupMenu = JBPopupMenu().apply {
        add(JMenuItem(JNoteBundle.message("toolWindow.tag.rename")).apply { addActionListener { renameTag(tag) } })
        add(JMenuItem(JNoteBundle.message("toolWindow.tag.delete")).apply { addActionListener { deleteTag(tag) } })
    }

    internal fun renameTag(tag: Tag): CompletableFuture<*> {
        val name = askTagName(project, tag, state.tags) ?: return CompletableFuture.completedFuture(null)
        return TagRepository(DatabaseService.getInstance().database).rename(tag.id, name).whenComplete { result, error ->
            onEdt {
                when {
                    error != null -> ToolWindowNotifier.renameTagFailed(project, error.cause ?: error)
                    result is TagRenameResult.Conflict -> ToolWindowNotifier.tagExists(project, name.trim())
                    // 입력 검사를 지났다면 일어나지 않지만, 다른 프로세스가 사이에 바꿨을 수 있다. 어느 쪽이든 목록은 새로 읽는다
                    else -> publishChanged()
                }
            }
        }
    }

    internal fun deleteTag(tag: Tag): CompletableFuture<*> {
        if (!confirmTagDelete(project, tag)) return CompletableFuture.completedFuture(null)
        return TagRepository(DatabaseService.getInstance().database).delete(tag.id).whenComplete { _, error ->
            onEdt { if (error == null) publishChanged() else ToolWindowNotifier.deleteTagFailed(project, error.cause ?: error) }
        }
    }

    private fun rebuildTagBar() {
        tagBar.removeAll()
        state.tags.forEach { tag ->
            val chip = tagChip(tag.name, tag.id in state.selectedTagIds) {
                state = state.toggleTag(tag.id)
                render()
            }
            // 오른쪽 클릭은 켜고 끄는 동작이 아니라 이름 바꾸기와 삭제 메뉴를 연다
            chip.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = showMenu(e)
                override fun mouseReleased(e: MouseEvent) = showMenu(e)

                private fun showMenu(e: MouseEvent) {
                    if (e.isPopupTrigger) tagMenu(tag).show(e.component, e.x, e.y)
                }
            })
            tagBar.add(chip)
        }
        tagBar.isVisible = state.tags.isNotEmpty()
        tagBar.revalidate()
        tagBar.repaint()
    }

    private fun render() {
        val selectedId = list.selectedValue?.note?.id
        model.replaceAll(state.visibleNotes)
        showEmptyText()
        // 다시 읽어도 보던 노트를 유지하고, 그 사이 지워졌다면 상세를 비운다
        val index = model.items.indexOfFirst { it.note.id == selectedId }
        if (index >= 0) list.selectedIndex = index else detail.show(null)
        project.service<PendingNoteSelection>().peek()?.let { selectAndClear(it) }
    }

    private fun onSelectRequested(requested: Project, noteId: Long) {
        if (requested !== project) return
        onEdt {
            // 첫 읽기 전이면 요청을 남겨 두고, 읽기가 끝나는 show()가 처리한다
            if (state.notes != null) selectAndClear(noteId)
        }
    }

    /** 목록에 없는 노트(다른 프로젝트 등)는 선택을 바꾸지 않고, 요청도 지우지 않는다. */
    private fun selectAndClear(noteId: Long) {
        val index = model.items.indexOfFirst { it.note.id == noteId }
        if (index < 0) return
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        project.service<PendingNoteSelection>().clear(noteId)
    }

    private fun showEmptyText() {
        val text = list.emptyText
        // 태그 칩이 켜져 있으면 검색어보다 칩이 목록을 비운 것일 수 있으므로 칩 안내를 먼저 보인다
        if (state.showEmptyState && state.tagFilterActive) {
            text.text = JNoteBundle.message("toolWindow.empty.tags.title")
            text.appendLine(JNoteBundle.message("toolWindow.empty.tags.hint"))
        } else if (state.noSearchResults) {
            text.text = JNoteBundle.message("toolWindow.search.empty.title", state.query)
            text.appendLine(JNoteBundle.message(if (state.allProjects) "toolWindow.search.empty.hint.all" else "toolWindow.search.empty.hint"))
        } else if (state.showEmptyState) {
            text.text = JNoteBundle.message(state.filter.emptyTitleKey)
            text.appendLine(if (state.filter == NoteFilter.FREE) JNoteBundle.message("toolWindow.empty.free.hint") else emptyHint())
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
        searchTimer?.cancel()
        // 대기 중인 타이머를 취소하고 보이는 상태를 끈다. DelayedSpinner는 EDT에서만 쓴다
        UIUtil.invokeLaterIfNeeded { spinner.stop() }
    }

    companion object {
        internal const val ADD_NOTE_ACTION_ID = "JNote.AddNote"
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}
