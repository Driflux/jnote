package io.github.driflux.jnote.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.model.Note
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal class NoteDetailPanel(
    private val onGoToCode: (Note) -> Unit,
    private val onDelete: (Note) -> Unit,
    private val onEdit: (Note) -> Unit,
    private val onRelocate: (Note) -> Unit,
    private val canRelocate: (Note) -> Boolean,
    private val onConfirm: (Note) -> Unit,
) : JBPanel<NoteDetailPanel>(BorderLayout()) {
    private var note: Note? = null

    internal val titleLabel = JBLabel().plainText().apply { font = JBFont.label().asBold() }
    internal val classLabel = JBLabel().plainText()
    internal val locationLabel = JBLabel().plainText().apply { foreground = UIUtil.getContextHelpForeground() }
    internal val approxLabel = JBLabel(JNoteBundle.message("toolWindow.detail.approx"), AllIcons.General.BalloonWarning, JBLabel.LEFT)
    internal val snippetArea = readOnlyArea(wrap = false)
    internal val bodyArea = readOnlyArea(wrap = true)
    internal val aiSourceLabel = JBLabel().plainText()
    internal val aiUrlLabel = JBLabel().plainText()
    internal val aiPromptArea = readOnlyArea(wrap = true)
    internal val goToCodeButton = JButton(JNoteBundle.message("toolWindow.detail.goToCode")).apply {
        addActionListener { note?.let(onGoToCode) }
    }
    internal val relocateButton = JButton(JNoteBundle.message("toolWindow.detail.relocate")).apply {
        addActionListener { note?.let(onRelocate) }
    }
    internal val editButton = JButton(JNoteBundle.message("toolWindow.detail.edit")).apply {
        addActionListener { note?.let(onEdit) }
    }
    internal val confirmButton = JButton(JNoteBundle.message("toolWindow.detail.confirmLocation")).apply {
        addActionListener { note?.let(onConfirm) }
    }
    internal val deleteButton = JButton(JNoteBundle.message("toolWindow.detail.delete")).apply {
        addActionListener { note?.let(onDelete) }
    }
    internal val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(goToCodeButton)
        add(relocateButton)
        add(confirmButton)
        add(editButton)
        add(deleteButton)
    }

    private val snippetSection = section("toolWindow.detail.snippet", JBScrollPane(snippetArea))
    private val bodySection = section("toolWindow.detail.note", bodyArea)
    private val aiSection = section("toolWindow.detail.aiSource", column(aiSourceLabel, aiUrlLabel, aiPromptArea))

    init {
        border = JBUI.Borders.empty(8)
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLabel)
            add(classLabel)
            add(locationLabel)
            add(approxLabel)
            add(snippetSection)
            add(bodySection)
            add(aiSection)
        }
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
        show(null)
    }

    fun show(note: Note?) {
        this.note = note
        isVisible = note != null
        val detail = note?.let(NoteDetails::of) ?: return
        titleLabel.text = detail.title
        classLabel.text = detail.className.orEmpty()
        classLabel.isVisible = detail.className != null
        locationLabel.text = detail.location.orEmpty()
        locationLabel.isVisible = detail.location != null
        snippetArea.text = detail.snippet.orEmpty()
        snippetSection.isVisible = detail.snippet != null
        bodyArea.text = detail.body
        bodyArea.font = if (detail.monospaceBody) monospace() else JBFont.label()
        bodySection.isVisible = detail.body.isNotEmpty()
        aiSourceLabel.text = detail.ai?.source.orEmpty()
        aiUrlLabel.text = detail.ai?.sourceUrl.orEmpty()
        aiUrlLabel.isVisible = detail.ai?.sourceUrl != null
        aiPromptArea.text = detail.ai?.prompt.orEmpty()
        aiPromptArea.isVisible = detail.ai?.prompt != null
        aiSection.isVisible = detail.ai != null
        goToCodeButton.isVisible = detail.canGoToCode
        relocateButton.isVisible = detail.canGoToCode && canRelocate(note)
        approxLabel.isVisible = detail.approximate
        confirmButton.isVisible = detail.approximate
    }

    private fun readOnlyArea(wrap: Boolean) = JBTextArea().apply {
        isEditable = false
        lineWrap = wrap
        wrapStyleWord = wrap
        font = if (wrap) JBFont.label() else monospace()
        border = JBUI.Borders.empty()
    }

    private fun monospace() = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

    private fun column(vararg parts: JComponent) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        parts.forEach { add(it) }
    }

    private fun section(headerKey: String, body: JComponent) = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        add(JBLabel(JNoteBundle.message(headerKey)).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }
}
