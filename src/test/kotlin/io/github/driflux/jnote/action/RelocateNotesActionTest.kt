package io.github.driflux.jnote.action

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import java.nio.file.Files
import java.nio.file.Path

class RelocateNotesActionTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()

    override fun tearDown() {
        try {
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
        } finally {
            super.tearDown()
        }
    }

    private fun openProjectFile(name: String): Editor {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, "class A\n")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += file
        myFixture.openFileInEditor(file)
        return myFixture.editor
    }

    private fun eventFor(action: RelocateNotesAction, editor: Editor?, file: VirtualFile?): AnActionEvent {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    fun testRegisteredInEditorPopupMenuNextToAddNote() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("JNote.RelocateNotes")
        assertTrue(action is RelocateNotesAction)
        assertEquals(JNoteBundle.message("action.relocateNotes.text"), action.templatePresentation.text)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
        val popup = (manager.getAction("EditorPopupMenu") as ActionGroup).getChildren(null).toList()
        assertEquals(popup.indexOf(manager.getAction("JNote.AddNote")) + 1, popup.indexOf(action))
    }

    fun testStartsRelocationForTheEditorFile() {
        val editor = openProjectFile("A.kt")
        val started = mutableListOf<Pair<Project, String>>()
        val action = RelocateNotesAction { project, relPath -> started += project to relPath }
        val event = eventFor(action, editor, myFixture.file.virtualFile)

        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        action.actionPerformed(event)

        assertEquals(listOf(project to "src/A.kt"), started)
    }

    fun testHiddenForFileOutsideProjectOrWithoutEditor() {
        myFixture.configureByText("Outside.kt", "class O")
        val action = RelocateNotesAction { _, _ -> fail("must not start") }
        for (event in listOf(
            eventFor(action, myFixture.editor, myFixture.file.virtualFile),
            eventFor(action, null, myFixture.file.virtualFile),
            TestActionEvent.createTestEvent(action, SimpleDataContext.EMPTY_CONTEXT),
        )) {
            action.update(event)
            assertFalse(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
        }
    }
}
