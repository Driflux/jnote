package io.github.driflux.jnote.gutter

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import io.github.driflux.jnote.model.NoteStatus
import java.awt.Component
import java.awt.Container

class NotePreviewPanelTest : BasePlatformTestCase() {
    private val calls = mutableListOf<String>()
    private val actions = object : PreviewActions {
        override fun edit(noteId: Long) { calls += "edit $noteId" }

        override fun open(noteId: Long) { calls += "open $noteId" }

        override fun delete(noteId: Long) { calls += "delete $noteId" }
    }

    private fun note(id: Long, title: String = "title $id", body: String = "body $id", tags: List<String> = listOf("a", "b"), ai: String? = null, snippet: String = "code $id") =
        CachedNote(id, "u$id", title, body, tags, ai, NoteStatus.OK, 1, 3, snippet)

    private fun all(root: Component): List<Component> =
        listOf(root) + ((root as? Container)?.components?.flatMap { all(it) } ?: emptyList())

    private inline fun <reified T : Component> find(root: Component, name: String): T? = all(root).filterIsInstance<T>().firstOrNull { it.name == name }

    private fun labels(root: Component) = all(root).filterIsInstance<JBLabel>().map { it.text }

    fun testACardShowsTitleTagsBodySnippetAndTheThreeActions() {
        val panel = NotePreviewPanel.build(listOf(note(1)), actions)

        assertTrue(labels(panel).contains("title 1"))
        assertEquals("#a #b", find<JBLabel>(panel, "tags")!!.text)
        assertEquals("body 1", find<JBTextArea>(panel, "body")!!.text)
        assertEquals("code 1", find<JBTextArea>(panel, "snippet")!!.text)
        assertNotNull(find<ActionLink>(panel, "edit"))
        assertNotNull(find<ActionLink>(panel, "open"))
        assertNotNull(find<ActionLink>(panel, "delete"))
    }

    fun testTheAiBadgeAppearsOnlyForNotesWithAnAiSource() {
        assertNotNull(find<JBLabel>(NotePreviewPanel.build(listOf(note(1, ai = "Claude")), actions), "aiBadge"))
        assertNull(find<JBLabel>(NotePreviewPanel.build(listOf(note(2)), actions), "aiBadge"))
        assertNull(find<JBLabel>(NotePreviewPanel.build(listOf(note(3, ai = "  ")), actions), "aiBadge"))
    }

    fun testEmptyPartsAreLeftOut() {
        val panel = NotePreviewPanel.build(listOf(note(1, body = "", tags = emptyList(), snippet = "")), actions)

        assertNull(find<JBLabel>(panel, "tags"))
        assertNull(find<JBTextArea>(panel, "body"))
        assertNull(find<JBTextArea>(panel, "snippet"))
    }

    fun testTheActionLinksCallBackWithTheirNoteId() {
        val panel = NotePreviewPanel.build(listOf(note(7)), actions)

        for (name in listOf("edit", "open", "delete")) find<ActionLink>(panel, name)!!.doClick()

        assertEquals(listOf("edit 7", "open 7", "delete 7"), calls)
    }

    fun testOnlyTheFirstThreeCardsShowAndTheRestAreCounted() {
        val panel = NotePreviewPanel.build((1L..5L).map { note(it) }, actions)

        assertEquals(3, all(panel).filterIsInstance<ActionLink>().count { it.name == "edit" })
        assertTrue(labels(panel).any { it.contains("2 more notes") })
    }

    fun testNoteTextIsShownAsPlainTextNotHtml() {
        val panel = NotePreviewPanel.build(listOf(note(1, title = "<html><b>x</b>", tags = listOf("<html>t"), body = "<script>")), actions)

        assertEquals(true, find<JBLabel>(panel, "title")!!.getClientProperty("html.disable"))
        assertEquals(true, find<JBLabel>(panel, "tags")!!.getClientProperty("html.disable"))
        assertTrue(labels(panel).contains("<html><b>x</b>"))
        assertEquals("<script>", find<JBTextArea>(panel, "body")!!.text)
    }

    fun testThePopupOpensWithoutTakingFocusAndClosesThroughItsHandle() {
        myFixture.configureByText("PreviewPopup.txt", "a\nb\nc")
        val editor = myFixture.editor as com.intellij.openapi.editor.ex.EditorEx
        var entered = 0
        val handle = NotePreviewPopup(editor, actions).show(listOf(note(1)), 10, { entered++ }, {})

        assertNotNull(handle)
        assertTrue(handle!!.isOpen)
        handle.close()
        assertFalse(handle.isOpen)
    }
}
