package io.github.driflux.jnote.anchor

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class CodeAnchorsTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()

    override fun tearDown() {
        try {
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
        } finally {
            super.tearDown()
        }
    }

    // 라이트 픽스처의 temp:// 파일은 프로젝트 루트 밖이므로 루트 아래에 진짜 파일을 만든다
    private fun openProjectFile(name: String, text: String): Editor {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += vFile
        myFixture.openFileInEditor(vFile)
        return myFixture.editor
    }

    private val source = "class A {\n    fun b() {\n        first()\n        second()\n    }\n}\n"

    fun testSelectionGivesRangeSnapshotAndPath() {
        val editor = openProjectFile("A.kt", source)
        val start = source.indexOf("first")
        editor.selectionModel.setSelection(start, source.indexOf("second()") + "second()".length)
        val draft = CodeAnchors.fromEditor(project, editor)!!
        assertEquals("src/A.kt", draft.relPath)
        assertEquals(3, draft.startLine)
        assertEquals(4, draft.endLine)
        assertEquals("        first()\n        second()", draft.snapshot)

        val anchor = ReadAction.compute<CodeAnchor, Throwable> { CodeAnchors.resolve(project, draft) }
        assertEquals(CodeAnchor("src/A.kt", 3, 4, draft.snapshot, "A.b"), anchor)
    }

    fun testSelectionEndingAtColumnZeroExcludesLastLine() {
        val editor = openProjectFile("A.kt", source)
        editor.selectionModel.setSelection(source.indexOf("    fun b"), source.indexOf("        first"))
        val draft = CodeAnchors.fromEditor(project, editor)!!
        assertEquals(2, draft.startLine)
        assertEquals(2, draft.endLine)
    }

    fun testNoSelectionUsesCaretLine() {
        val editor = openProjectFile("A.kt", source)
        editor.caretModel.moveToOffset(source.indexOf("second") + 2)
        val draft = CodeAnchors.fromEditor(project, editor)!!
        assertEquals(4, draft.startLine)
        assertEquals(4, draft.endLine)
        assertEquals("        second()", draft.snapshot)
    }

    fun testFileOutsideProjectIsNotEligible() {
        myFixture.configureByText("Outside.kt", "class O")
        assertNull(CodeAnchors.fromEditor(project, myFixture.editor))
    }

    fun testExplicitLineRange() {
        val editor = openProjectFile("A.kt", source)
        val draft = CodeAnchors.fromLines(project, myFixture.file.virtualFile, editor.document, 3, 4)!!
        assertEquals(3, draft.startLine)
        assertEquals(4, draft.endLine)
        assertEquals("        first()\n        second()", draft.snapshot)
    }

    fun testExplicitLineRangeIsOrderedAndBoundsChecked() {
        val editor = openProjectFile("A.kt", source)
        val file = myFixture.file.virtualFile
        val reversed = CodeAnchors.fromLines(project, file, editor.document, 4, 3)!!
        assertEquals(3, reversed.startLine)
        assertEquals(4, reversed.endLine)
        assertNull(CodeAnchors.fromLines(project, file, editor.document, 0, 2))
        assertNull(CodeAnchors.fromLines(project, file, editor.document, 1, editor.document.lineCount + 1))
    }

    fun testNameIsNullInPlainTextFile() {
        val editor = openProjectFile("notes.txt", "hello\nworld\n")
        val draft = CodeAnchors.fromEditor(project, editor)!!
        val anchor = ReadAction.compute<CodeAnchor, Throwable> { CodeAnchors.resolve(project, draft) }
        assertNull(anchor.className)
    }
}
