package io.github.driflux.jnote.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import io.github.driflux.jnote.anchor.CodeAnchor

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

    fun testDraftReadsTheFields() = withDialog { dialog ->
        val titleField = UIUtil.findComponentsOfType(dialog.content, JBTextField::class.java).first()
        titleField.text = "A title"
        assertEquals("A title", dialog.draft().title)
        assertEquals("", dialog.draft().aiSource)
    }
}
