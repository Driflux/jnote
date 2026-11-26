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
import io.github.driflux.jnote.model.Note
import javax.swing.JComponent

/**
 * 노트를 쓰는 다이얼로그. 추가와 편집이 함께 쓴다.
 * 앵커는 읽기 전용 요약으로만 보여 주고, null이면(FREE 노트) 요약을 그리지 않는다.
 * [initial]은 편집할 때 채워 둘 값이다.
 */
class AddNoteDialog(
    project: Project,
    private val anchor: CodeAnchor?,
    private val initial: NoteDraft = NoteDraft(""),
    dialogTitle: String = JNoteBundle.message("dialog.addNote.title"),
) : DialogWrapper(project) {
    private val titleField = JBTextField(initial.title)
    private val bodyArea = JBTextArea(8, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        text = initial.body
    }
    private val aiNameCombo = ComboBox(AI_PRESETS.toTypedArray()).apply {
        isEditable = true
        selectedItem = initial.aiSource
    }
    private val linkField = JBTextField(initial.sourceUrl)
    private val promptArea = JBTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        text = initial.prompt
    }

    // 테스트가 같은 패널을 읽을 수 있게 한 번만 만든다. init 블록이 init()을 부르므로 그보다 먼저 선언한다
    internal val content: JComponent by lazy { buildContent() }

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent = content

    private fun buildContent(): JComponent = panel {
        if (anchor != null) {
            row(JNoteBundle.message("dialog.addNote.anchor.file")) { label(anchor.relPath) }
            row(JNoteBundle.message("dialog.addNote.anchor.class")) {
                label(anchor.className ?: JNoteBundle.message("dialog.addNote.anchor.class.none"))
            }
            row(JNoteBundle.message("dialog.addNote.anchor.lines")) { label(anchor.linesLabel()) }
        }
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
        }.expanded = initial.hasAiSource()
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
        fun ask(project: Project, anchor: CodeAnchor): NoteDraft? = show(AddNoteDialog(project, anchor))

        /** 저장된 [note]의 값을 채워 편집한다. 앵커는 보여 주기만 하고 바꾸지 않는다. UI 스레드에서 호출한다. */
        fun edit(project: Project, note: Note): NoteDraft? = show(
            AddNoteDialog(project, note.anchorOrNull(), NoteDraft.of(note), JNoteBundle.message("dialog.editNote.title")),
        )

        private fun show(dialog: AddNoteDialog): NoteDraft? = if (dialog.showAndGet()) dialog.draft() else null
    }
}
