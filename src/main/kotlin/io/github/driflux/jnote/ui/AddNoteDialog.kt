package io.github.driflux.jnote.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.anchor.CodeAnchor
import javax.swing.JComponent

class AddNoteDialog(project: Project, private val anchor: CodeAnchor) : DialogWrapper(project) {
    private val titleField = JBTextField()
    private val bodyArea = JBTextArea(8, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val aiNameCombo = ComboBox(AI_PRESETS.toTypedArray()).apply {
        isEditable = true
        selectedItem = ""
    }
    private val linkField = JBTextField()
    private val promptArea = JBTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    // 테스트가 같은 패널을 읽을 수 있게 한 번만 만든다. init 블록이 init()을 부르므로 그보다 먼저 선언한다
    internal val content: JComponent by lazy { buildContent() }

    init {
        title = JNoteBundle.message("dialog.addNote.title")
        init()
    }

    override fun createCenterPanel(): JComponent = content

    private fun buildContent(): JComponent = panel {
        row(JNoteBundle.message("dialog.addNote.anchor.file")) { label(anchor.relPath) }
        row(JNoteBundle.message("dialog.addNote.anchor.class")) {
            label(anchor.className ?: JNoteBundle.message("dialog.addNote.anchor.class.none"))
        }
        row(JNoteBundle.message("dialog.addNote.anchor.lines")) { label(anchor.linesLabel()) }
        row(JNoteBundle.message("dialog.addNote.noteTitle.label")) {
            cell(titleField).align(AlignX.FILL)
        }
        row { label(JNoteBundle.message("dialog.addNote.body.label")) }
        row { cell(JBScrollPane(bodyArea)).align(Align.FILL) }.resizableRow()
        collapsibleGroup(JNoteBundle.message("dialog.addNote.aiSource.group")) {
            row(JNoteBundle.message("dialog.addNote.aiSource.name")) { cell(aiNameCombo).align(AlignX.FILL) }
            row(JNoteBundle.message("dialog.addNote.aiSource.link")) { cell(linkField).align(AlignX.FILL) }
            row { label(JNoteBundle.message("dialog.addNote.aiSource.prompt")) }
            row { cell(JBScrollPane(promptArea)).align(AlignX.FILL) }
        }.expanded = false
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    public override fun doValidate(): ValidationInfo? =
        if (titleField.text.isBlank()) ValidationInfo(JNoteBundle.message("dialog.addNote.noteTitle.required"), titleField) else null

    fun draft(): NoteDraft = NoteDraft(
        title = titleField.text,
        body = bodyArea.text,
        // 편집 가능한 콤보는 입력 중인 글자가 selectedItem에 반영되기 전이라 편집기 값을 읽는다
        aiSource = aiNameCombo.editor.item?.toString().orEmpty(),
        sourceUrl = linkField.text,
        prompt = promptArea.text,
    )

    companion object {
        /** 프리셋일 뿐이며 콤보에는 어떤 글자든 입력할 수 있다. */
        val AI_PRESETS = listOf("Claude", "ChatGPT", "Gemini")

        /** 확인으로 닫으면 입력값, 취소하면 null. UI 스레드에서 호출한다. */
        fun ask(project: Project, anchor: CodeAnchor): NoteDraft? {
            val dialog = AddNoteDialog(project, anchor)
            return if (dialog.showAndGet()) dialog.draft() else null
        }
    }
}
