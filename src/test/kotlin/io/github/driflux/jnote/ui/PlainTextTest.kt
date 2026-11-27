package io.github.driflux.jnote.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import io.github.driflux.jnote.gutter.CachedNote
import io.github.driflux.jnote.gutter.NoteChooser
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.model.NoteSummary
import io.github.driflux.jnote.model.Tag
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.plaf.basic.BasicHTML

/** html 태그로 시작하는 노트 텍스트가 Swing의 HTML 뷰로 그려지지 않는지 면마다 확인한다. */
class PlainTextTest : BasePlatformTestCase() {
    private val markup = "<html><b>bold</b>"

    private fun hasHtmlView(component: JComponent) = component.getClientProperty("html") != null

    private fun allComponents(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + (if (it is Container) allComponents(it) else emptyList()) }

    private fun markupLabels(root: Container) = allComponents(root).filterIsInstance<JBLabel>().filter { it.text.contains("<html>") }

    fun testPlainTextKeepsTheLabelOutOfHtmlMode() {
        assertTrue("control: a bare label does get the HTML view", hasHtmlView(JBLabel().apply { text = markup }))
        assertFalse(hasHtmlView(JBLabel().plainText().apply { text = markup }))
    }

    fun testRowRendererShowsEveryTextAsPlain() {
        val summary = NoteSummary(1, "u", NoteKind.CODE, markup, "src/A.kt", markup, 1, 2, NoteStatus.OK, markup, markup, 1)
        val row = NoteRow(summary, subtitle = markup, projectName = markup, aiBadge = true, tags = listOf(Tag(1, markup)))
        val renderer = NoteRowRenderer()
        renderer.getListCellRendererComponent(JList(arrayOf(row)), row, 0, false, false)
        val labels = markupLabels(renderer)
        assertEquals("title, subtitle, tags and project carry the markup", 4, labels.size)
        labels.forEach { assertFalse("html view on '${it.text}'", hasHtmlView(it)) }
    }

    fun testDetailPanelShowsEveryTextAsPlain() {
        val note = Note(
            1, "u", NoteKind.CODE, "p", markup, markup, 1, 2, "code", NoteStatus.OK, markup, "body",
            markup, markup, "prompt", 1, 1,
        )
        val panel = NoteDetailPanel({}, {}, {}, {}, { false }, {})
        panel.show(note)
        listOf(panel.titleLabel, panel.classLabel, panel.locationLabel, panel.aiSourceLabel, panel.aiUrlLabel).forEach {
            assertTrue("the markup is shown as text: '${it.text}'", it.text.startsWith(markup))
            assertFalse("html view on '${it.text}'", hasHtmlView(it))
        }
    }

    fun testTagChipShowsTheNameAsPlain() {
        val chip = tagChip(markup, true) {}
        assertEquals(markup, chip.text)
        assertTrue(chip.isSelected)
        assertFalse(hasHtmlView(chip))
    }

    fun testPopupTextCannotStartAHtmlDocument() {
        val note = CachedNote(1, "u", markup, "", emptyList(), null, NoteStatus.OK, 1, 1, "")
        val step = NoteChooser.Step(project, listOf(note))
        val shown = step.getTextFor(note)
        assertFalse(BasicHTML.isHTMLString(shown))
        assertTrue(shown.endsWith(markup))
        assertEquals("plain titles are untouched", "plain", step.getTextFor(note.copy(title = "plain")))
    }
}
