package io.github.driflux.jnote.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
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
import io.github.driflux.jnote.anchor.AnchorLines
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.db.SearchRepository
import io.github.driflux.jnote.db.TagRenameResult
import io.github.driflux.jnote.db.TagRepository
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteSummary
import io.github.driflux.jnote.model.Tag
import io.github.driflux.jnote.project.ProjectKeyService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.JPopupMenu
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class JNotePanel internal constructor(
    private val project: Project,
    private val scheduler: DelayScheduler,
    private val saver: NoteSaver = NoteSaver(),
    private val askEdit: (Project, Note, List<String>) -> NoteDraft? = AddNoteDialog::edit,
    private val askTagName: (Project, Tag, List<Tag>) -> String? = TagDialogs::askNewName,
    private val confirmTagDelete: (Project, Tag) -> Boolean = TagDialogs::confirmDelete,
    private val goToCode: (Project, Note) -> Unit = { target, note -> CodeNavigator.goTo(target, note) },
) : JBPanel<JNotePanel>(BorderLayout()), Disposable {
    constructor(project: Project) : this(project, AppDelayScheduler)

    private class Loaded(
        val projectKey: String,
        val notes: List<NoteSummary>,
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
        project,
        onGoToCode = { goToCode(project, it) },
        onDelete = { delete(it) },
        onEdit = { edit(it) },
        onRelocate = { note -> note.relPath?.let { RelocateNotesTask(project, it).queue() } },
        canRelocate = { note -> note.project != null && note.project == shownProjectKey },
        onConfirm = { confirmLocation(it) },
    )

    private var shownProjectKey: String? = null

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
        addActionListener {
            state = state.copy(filter = selectedItem as NoteFilter)
            render()
        }
    }
    internal val currentFileBox = JBCheckBox(JNoteBundle.message("toolWindow.currentFile")).apply {
        addActionListener {
            state = state.copy(currentFileOnly = isSelected, currentRelPath = currentRelPath())
            render()
        }
    }
    internal val aiOnlyBox = JBCheckBox(JNoteBundle.message("toolWindow.aiOnly")).apply {
        // 이미 읽은 행에만 걸리므로 다시 읽지 않는다
        addActionListener {
            state = state.copy(aiOnly = isSelected)
            render()
        }
    }

    // 지금 칩이 그려 낸 사전과 켜 둔 태그. 이것이 바뀔 때만 칩을 새로 만든다. EDT에서만 쓴다
    private var tagBarKey: Pair<List<Tag>, Set<Long>>? = null
    internal val tagBar = JBPanel<JBPanel<*>>(WrapLayout(FlowLayout.LEFT, 4, 2)).apply { isVisible = false }
    internal val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JNoteBundle.message("toolWindow.search.placeholder")
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onQueryTyped()
        })
    }
    private var searchTimer: Cancellable? = null

    @Volatile
    private var query = ""

    private val searchGeneration = AtomicInteger()

    internal val spinnerIcon = AsyncProcessIcon("JNote").apply { isVisible = false }

    internal val spinner = DelayedSpinner(scheduler, onVisibleChanged = {
        if (!disposed) {
            spinnerIcon.isVisible = it
            if (it) spinnerIcon.resume() else spinnerIcon.suspend()
        }
    })

    private val pendingLoads = AtomicInteger()

    // 목록 읽기만 센다. 포커스가 연달아 들어와도 읽기가 쌓이지 않게 하는 데 쓴다
    private val listLoads = AtomicInteger()
    private val rerunAfterLoad = AtomicBoolean()

    // 테스트가 읽기 횟수를 확인하는 데 쓴다
    internal val reloadsStarted = AtomicInteger()
    internal val isIdle: Boolean get() = pendingLoads.get() == 0

    internal var state = ToolWindowState()
        private set

    private val generation = AtomicInteger()

    private val detailGeneration = AtomicInteger()

    private var shownDetailUid: String? = null

    private var renderingSelection = false

    @Volatile
    private var disposed = false
    internal val focusSearchAction = DumbAwareAction.create { focusSearch() }

    init {
        Disposer.register(this, spinnerIcon)
        focusSearchAction.registerCustomShortcutSet(CommonShortcuts.getFind(), this)
        bindListKey(KeyEvent.VK_ENTER, "jnote.goToCode") { detail.goToCodeButton }
        bindListKey(KeyEvent.VK_DELETE, "jnote.delete") { detail.deleteButton }
        list.addListSelectionListener { if (!it.valueIsAdjusting && !renderingSelection) showDetailOf(list.selectedValue?.summary) }
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(JBPanel<JBPanel<*>>(WrapLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(allProjectsBox)
                add(currentFileBox)
                add(aiOnlyBox)
                add(filterCombo)
            }, BorderLayout.CENTER)
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
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = onEditorSelectionChanged()
        })
        // 다른 IDE 창이 쓴 노트를 보려고, 포커스가 패널로 들어올 때 한 번만 다시 읽는다. 타이머는 없다
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusListener = PropertyChangeListener { onFocusOwnerChanged(it.oldValue as? Component, it.newValue as? Component) }
        focusManager.addPropertyChangeListener(FOCUS_OWNER_PROPERTY, focusListener)
        Disposer.register(this) { focusManager.removePropertyChangeListener(FOCUS_OWNER_PROPERTY, focusListener) }
        // 패널에 포커스를 둔 채 다른 창에 다녀온 경우에는 포커스 소유자가 그대로여서 위 알림이 오지 않는다
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = onApplicationActivated(ideFrame.component)
            })
        // 구독을 먼저 걸어 첫 읽기 사이에 오는 알림을 놓치지 않는다
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { reload() })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(NoteSelectRequested.TOPIC, NoteSelectRequestedListener { requested, noteId -> onSelectRequested(requested, noteId) })
        reload()
    }

    private fun currentRelPath(): String? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { AnchorLines.relativePath(project.basePath, it.path) }

    private fun onEditorSelectionChanged() {
        if (disposed || !state.currentFileOnly) return
        val relPath = currentRelPath()
        if (relPath == state.currentRelPath) return
        state = state.copy(currentRelPath = relPath)
        render()
    }

    internal fun onFocusOwnerChanged(oldOwner: Component?, newOwner: Component?) {
        if (!disposed && FocusEntry.entered(this, oldOwner, newOwner)) reloadOnFocus()
    }

    /** [frame]은 활성화된 IDE 창. 다른 창이면 이 패널과 상관없다. */
    internal fun onApplicationActivated(
        frame: Component?,
        focusOwner: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
    ) {
        if (disposed || (frame != null && !SwingUtilities.isDescendingFrom(this, frame))) return
        if (FocusEntry.inside(this, focusOwner)) reloadOnFocus()
    }

    // 이미 읽는 중이면 새로 시작하지 않고 끝난 뒤 한 번만 더 읽게 표시해 둔다
    private fun reloadOnFocus() {
        if (listLoads.get() > 0) rerunAfterLoad.set(true) else reload()
    }

    internal fun focusSearch() {
        IdeFocusManager.getInstance(project).requestFocus(searchField.textEditor, true)
        searchField.textEditor.selectAll()
    }
    private fun bindListKey(keyCode: Int, name: String, button: () -> JButton) {
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name)
        list.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val target = button()
                val selectedUid = list.selectedValue?.summary?.uid
                if (selectedUid != null && selectedUid == shownDetailUid && detail.isVisible && target.isVisible) target.doClick(0)
            }
        })
    }
    fun reload() {
        val ticket = generation.incrementAndGet()
        val allProjects = allProjects
        val query = query
        reloadsStarted.incrementAndGet()
        listLoads.incrementAndGet()
        pendingLoads.incrementAndGet()
        onEdt { if (!disposed) spinner.start() }
        load(allProjects, query).whenComplete { loaded, error ->
            // 읽는 동안 포커스가 또 들어왔다면 끝난 뒤 한 번만 더 읽는다(그 읽기가 시작되기 전에 쓰인 것까지 보려고)
            if (listLoads.decrementAndGet() == 0 && rerunAfterLoad.compareAndSet(true, false) && !disposed) reload()
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

    private fun deleteOn(deletion: NoteDeletion, note: Note): CompletableFuture<*> =
        deletion.delete(note.id).whenComplete { deleted, error ->
            onEdt {
                when {
                    error != null -> ToolWindowNotifier.deleteFailed(project, error.cause ?: error)
                    deleted != null -> {
                        publishChanged()
                        ToolWindowNotifier.deleted(project) { undo(deletion, deleted) }
                    }
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

    private fun resolveKey(): CompletableFuture<String> =
        CompletableFuture.supplyAsync({ project.service<ProjectKeyService>().resolve().key }, AppExecutorUtil.getAppExecutorService())

    private fun load(allProjects: Boolean, query: String): CompletableFuture<Loaded> =
        resolveKey().thenCompose { key ->
            val database = DatabaseService.getInstance().database
            val tags = TagRepository(database)
            val notes = NoteRepository(database).listSummaries(key, allProjects)
            val dictionary = tags.list()
            val assignments = tags.assignments()
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
        val relPath = if (state.currentFileOnly) currentRelPath() else state.currentRelPath
        // 읽는 사이 검색어가 바뀌었다면 옛 검색어의 결과는 버리고, 새 검색어의 결과는 applyQuery가 따로 가져온다
        val match = loaded.match?.takeIf { it.query == state.query } ?: state.match
        state = state.copy(notes = rows, allProjects = allProjects, match = match, currentRelPath = relPath).withTags(loaded.dictionary)
        // 칩이 그대로면 다시 만들지 않는다. 다시 만들면 키보드로 칩에 있던 포커스가 사라진다
        val tagKey = state.tags to state.selectedTagIds
        if (tagKey != tagBarKey) rebuildTagBar()
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
        tagBarKey = state.tags to state.selectedTagIds
        tagBar.removeAll()
        state.tags.forEach { tag ->
            val chip = tagChip(tag.name, tag.id in state.selectedTagIds) {
                state = state.toggleTag(tag.id)
                tagBarKey = state.tags to state.selectedTagIds
                render()
            }
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
        val selectedUid = list.selectedValue?.summary?.uid
        renderingSelection = true
        try {
            model.replaceAll(state.visibleNotes)
            showEmptyText()
            val index = model.items.indexOfFirst { it.summary.uid == selectedUid }
            if (index >= 0) list.selectedIndex = index
        } finally {
            renderingSelection = false
        }
        showDetailOf(list.selectedValue?.summary)
        project.service<PendingNoteSelection>().peek()?.let { selectAndClear(it) }
    }

    private fun onSelectRequested(requested: Project, noteId: Long) {
        if (requested !== project) return
        onEdt {
            if (state.notes != null) selectAndClear(noteId)
        }
    }

    private fun selectAndClear(noteId: Long) {
        val index = model.items.indexOfFirst { it.summary.id == noteId }
        if (index < 0) return
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        project.service<PendingNoteSelection>().clear(noteId)
    }

    private fun showDetailOf(summary: NoteSummary?) {
        val ticket = detailGeneration.incrementAndGet()
        if (summary == null || summary.uid != shownDetailUid) {
            shownDetailUid = null
            detail.show(null)
        }
        if (summary == null) return
        pendingLoads.incrementAndGet()
        NoteRepository(DatabaseService.getInstance().database).get(summary.id).whenComplete { note, error ->
            pendingLoads.decrementAndGet()
            onEdt {
                if (disposed || ticket != detailGeneration.get()) return@onEdt
                when {
                    error != null -> ToolWindowNotifier.loadFailed(project, error.cause ?: error)
                    note?.uid != summary.uid -> {
                        shownDetailUid = null
                        detail.show(null)
                    }
                    else -> {
                        shownDetailUid = note.uid
                        detail.show(note)
                    }
                }
            }
        }
    }

    private fun showEmptyText() {
        val text = list.emptyText
        if (state.showEmptyState && state.currentFileOnly && state.currentRelPath == null) {
            text.text = JNoteBundle.message("toolWindow.empty.currentFile.none.title")
            text.appendLine(JNoteBundle.message("toolWindow.empty.currentFile.none.hint"))
        } else if (state.aiFilterEmptiedTheList) {
            text.text = JNoteBundle.message(if (state.currentFileOnly) "toolWindow.empty.ai.currentFile.title" else "toolWindow.empty.ai.title")
            text.appendLine(JNoteBundle.message("toolWindow.empty.ai.hint"))
        } else if (state.showEmptyState && state.tagFilterActive) {
            text.text = JNoteBundle.message("toolWindow.empty.tags.title")
            text.appendLine(JNoteBundle.message("toolWindow.empty.tags.hint"))
        } else if (state.noSearchResults) {
            text.text = JNoteBundle.message("toolWindow.search.empty.title", state.query)
            text.appendLine(JNoteBundle.message(if (state.allProjects) "toolWindow.search.empty.hint.all" else "toolWindow.search.empty.hint"))
        } else if (state.showEmptyState && state.aiOnly) {
            text.text = JNoteBundle.message(if (state.currentFileOnly) "toolWindow.empty.ai.currentFile.title" else "toolWindow.empty.ai.title")
            text.appendLine(JNoteBundle.message("toolWindow.empty.ai.hint"))
        } else if (state.showEmptyState && state.currentFileOnly) {
            text.text = JNoteBundle.message("toolWindow.empty.currentFile.title")
            text.appendLine(JNoteBundle.message("toolWindow.empty.currentFile.hint"))
        } else if (state.showEmptyState) {
            text.text = JNoteBundle.message(state.filter.emptyTitleKey)
            text.appendLine(if (state.filter == NoteFilter.FREE) JNoteBundle.message("toolWindow.empty.free.hint") else emptyHint())
        } else {
            text.clear()
        }
    }

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
        UIUtil.invokeLaterIfNeeded {
            spinner.stop()
            detail.snippetViewer.release()
        }
    }

    companion object {
        internal const val ADD_NOTE_ACTION_ID = "JNote.AddNote"
        internal const val FOCUS_OWNER_PROPERTY = "permanentFocusOwner"
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}
