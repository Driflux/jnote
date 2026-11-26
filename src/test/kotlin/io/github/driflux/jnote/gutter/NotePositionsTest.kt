package io.github.driflux.jnote.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path

class NotePositionsTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val created = mutableListOf<VirtualFile>()
    private val dbService get() = DatabaseService.getInstance()
    private val repo get() = NoteRepository(dbService.database)
    private val gutter get() = project.service<GutterNoteService>()
    private val document: Document get() = myFixture.editor.document

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-positions").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            try {
                // 늦은 읽기가 DB 경로를 바꾼 뒤 기본 경로(홈 디렉터리)의 DB를 열지 않도록 먼저 끝낸다
                settle()
                runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
            } finally {
                dbService.switchTo(DatabasePaths.default())
                dir.toFile().deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    private fun projectKey(): String =
        ApplicationManager.getApplication().executeOnPooledThread<String> { project.service<ProjectKeyService>().resolve().key }.get()

    // 다른 테스트의 파일 이름과 겹쳐 캐시 항목을 나누지 않도록 이름 앞에 Track을 붙인다
    private fun openFile(name: String, text: String): VirtualFile {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += vFile
        myFixture.openFileInEditor(vFile)
        return vFile
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "    line $it" }

    private fun note(name: String, start: Int, end: Int, title: String) =
        repo.create(NewNote.code(projectKey(), "src/$name", start, end, title)).get()

    /** JUnit3 러너가 test로 시작하는 메서드 안의 람다를 테스트로 오인하므로 람다는 도우미 함수에서 만든다. */
    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $message" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun settle() {
        while (true) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (gutter.isIdle) return
            awaitUntil("gutter loads drained") { gutter.isIdle }
        }
    }

    /** 첫 패스가 읽기를 시작하고, 읽기가 끝난 뒤 데몬의 패스(백그라운드 스레드)가 마커를 만들고 그린다. */
    private fun loadedMarkers(): List<Int> {
        assertTrue(directPass().isEmpty())
        settle()
        myFixture.doHighlighting()
        return DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
            .filter { it.icon === GutterIcons.NOTE || it.icon === GutterIcons.APPROX }
            .map { lineOf(it) }
    }

    /**
     * 파일 전체를 처음부터 다시 그리는 패스. 데몬은 편집되지 않은 영역의 기존 마커를 편집과 함께 옮겨 두므로,
     * 제공자가 계산한 줄을 보려면 데몬 없이 제공자를 직접 부른다.
     */
    private fun directPass(): List<LineMarkerInfo<*>> {
        val sink = mutableListOf<LineMarkerInfo<*>>()
        runReadAction {
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(document)!!
            NoteLineMarkerProvider().collectSlowLineMarkers(SyntaxTraverser.psiTraverser(psi).toList(), sink)
        }
        return sink
    }

    private fun lineOf(info: LineMarkerInfo<*>) = document.getLineNumber(info.startOffset) + 1

    private fun drawnLines(): List<Int> = directPass().map { lineOf(it) }

    private fun drawn(): List<Pair<Int, String?>> = directPass().map { lineOf(it) to it.lineMarkerTooltip }

    private fun lineStart(line: Int) = document.getLineStartOffset(line - 1)

    private fun lineEnd(line: Int) = document.getLineEndOffset(line - 1)

    private fun insertTwoLinesAtTop() = edit { it.insertString(0, "new a\nnew b\n") }

    private fun delete(start: Int, end: Int) = edit { it.deleteString(start, end) }

    private fun edit(change: (Document) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { change(document) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    /** 다른 쓰기가 DB를 바꾸고 알린 것처럼 하고, 캐시가 다시 읽을 때까지 기다린다. */
    private fun writeAndNotify(note: Note) {
        repo.update(note).get()
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        settle()
    }

    private fun closedElsewhere(file: VirtualFile) {
        project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER).fileClosed(FileEditorManager.getInstance(project), file)
    }

    fun testTheIconFollowsLinesInsertedAboveTheNote() {
        openFile("TrackInsert.txt", lines(10))
        note("TrackInsert.txt", 3, 4, "moved")
        assertEquals(listOf(3), loadedMarkers())

        insertTwoLinesAtTop()

        assertEquals(listOf(5), drawnLines())
    }

    fun testDeletingTheNoteLinesWithTheirLineBreakHidesOnlyThatNote() {
        openFile("TrackDelete.txt", lines(10))
        note("TrackDelete.txt", 3, 4, "deleted")
        note("TrackDelete.txt", 7, 7, "kept")
        assertEquals(listOf(3, 7), loadedMarkers())

        delete(lineStart(3), lineStart(5))

        assertEquals(listOf(5 to "kept"), drawn())
    }

    fun testDeletingExactlyTheTextOfTheNoteLinesHidesIt() {
        openFile("TrackClear.txt", lines(10))
        note("TrackClear.txt", 3, 4, "cleared")
        note("TrackClear.txt", 7, 7, "kept")
        assertEquals(listOf(3, 7), loadedMarkers())

        // 줄 3과 4의 글자만 지우면 빈 줄 하나가 남으므로 다른 노트는 한 줄 올라간다
        delete(lineStart(3), lineEnd(4))

        assertEquals(listOf(6 to "kept"), drawn())
    }

    fun testAChangeThatKeepsTheDatabaseLinesLeavesTheIconWhereTheEditMovedIt() {
        openFile("TrackTitle.txt", lines(10))
        val saved = note("TrackTitle.txt", 3, 3, "before")
        loadedMarkers()
        insertTwoLinesAtTop()

        writeAndNotify(saved.copy(title = "after"))

        assertEquals(listOf(5 to "after"), drawn())
    }

    fun testNewDatabaseLinesFromAnotherWriterMoveTheIconThere() {
        openFile("TrackRelocated.txt", lines(10))
        val saved = note("TrackRelocated.txt", 3, 3, "relocated")
        loadedMarkers()
        insertTwoLinesAtTop()
        assertEquals(listOf(5), drawnLines())

        writeAndNotify(saved.copy(startLine = 8, endLine = 8))

        assertEquals(listOf(8), drawnLines())
    }

    fun testAnOrphanedNoteDrawsNothingUntilItIsPlacedAgain() {
        val file = openFile("TrackOrphan.txt", lines(10))
        val saved = note("TrackOrphan.txt", 3, 3, "orphan")
        loadedMarkers()
        insertTwoLinesAtTop()

        writeAndNotify(saved.copy(status = NoteStatus.ORPHAN))
        // 다시 읽은 캐시에 노트가 있으므로 아이콘이 없는 것은 상태 때문이다
        assertEquals(NoteStatus.ORPHAN, gutter.notesFor(file, "src/TrackOrphan.txt")!!.single().status)
        assertEquals(emptyList<Int>(), drawnLines())

        writeAndNotify(saved.copy(startLine = 9, endLine = 9))

        assertEquals(listOf(9), drawnLines())
    }

    fun testANoteBackFromOrphanAtItsOldLinesIsDrawnAtTheDatabaseLines() {
        openFile("TrackOrphanBack.txt", lines(10))
        val saved = note("TrackOrphanBack.txt", 3, 3, "back")
        loadedMarkers()
        insertTwoLinesAtTop()
        writeAndNotify(saved.copy(status = NoteStatus.ORPHAN))
        assertEquals(emptyList<Int>(), drawnLines())

        writeAndNotify(saved)

        // ORPHAN이 된 노트는 추적을 멈추므로, 편집이 옮겨 둔 5번 줄이 아니라 DB 줄에서 다시 시작한다
        assertEquals(listOf(3), drawnLines())
    }

    fun testClosingTheFileInEveryEditorDropsItsMarkers() {
        val file = openFile("TrackClosed.txt", lines(10))
        note("TrackClosed.txt", 3, 3, "closed")
        loadedMarkers()
        val tracked = document
        assertTrue(gutter.isTracking(tracked))

        // 분할 창 하나만 닫혀 파일이 아직 열려 있으면 그대로 둔다
        closedElsewhere(file)
        assertTrue(gutter.isTracking(tracked))
        val other = openFile("TrackOther.txt", lines(3))
        FileEditorManager.getInstance(project).closeFile(other)
        assertTrue(gutter.isTracking(tracked))

        FileEditorManager.getInstance(project).closeFile(file)

        assertFalse(gutter.isTracking(tracked))
    }

    fun testSwitchingTheDatabaseDropsTheMarkers() {
        openFile("TrackSwitch.txt", lines(10))
        val old = note("TrackSwitch.txt", 3, 3, "old database")
        loadedMarkers()
        insertTwoLinesAtTop()
        assertEquals(listOf(5), drawnLines())

        dbService.switchTo(Files.createTempDirectory(dir, "other").resolve("jnote.db"))
        val fresh = note("TrackSwitch.txt", 3, 3, "new database")
        // 새 DB의 첫 노트라 옛 노트와 id가 같다. 옛 마커가 남았다면 이 노트를 편집이 옮긴 5번 줄에 그린다
        assertEquals(old.id, fresh.id)
        assertTrue(directPass().isEmpty())
        assertFalse(gutter.isTracking(document))
        settle()

        assertEquals(listOf(3 to "new database"), drawn())
    }
}
