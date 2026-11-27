package io.github.driflux.jnote.ui

import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.WrapLayout
import io.github.driflux.jnote.JNoteBundle
import java.awt.FlowLayout
import javax.swing.JToggleButton

internal fun tagChip(name: String, selected: Boolean, onToggle: () -> Unit): JToggleButton =
    JToggleButton().plainText().apply {
        text = name
        isSelected = selected
        isFocusable = true
        addActionListener { onToggle() }
    }

internal class TagChipsPanel(private val selection: TagSelection) : JBPanel<TagChipsPanel>(WrapLayout(FlowLayout.LEFT, 4, 2)) {
    private var known: List<String> = emptyList()

    internal val input = JBTextField(12).apply {
        emptyText.text = JNoteBundle.message("dialog.addNote.tags.input.hint")
        addActionListener { commitInput() }
    }

    internal val chips: List<JToggleButton> get() = components.filterIsInstance<JToggleButton>()

    init {
        rebuild()
    }

    fun setKnown(names: List<String>) {
        known = names
        rebuild()
    }

    fun commitInput() {
        selection.add(input.text)
        input.text = ""
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        (known + selection.names).distinct().sorted().forEach { name ->
            add(tagChip(name, selection.contains(name)) {
                selection.toggle(name)
                rebuild()
            })
        }
        add(input)
        revalidate()
        repaint()
    }
}
