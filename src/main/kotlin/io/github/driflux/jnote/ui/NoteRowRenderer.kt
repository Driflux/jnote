package io.github.driflux.jnote.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.driflux.jnote.JNoteBundle
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.BoxLayout
import javax.swing.JPanel

internal class NoteRowRenderer : JBPanel<NoteRowRenderer>(BorderLayout()), ListCellRenderer<NoteRow> {
    private val title = JBLabel().plainText()
    private val subtitle = JBLabel().plainText().apply { font = JBFont.small() }
    private val tags = JBLabel().plainText().apply { font = JBFont.small() }
    private val project = JBLabel().plainText().apply { font = JBFont.small() }
    private val badge = JBLabel(JNoteBundle.message("toolWindow.badge.ai")).apply {
        font = JBFont.small()
        border = JBUI.Borders.empty(0, 4)
    }

    init {
        border = JBUI.Borders.empty(4, 8)
        val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(title)
            add(subtitle)
            add(tags)
        }
        val tail = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(project)
            add(badge)
        }
        add(text, BorderLayout.CENTER)
        add(tail, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out NoteRow>,
        value: NoteRow,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val main = if (isSelected) list.selectionForeground else list.foreground
        val muted = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
        background = if (isSelected) list.selectionBackground else list.background
        title.text = value.title
        title.foreground = main
        subtitle.text = value.subtitle
        subtitle.foreground = muted
        tags.text = value.tags.joinToString(" ") { "#" + it.name }
        tags.foreground = muted
        tags.isVisible = value.tags.isNotEmpty()
        project.text = value.projectName.orEmpty()
        project.foreground = muted
        project.isVisible = value.projectName != null
        badge.foreground = muted
        badge.isVisible = value.aiBadge
        return this
    }
}
