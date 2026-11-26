package io.github.driflux.jnote.ui

import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.WrapLayout
import io.github.driflux.jnote.JNoteBundle
import java.awt.FlowLayout
import javax.swing.JToggleButton

/** 이름을 눌러 켜고 끄는 태그 칩. 다이얼로그와 Tool Window 필터가 함께 쓴다. */
internal fun tagChip(name: String, selected: Boolean, onToggle: () -> Unit): JToggleButton =
    JToggleButton(name, selected).apply {
        isFocusable = true
        addActionListener { onToggle() }
    }

/**
 * 다이얼로그의 태그 입력. 사전의 태그와 이미 고른 태그를 칩으로 보여 주고, 입력란에 새 이름을 치면
 * 칩으로 더한다(저장할 때 사전에 등록된다). [selection]이 고른 값의 유일한 원천이다.
 */
internal class TagChipsPanel(private val selection: TagSelection) : JBPanel<TagChipsPanel>(WrapLayout(FlowLayout.LEFT, 4, 2)) {
    private var known: List<String> = emptyList()

    // 엔터는 다이얼로그를 닫지 않고 칩으로 더한다(액션 리스너가 있으면 기본 버튼보다 먼저 처리된다)
    internal val input = JBTextField(12).apply {
        emptyText.text = JNoteBundle.message("dialog.addNote.tags.input.hint")
        addActionListener { commitInput() }
    }

    internal val chips: List<JToggleButton> get() = components.filterIsInstance<JToggleButton>()

    init {
        rebuild()
    }

    /** 사전이 늦게 도착해도 이미 고른 태그는 그대로 둔다. */
    fun setKnown(names: List<String>) {
        known = names
        rebuild()
    }

    /** 입력란에 남은 글자를 칩으로 확정한다. 저장 직전에도 불러 쓴다. */
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
                // 사전에 없는 새 칩은 끄면 사라져야 하므로 다시 그린다
                rebuild()
            })
        }
        add(input)
        revalidate()
        repaint()
    }
}
