package io.github.driflux.jnote.gutter

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import io.github.driflux.jnote.ui.AppDelayScheduler

internal object GutterAddNoteAttacher {
    private val ATTACHED = Key.create<Any>("jnote.gutter.addNote.attached")

    fun attach(
        project: Project,
        document: Document,
        parent: Disposable,
        isParentDisposed: () -> Boolean = { false },
        source: PreviewSource = PreviewSource.None,
    ) {
        EditorFactory.getInstance().editors(document, project).forEach { editor ->
            if (editor !is EditorEx || (editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.UNTYPED)) return@forEach
            val token = Any()
            if ((editor as UserDataHolderEx).putUserDataIfAbsent(ATTACHED, token) !== token) return@forEach
            ApplicationManager.getApplication().invokeLater({ install(editor, project, parent, isParentDisposed, source) }, project.disposed)
        }
    }

    private fun install(editor: EditorEx, project: Project, parent: Disposable, isParentDisposed: () -> Boolean, source: PreviewSource) {
        if (editor.isDisposed || isParentDisposed()) return
        val gutter = editor.gutterComponentEx
        val popup = NotePreviewPopup(editor) { source.database()?.let { GutterNotePreviewActions(project, it) } }
        val preview = GutterPreviewController(AppDelayScheduler, { line -> source.notesAt(editor.document, line) }, popup)
        val listener = GutterAddNoteListener(editor, project, GutterGeometry.of(editor), preview = preview)
        val installed = Disposer.newDisposable("jnote.gutter.addNote")
        gutter.addMouseListener(listener)
        gutter.addMouseMotionListener(listener)
        Disposer.register(installed, Disposable {
            gutter.removeMouseListener(listener)
            gutter.removeMouseMotionListener(listener)
            listener.dispose()
        })
        EditorUtil.disposeWithEditor(editor, installed)
        val link = Disposable { Disposer.dispose(installed) }
        Disposer.register(parent, link)
        Disposer.register(installed, Disposable { Disposer.dispose(link) })
        gutter.setInitialIconAreaWidth(GutterGeometry.ICON_COLUMN_WIDTH)
        gutter.revalidateMarkup()
    }
}