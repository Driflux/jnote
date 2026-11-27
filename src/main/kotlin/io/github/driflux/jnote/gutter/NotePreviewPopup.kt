package io.github.driflux.jnote.gutter

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.driflux.jnote.JNoteBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 미리보기 카드에서 고른 동작. */
internal interface PreviewActions {
    fun edit(noteId: Long)

    fun open(noteId: Long)

    fun delete(noteId: Long)
}

/** 미리보기 내용. 캐시된 [CachedNote]만 읽으므로 DB나 파일에 닿지 않는다. */
internal object NotePreviewPanel {
    private const val WIDTH = 420

    fun build(notes: List<CachedNote>, actions: PreviewActions): JComponent {
        val shown = notes.take(NotePreviewText.MAX_CARDS)
        val root = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 8)
        }
        shown.forEachIndexed { index, note ->
            if (index > 0) root.add(JBPanel<JBPanel<*>>().apply { preferredSize = Dimension(WIDTH, JBUI.scale(8)) })
            root.add(card(note, actions))
        }
        val hidden = notes.size - shown.size
        if (hidden > 0) root.add(JBLabel(JNoteBundle.message("gutter.preview.more", hidden)).apply { font = JBFont.small() })
        return root
    }

    private fun card(note: CachedNote, actions: PreviewActions): JComponent {
        val card = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(4)))
        val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(plainLabel(note.title).apply {
                name = "title"
                font = JBFont.label().asBold()
            })
            if (!note.aiSource.isNullOrBlank()) {
                add(JBLabel(JNoteBundle.message("toolWindow.badge.ai")).apply {
                    name = "aiBadge"
                    font = JBFont.small()
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
        }
        val details = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        if (note.tags.isNotEmpty()) {
            details.add(plainLabel(NotePreviewText.tags(note.tags)).apply {
                name = "tags"
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        if (note.body.isNotBlank()) details.add(textArea("body", NotePreviewText.body(note.body), monospaced = false))
        if (note.snippetHead.isNotBlank()) details.add(textArea("snippet", note.snippetHead, monospaced = true))
        val links = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
            add(ActionLink(JNoteBundle.message("toolWindow.detail.edit")) { actions.edit(note.id) }.apply { name = "edit" })
            add(ActionLink(JNoteBundle.message("gutter.preview.open")) { actions.open(note.id) }.apply { name = "open" })
            add(ActionLink(JNoteBundle.message("toolWindow.detail.delete")) { actions.delete(note.id) }.apply { name = "delete" })
        }
        card.add(header, BorderLayout.NORTH)
        card.add(details, BorderLayout.CENTER)
        card.add(links, BorderLayout.SOUTH)
        card.preferredSize = Dimension(JBUI.scale(WIDTH), card.preferredSize.height)
        return card
    }

    /** 가져온 노트의 글이 <html>로 시작해도 HTML로 그려지지 않는다. 글을 넣기 전에 끄므로 HTML 뷰가 만들어지지 않는다. */
    internal fun plainLabel(text: String): JBLabel = JBLabel().apply {
        putClientProperty("html.disable", true)
        this.text = text
    }

    private fun textArea(name: String, text: String, monospaced: Boolean) = JBTextArea(text).apply {
        this.name = name
        isEditable = false
        isOpaque = false
        lineWrap = !monospaced
        wrapStyleWord = true
        border = JBUI.Borders.empty(2, 0)
        if (monospaced) font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.smallFont().size)
        alignmentX = Component.LEFT_ALIGNMENT
    }
}

/** 팝업으로 미리보기를 띄운다. 편집기 포커스를 가져가지 않고, 거터 오른쪽 호버한 줄 높이에 놓는다. */
internal class NotePreviewPopup(private val editor: EditorEx, private val actionsFor: () -> PreviewActions?) : PreviewPresenter {
    override fun show(notes: List<CachedNote>, anchorY: Int, onEntered: () -> Unit, onExited: () -> Unit): PreviewHandle? {
        if (editor.isDisposed) return null
        // 카드는 노트 번호만 알므로, 노트를 읽어 온 DB에 묶인 동작을 이 팝업을 띄울 때 만든다
        val actions = actionsFor() ?: return null
        lateinit var popup: JBPopup
        val closing = object : PreviewActions {
            override fun edit(noteId: Long) = run { popup.cancel(); actions.edit(noteId) }

            override fun open(noteId: Long) = run { popup.cancel(); actions.open(noteId) }

            override fun delete(noteId: Long) = run { popup.cancel(); actions.delete(noteId) }
        }
        val content = NotePreviewPanel.build(notes, closing)
        watchMouse(content, onEntered, onExited)
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()
        val gutter = editor.gutterComponentEx
        popup.show(RelativePoint(gutter, Point(gutter.width, anchorY)))
        return object : PreviewHandle {
            override val isOpen: Boolean get() = !popup.isDisposed

            override fun close() = popup.cancel()
        }
    }

    // 자식 컴포넌트 경계에서는 나감과 들어옴이 이어서 오므로, 닫기를 예약했다가 곧 취소하는 것으로 충분하다
    private fun watchMouse(root: Container, onEntered: () -> Unit, onExited: () -> Unit) {
        val listener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = onEntered()

            override fun mouseExited(e: MouseEvent) = onExited()
        }
        fun attach(component: Component) {
            component.addMouseListener(listener)
            if (component is Container) component.components.forEach(::attach)
        }
        attach(root)
    }
}
