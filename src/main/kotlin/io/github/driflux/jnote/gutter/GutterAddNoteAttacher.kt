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

/**
 * 거터 마우스 리스너를 편집기에 처음 한 번만 단다. EditorFactoryListener나 plugin.xml 등록 없이,
 * 프로젝트 파일의 마커 패스가 돌 때 그 문서의 주 편집기를 찾아 달기 때문에 시작 때 하는 일이 없다(ADR 0009와 같은 방식).
 * 이미 단 편집기는 사용자 데이터 한 번 조회로 건너뛴다.
 */
internal object GutterAddNoteAttacher {
    private val ATTACHED = Key.create<Any>("jnote.gutter.addNote.attached")

    /** 마커 패스(백그라운드)에서 부른다. 리스너는 EDT에서 달고, [parent]가 정리되면 함께 떼어진다. */
    fun attach(project: Project, document: Document, parent: Disposable, isParentDisposed: () -> Boolean = { false }) {
        EditorFactory.getInstance().editors(document, project).forEach { editor ->
            // 테스트 픽스처의 편집기는 종류가 UNTYPED라 함께 받는다. diff, 콘솔, 미리보기 편집기는 제외한다
            if (editor !is EditorEx || (editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.UNTYPED)) return@forEach
            val token = Any()
            // 동시에 두 패스가 와도 한 편집기에는 한 번만 단다
            if ((editor as UserDataHolderEx).putUserDataIfAbsent(ATTACHED, token) !== token) return@forEach
            ApplicationManager.getApplication().invokeLater({ install(editor, project, parent, isParentDisposed) }, project.disposed)
        }
    }

    private fun install(editor: EditorEx, project: Project, parent: Disposable, isParentDisposed: () -> Boolean) {
        if (editor.isDisposed || isParentDisposed()) return
        val gutter = editor.gutterComponentEx
        val listener = GutterAddNoteListener(editor, project, GutterGeometry.of(editor))
        val installed = Disposer.newDisposable("jnote.gutter.addNote")
        gutter.addMouseListener(listener)
        gutter.addMouseMotionListener(listener)
        Disposer.register(installed, Disposable {
            gutter.removeMouseListener(listener)
            gutter.removeMouseMotionListener(listener)
            listener.dispose()
        })
        // 편집기가 먼저 닫히면 편집기가 정리하고, 서비스가 먼저 정리되면 아래 콜백이 정리하면서 편집기 쪽 등록도 사라진다.
        // 서비스의 자식은 이 콜백뿐이라 편집기가 살아 있어도 플러그인 클래스를 붙잡는 것은 없다
        EditorUtil.disposeWithEditor(editor, installed)
        Disposer.register(parent, Disposable { Disposer.dispose(installed) })
        // New UI는 아이콘이 없는 파일의 아이콘 칸 폭이 0이라, 첫 "+"가 뜰 때 코드가 밀리지 않도록 처음부터 "+" 폭만큼 잡아 둔다
        gutter.setInitialIconAreaWidth(GutterGeometry.ICON_COLUMN_WIDTH)
        gutter.revalidateMarkup()
    }
}
