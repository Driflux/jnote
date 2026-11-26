package io.github.driflux.jnote.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.anchor.CodeAnchor
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import javax.swing.JLabel

// 실제 표시와 입력은 자동으로 확인할 수 없어 구성 요소와 입력값 읽기까지만 본다
class AddNoteDialogTest : BasePlatformTestCase() {
    private val anchor = CodeAnchor("src/A.kt", 3, 5, "snap", "A.b")

    private fun <T> withDialog(block: (AddNoteDialog) -> T): T {
        val dialog = AddNoteDialog(project, anchor)
        try {
            return block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun <T> withDialog(dialog: AddNoteDialog, block: (AddNoteDialog) -> T): T {
        try {
            return block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private val saved = Note(
        7, "uid", NoteKind.CODE, "proj", "src/A.kt", "A.b", 3, 5, "snap", NoteStatus.OK,
        "Old", "old body", "Claude", "http://old", "old prompt", 1, 2,
    )

    private fun editDialog(note: Note) =
        AddNoteDialog(project, note.anchorOrNull(), NoteDraft.of(note), JNoteBundle.message("dialog.editNote.title"))

    private fun labels(dialog: AddNoteDialog) = UIUtil.findComponentsOfType(dialog.content, JLabel::class.java).map { it.text }

    fun testEditDialogIsPrefilledAndReturnsTheSameValuesUnchanged() = withDialog(editDialog(saved)) { dialog ->
        assertEquals(JNoteBundle.message("dialog.editNote.title"), dialog.title)
        assertEquals(NoteDraft("Old", "old body", "Claude", "http://old", "old prompt"), dialog.draft())
    }

    fun testEditDialogShowsTheAnchorSummaryAndExpandsTheAiSourceWhenItHasValues() = withDialog(editDialog(saved)) { dialog ->
        assertTrue(labels(dialog).containsAll(listOf("src/A.kt", "A.b", "3-5")))
        val combo = UIUtil.findComponentOfType(dialog.content, ComboBox::class.java)!!
        assertTrue("AI source must be open when it has values", combo.isVisible)
    }

    fun testEditDialogKeepsTheAiSourceCollapsedWhenEmpty() =
        withDialog(editDialog(saved.copy(aiSource = null, sourceUrl = null, prompt = null))) { dialog ->
            val combo = UIUtil.findComponentOfType(dialog.content, ComboBox::class.java)!!
            assertFalse(combo.isVisible)
        }

    fun testDialogWithoutAnchorHasNoAnchorSummary() = withDialog(AddNoteDialog(project, null)) { dialog ->
        assertFalse(labels(dialog).contains(JNoteBundle.message("dialog.addNote.anchor.file")))
        assertEquals(NoteDraft(""), dialog.draft())
    }

    fun testAiSourceSectionIsCollapsedByDefault() = withDialog { dialog ->
        val combo = UIUtil.findComponentOfType(dialog.content, ComboBox::class.java)!!
        assertFalse("AI source must start collapsed", combo.isVisible)
    }

    fun testPresetsAreOfferedAndAnyTextIsAllowed() = withDialog { dialog ->
        val combo = UIUtil.findComponentOfType(dialog.content, ComboBox::class.java)!!
        assertTrue(combo.isEditable)
        assertEquals(listOf("Claude", "ChatGPT", "Gemini"), (0 until combo.itemCount).map { combo.getItemAt(it).toString() })
        combo.editor.item = "My local model"
        assertEquals("My local model", dialog.draft().aiSource)
    }

    fun testBlankTitleIsRejectedAndNonBlankAccepted() = withDialog { dialog ->
        val titleField = UIUtil.findComponentsOfType(dialog.content, JBTextField::class.java).first()
        titleField.text = ""
        assertNotNull(dialog.doValidate())
        titleField.text = "   "
        assertNotNull(dialog.doValidate())
        titleField.text = "A title"
        assertNull(dialog.doValidate())
    }

    fun testDraftReadsTheFields() = withDialog { dialog ->
        val titleField = UIUtil.findComponentsOfType(dialog.content, JBTextField::class.java).first()
        titleField.text = "A title"
        assertEquals("A title", dialog.draft().title)
        assertEquals("", dialog.draft().aiSource)
    }
}
