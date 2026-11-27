package io.github.driflux.jnote.gutter

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.anchor.AnchorDraft
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path

class GutterAddNoteListenerTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()
    private val added = mutableListOf<AnchorDraft>()

    override fun tearDown() {
        try {
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
        } finally {
            super.tearDown()
        }
    }

    private fun openFile(name: String, text: String): EditorEx {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += file
        myFixture.openFileInEditor(file)
        return myFixture.editor as EditorEx
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }

    private fun listener(editor: EditorEx): GutterAddNoteListener {
        lateinit var created: GutterAddNoteListener
        val geometry = GutterGeometry(
            lineAt = { it.y },
            inIconArea = { it.x in 0..15 },
            onAddIcon = { it.x in 0..15 && created.shownLine == it.y },
        )
        created = GutterAddNoteListener(editor, project, geometry) { _, draft -> added += draft }
        return created
    }

    private fun mouse(editor: EditorEx, id: Int, line: Int, x: Int = 5, button: Int = MouseEvent.BUTTON1) =
        MouseEvent(editor.gutterComponentEx, id, 0L, if (id == MouseEvent.MOUSE_DRAGGED) InputEvent.BUTTON1_DOWN_MASK else 0, x, line, 1, false, button)

    fun testHoveringAnIconAreaLineShowsThePlusOnThatLine() {
        val editor = openFile("GutterAddHover.txt", lines(10))
        val l = listener(editor)

        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        assertEquals(3, l.shownLine)
        assertEquals(1, plusCount(editor))
    }

    fun testMovingToAnotherLineMovesThePlusInsteadOfAddingOne() {
        val editor = openFile("GutterAddMove.txt", lines(10))
        val l = listener(editor)

        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 6))

        assertEquals(6, l.shownLine)
        assertEquals(1, plusCount(editor))
    }

    fun testLeavingTheIconAreaOrTheGutterHidesThePlus() {
        val editor = openFile("GutterAddLeave.txt", lines(10))
        val l = listener(editor)

        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 2))
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 2, x = 40))
        assertNull(l.shownLine)

        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 2))
        l.mouseExited(mouse(editor, MouseEvent.MOUSE_EXITED, 2))
        assertNull(l.shownLine)
        assertEquals(0, plusCount(editor))
    }

    fun testNoPlusBelowTheLastLine() {
        val editor = openFile("GutterAddBelow.txt", lines(5))
        val l = listener(editor)

        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 5))

        assertNull(l.shownLine)
    }

    fun testClickingThePlusAddsANoteForThatSingleLine() {
        val editor = openFile("GutterAddClick.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 4))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 4))
        val renderer = editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer as? AddNoteGutterRenderer }.single()
        renderer.clickAction!!.actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent())

        val draft = added.single()
        assertEquals(5, draft.startLine)
        assertEquals(5, draft.endLine)
        assertEquals("line 5", draft.snapshot)
        assertEquals("src/GutterAddClick.txt", draft.relPath)
    }

    fun testDraggingDownFromThePlusAddsANoteForTheLineRangeAndSelectsIt() {
        val editor = openFile("GutterAddDrag.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 2))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 2))
        l.mouseDragged(mouse(editor, MouseEvent.MOUSE_DRAGGED, 4))
        assertEquals("line 3\nline 4\nline 5", editor.selectionModel.selectedText)
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 4))

        val draft = added.single()
        assertEquals(3, draft.startLine)
        assertEquals(5, draft.endLine)
        assertEquals("line 3\nline 4\nline 5", draft.snapshot)
    }

    fun testDraggingUpIsTheSameRangeAsDraggingDown() {
        val editor = openFile("GutterAddUp.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 6))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 6))
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 3))

        assertEquals(4 to 7, added.single().let { it.startLine to it.endLine })
    }

    fun testDraggingPastTheEndOfTheFileStopsAtTheLastLine() {
        val editor = openFile("GutterAddPastEnd.txt", lines(5))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 3))
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 99))

        assertEquals(4 to 5, added.single().let { it.startLine to it.endLine })
    }

    fun testReleasingOnTheStartLineAddsNothingHere() {
        val editor = openFile("GutterAddSame.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 3))
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 3))

        assertTrue(added.isEmpty())
    }

    fun testPressingAwayFromThePlusNeverStartsADrag() {
        val editor = openFile("GutterAddAway.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 5))
        l.mouseDragged(mouse(editor, MouseEvent.MOUSE_DRAGGED, 8))
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 8))

        assertTrue(added.isEmpty())
        assertNull(editor.selectionModel.selectedText)
    }

    fun testReturningToTheStartLineClearsTheSelectionTheDragMade() {
        val editor = openFile("GutterAddBack.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 3))
        l.mouseDragged(mouse(editor, MouseEvent.MOUSE_DRAGGED, 6))
        l.mouseDragged(mouse(editor, MouseEvent.MOUSE_DRAGGED, 3))
        assertNull(editor.selectionModel.selectedText)
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 3))

        assertTrue(added.isEmpty())
    }

    fun testTheIconAreaCoversLineNumbersAndAnEmptyIconColumn() {
        val editor = openFile("GutterAddZone.txt", lines(5))
        val geometry = GutterGeometry.of(editor)
        val gutter = editor.gutterComponentEx
        val first = gutter.annotationsAreaOffset + gutter.annotationsAreaWidth
        val end = gutter.iconAreaOffset + maxOf(gutter.iconsAreaWidth, GutterGeometry.ICON_COLUMN_WIDTH)

        assertTrue(geometry.inIconArea(mouse(editor, MouseEvent.MOUSE_MOVED, 1, x = first)))
        assertTrue(geometry.inIconArea(mouse(editor, MouseEvent.MOUSE_MOVED, 1, x = end - 1)))
        assertFalse(geometry.inIconArea(mouse(editor, MouseEvent.MOUSE_MOVED, 1, x = end)))
    }

    fun testOnlyTheLeftButtonStartsADrag() {
        val editor = openFile("GutterAddButton.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 3, button = MouseEvent.BUTTON3))
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 6, button = MouseEvent.BUTTON3))

        assertTrue(added.isEmpty())
    }

    fun testThePlusStaysPutWhileDragging() {
        val editor = openFile("GutterAddStay.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))
        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 3))

        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 7))
        l.mouseExited(mouse(editor, MouseEvent.MOUSE_EXITED, 7))

        assertEquals(3, l.shownLine)
    }

    fun testEditsAbovePushThePlusAndItsClickWithTheLine() {
        val editor = openFile("GutterAddEdit.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 4))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 4))
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "new\n") }
        val renderer = editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer as? AddNoteGutterRenderer }.single()
        renderer.clickAction!!.actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent())

        assertEquals(6, added.single().startLine)
    }

    fun testAClickActionWithoutAPressOnThePlusAddsNothing() {
        val editor = openFile("GutterAddStray.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 4))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 4, x = 40))
        val renderer = editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer as? AddNoteGutterRenderer }.single()
        renderer.clickAction!!.actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent())

        assertTrue(added.isEmpty())
    }

    fun testReleasingADragOutsideTheIconAreaHidesThePlus() {
        val editor = openFile("GutterAddOutside.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        l.mousePressed(mouse(editor, MouseEvent.MOUSE_PRESSED, 3))
        l.mouseReleased(mouse(editor, MouseEvent.MOUSE_RELEASED, 6, x = 40))

        assertNull(l.shownLine)
        assertEquals(0, plusCount(editor))
    }

    fun testDisposeRemovesThePlus() {
        val editor = openFile("GutterAddDispose.txt", lines(10))
        val l = listener(editor)
        l.mouseMoved(mouse(editor, MouseEvent.MOUSE_MOVED, 3))

        l.dispose()

        assertEquals(0, plusCount(editor))
    }

    fun testRealGeometryReadsTheLineFromTheYCoordinate() {
        val editor = openFile("GutterAddGeometry.txt", lines(20))
        val geometry = GutterGeometry.of(editor)

        for (line in listOf(0, 7, 19)) {
            val y = editor.visualPositionToXY(VisualPosition(line, 0)).y + editor.lineHeight / 2
            assertEquals(line, geometry.lineAt(mouse(editor, MouseEvent.MOUSE_MOVED, y)))
        }
    }

    private fun plusCount(editor: EditorEx) = editor.markupModel.allHighlighters.count { it.gutterIconRenderer is AddNoteGutterRenderer }
}

class GutterAddNoteAttacherTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()

    override fun tearDown() {
        try {
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
        } finally {
            super.tearDown()
        }
    }

    private fun openFile(name: String): EditorEx {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, "a\nb\nc")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += file
        myFixture.openFileInEditor(file)
        return myFixture.editor as EditorEx
    }

    private fun listeners(editor: EditorEx) = editor.gutterComponentEx.mouseListeners.count { it is GutterAddNoteListener } +
        editor.gutterComponentEx.mouseMotionListeners.count { it is GutterAddNoteListener }

    private fun attach(editor: EditorEx, parent: Disposable) {
                GutterAddNoteAttacher.attach(project, editor.document, parent)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun providerPass(editor: EditorEx) {
        val sink = mutableListOf<com.intellij.codeInsight.daemon.LineMarkerInfo<*>>()
        com.intellij.openapi.application.runReadAction {
            val psi = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(editor.document)!!
            NoteLineMarkerProvider().collectSlowLineMarkers(com.intellij.psi.SyntaxTraverser.psiTraverser(psi).toList(), sink)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testAMarkerPassOverAProjectFileAttachesTheListenersToItsEditor() {
        val editor = openFile("GutterAttachPass.txt")

        providerPass(editor)
        providerPass(editor)

        assertEquals(2, listeners(editor))
    }

    fun testAMarkerPassOverAFileOutsideTheProjectAttachesNothing() {
        val dir = Files.createTempDirectory("jnote-outside").toRealPath()
        try {
            com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
            val path = dir.resolve("Outside.txt")
            Files.writeString(path, "a\nb\nc")
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            myFixture.openFileInEditor(file)
            val editor = myFixture.editor as EditorEx

            providerPass(editor)

            assertEquals(0, listeners(editor))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun testAttachingTwiceLeavesOneMouseAndOneMotionListener() {
        val editor = openFile("GutterAttachTwice.txt")
        val parent = Disposer.newDisposable()
        try {
            attach(editor, parent)
            attach(editor, parent)

            assertEquals(2, listeners(editor))
        } finally {
            Disposer.dispose(parent)
        }
    }

    private fun childCount(parent: Disposable): Int {
        var count = 0
        Disposer.disposeChildren(parent) { count++; false }
        return count
    }

    fun testClosingTheEditorReleasesTheParentsCallback() {
        val editor = openFile("GutterAttachClose.txt")
        val parent = Disposer.newDisposable()
        try {
            attach(editor, parent)
            assertEquals(1, childCount(parent))

            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(created.last())
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertEquals(0, childCount(parent))
        } finally {
            Disposer.dispose(parent)
        }
    }

    fun testDisposingTheParentRemovesTheListeners() {
        val editor = openFile("GutterAttachParent.txt")
        val parent = Disposer.newDisposable()

        attach(editor, parent)
        Disposer.dispose(parent)

        assertEquals(0, listeners(editor))
    }
}