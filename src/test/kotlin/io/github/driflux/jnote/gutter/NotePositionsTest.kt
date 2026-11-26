package io.github.driflux.jnote.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun writeAndNotify(note: Note) {
        repo.update(note).get()
        notifyAndSettle()
    }

    private fun notifyAndSettle() {
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
        settle()
    }

    private fun insertLineAt(line: Int) = edit { it.insertString(lineStart(line), "new\n") }

    // 노트 줄 안에 글자를 더하는 편집은 어떤 노트의 줄도 옮기지 않는다
    private fun appendToLine(line: Int) = edit { it.insertString(lineEnd(line), " more") }

    private fun save() = runWriteAction { FileDocumentManager.getInstance().saveDocument(document) }

    private fun stored(note: Note): Note = repo.getByUid(note.uid).get()!!

    /** 쓰기는 DB 스레드에서 끝나므로 DB 값이 그 위치가 될 때까지 기다린다. */
    private fun awaitStored(note: Note, start: Int, end: Int, status: NoteStatus = note.status): Note {
        awaitUntil("${note.title} stored at $start..$end $status") {
            stored(note).let { it.startLine == start && it.endLine == end && it.status == status }
        }
        return stored(note)
    }

    /**
     * DB 스레드는 하나라 지금 맡긴 일이 끝나면 앞서 맡긴 쓰기도 끝났다. 그 뒤 EDT로 넘긴 알림까지 처리한다.
     * 아직 아무도 DB를 찾지 않았다면 기다릴 쓰기도 없으므로 DB를 만들지 않는다.
     */
    private fun drainWrites() {
        dbService.current().database?.submitAndWait { }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** 풀 때까지 DB 스레드를 막는다. 테스트가 중간에 실패해도 스레드가 영영 막히지 않도록 시간 제한을 둔다. */
    private fun blockDatabase(): CountDownLatch {
        val gate = CountDownLatch(1)
        dbService.database.submit { gate.await(10, TimeUnit.SECONDS) }
        return gate
    }

    private fun failUpdates() = dbService.database.submitAndWait { c ->
        c.createStatement().use { it.execute("CREATE TRIGGER fail_update BEFORE UPDATE ON note BEGIN SELECT RAISE(ABORT, 'boom'); END") }
    }

    private fun allowUpdates() = dbService.database.submitAndWait { c -> c.createStatement().use { it.execute("DROP TRIGGER fail_update") } }

    private fun countNotesChanged(): () -> Int {
        var count = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { count++ })
        return { count }
    }

    private fun saveFailures(): () -> List<Notification> {
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        return { shown.filter { it.title == JNoteBundle.message("gutter.error.save.title") } }
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

    fun testANewNoteThatReusesADeletedNotesIdStartsAtItsOwnLines() {
        openFile("TrackReused.txt", lines(10))
        val deleted = note("TrackReused.txt", 3, 3, "deleted")
        loadedMarkers()
        insertTwoLinesAtTop()
        assertEquals(listOf(5), drawnLines())

        repo.delete(deleted.id).get()
        val fresh = note("TrackReused.txt", 3, 3, "fresh")
        // 가장 큰 id를 지우면 새 노트가 그 id를 받는다. id로 따라갔다면 지운 노트의 마커가 옮겨 둔 5번 줄에 그린다
        assertEquals(deleted.id, fresh.id)
        notifyAndSettle()

        assertEquals(listOf(3 to "fresh"), drawn())
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

    fun testSavingWritesOnlyTheLinesTheEditMovedTheNoteTo() {
        openFile("TrackSave.txt", lines(10))
        val saved = repo.create(
            NewNote.code(projectKey(), "src/TrackSave.txt", 3, 4, "moved", body = "why", status = NoteStatus.APPROX),
        ).get()
        loadedMarkers()
        insertTwoLinesAtTop()

        save()

        // 제목, 본문, 상태, 수정 시각은 그대로다
        assertEquals(saved.copy(startLine = 5, endLine = 6), awaitStored(saved, 5, 6))
    }

    fun testSavingAfterTheNoteLinesWereDeletedMarksItForRelocationOnce() {
        openFile("TrackSaveLost.txt", lines(10))
        val saved = note("TrackSaveLost.txt", 3, 4, "lost")
        loadedMarkers()
        val changes = countNotesChanged()
        delete(lineStart(3), lineStart(5))

        save()

        // 지워지기 전 줄은 재배치 힌트로 남는다
        assertEquals(saved.copy(status = NoteStatus.NEEDS_RELOCATE), awaitStored(saved, 3, 4, NoteStatus.NEEDS_RELOCATE))
        drainWrites()
        assertEquals(1, changes())

        appendToLine(5)
        save()
        drainWrites()
        assertEquals(1, changes())
    }

    fun testClosingAnEditedFileWithoutSavingWritesTheMovedLines() {
        val file = openFile("TrackCloseWrite.txt", lines(10))
        val saved = note("TrackCloseWrite.txt", 3, 3, "closed")
        loadedMarkers()
        insertTwoLinesAtTop()

        FileEditorManager.getInstance(project).closeFile(file)

        awaitStored(saved, 5, 5)
        myFixture.openFileInEditor(file)
        // 닫혀 있던 파일의 캐시는 다시 열고 처음 그릴 때 다시 읽는다
        directPass()
        settle()
        assertEquals(listOf(5), drawnLines())
    }

    fun testReopeningBeforeTheCloseWriteFinishesEndsAtTheWrittenLines() {
        val file = openFile("TrackCloseRace.txt", lines(10))
        val saved = note("TrackCloseRace.txt", 3, 3, "race")
        loadedMarkers()
        insertTwoLinesAtTop()
        val gate = blockDatabase()
        FileEditorManager.getInstance(project).closeFile(file)
        myFixture.openFileInEditor(file)
        // 쓰기가 아직 끝나지 않아 캐시가 옛 줄이므로 새 마커는 옛 줄에서 시작한다
        assertEquals(listOf(3), drawnLines())

        gate.countDown()
        awaitStored(saved, 5, 5)
        settle()

        assertEquals(listOf(5), drawnLines())
    }

    fun testASaveThatMovesNoNoteWritesAndAnnouncesNothing() {
        openFile("TrackSaveStill.txt", lines(10))
        val saved = note("TrackSaveStill.txt", 3, 4, "still")
        loadedMarkers()
        val changes = countNotesChanged()
        appendToLine(3)

        save()

        drainWrites()
        assertEquals(saved, stored(saved))
        assertEquals(0, changes())
    }

    fun testAWriteThatChangesNoRowAnnouncesNothing() {
        openFile("TrackSaveGone.txt", lines(10))
        val saved = note("TrackSaveGone.txt", 3, 3, "gone")
        loadedMarkers()
        val changes = countNotesChanged()
        insertTwoLinesAtTop()
        // 다른 곳에서 노트를 지웠고 캐시는 아직 모른다. 쓰기는 고친 행이 없다
        repo.delete(saved.id).get()

        save()

        drainWrites()
        assertNull(repo.getByUid(saved.uid).get())
        assertEquals(0, changes())
    }

    fun testOnlyTheNoteBelowTheEditIsWritten() {
        openFile("TrackSaveBelow.txt", lines(10))
        val above = note("TrackSaveBelow.txt", 2, 2, "above")
        val below = note("TrackSaveBelow.txt", 6, 7, "below")
        loadedMarkers()
        // 다른 쓰기가 위쪽 노트를 옮겼고 캐시는 아직 모른다. 편집이 옮기지 않은 노트는 쓰지 않으므로 이 값이 남는다
        val relocated = repo.update(above.copy(startLine = 9, endLine = 9)).get()!!
        insertLineAt(4)

        save()

        assertEquals(below.copy(startLine = 7, endLine = 8), awaitStored(below, 7, 8))
        assertEquals(relocated, stored(above))
    }

    fun testAnEditAfterTheReloadIsWrittenOnTopOfTheLastSave() {
        openFile("TrackSaveTwice.txt", lines(10))
        val saved = note("TrackSaveTwice.txt", 3, 3, "twice")
        loadedMarkers()
        insertTwoLinesAtTop()
        save()
        awaitStored(saved, 5, 5)
        settle()

        insertTwoLinesAtTop()
        // 다시 읽힌 5번 줄은 이 추적기가 쓴 값이므로 마커를 그 줄로 되돌리지 않는다
        assertEquals(listOf(7), drawnLines())
        save()

        awaitStored(saved, 7, 7)
    }

    fun testTwoSavesBeforeTheFirstWriteFinishesKeepTheLaterEdits() {
        openFile("TrackSaveQuick.txt", lines(10))
        val saved = note("TrackSaveQuick.txt", 3, 3, "quick")
        loadedMarkers()
        val gate = blockDatabase()
        insertTwoLinesAtTop()
        save()
        insertTwoLinesAtTop()
        save()
        insertTwoLinesAtTop()

        gate.countDown()
        awaitStored(saved, 7, 7)
        settle()

        // 다시 읽힌 7번 줄은 두 번째 저장이 쓴 값이므로 그 뒤 편집이 옮긴 마커를 되돌리지 않는다
        assertEquals(listOf(9), drawnLines())
        save()
        awaitStored(saved, 9, 9)
    }

    fun testLinesAnotherWriterSetSurviveTheNextSave() {
        openFile("TrackSaveOther.txt", lines(10))
        val saved = note("TrackSaveOther.txt", 3, 3, "other")
        loadedMarkers()
        insertTwoLinesAtTop()
        writeAndNotify(saved.copy(startLine = 9, endLine = 9))
        assertEquals(listOf(9), drawnLines())

        save()

        drainWrites()
        assertEquals(9 to 9, stored(saved).let { it.startLine to it.endLine })
    }

    fun testAFailedWriteIsReportedOnceAndTheNextSaveWritesTheMovedLines() {
        val failures = saveFailures()
        openFile("Track<Fail>.txt", lines(10))
        val saved = note("Track<Fail>.txt", 3, 3, "fail")
        loadedMarkers()
        failUpdates()
        insertTwoLinesAtTop()

        save()

        drainWrites()
        assertEquals(saved, stored(saved))
        val content = failures().single().content
        assertTrue(content, content.contains("Track&lt;Fail&gt;.txt") && content.contains("boom"))

        allowUpdates()
        // 바뀐 것이 없는 문서를 저장하면 저장 이벤트가 오지 않으므로, 노트를 옮기지 않는 편집을 하고 저장한다.
        // 실패한 쓰기는 stored를 바꾸지 않았으므로 이 저장이 옮긴 줄을 쓴다
        appendToLine(5)
        save()
        awaitStored(saved, 5, 5)
        drainWrites()
        assertEquals(1, failures().size)
    }

    fun testASaveAfterADatabaseSwitchWritesNothingAndReportsNothing() {
        val failures = saveFailures()
        openFile("TrackSaveSwitch.txt", lines(10))
        val saved = note("TrackSaveSwitch.txt", 3, 3, "switch")
        loadedMarkers()
        insertTwoLinesAtTop()
        val changes = countNotesChanged()
        dbService.switchTo(Files.createTempDirectory(dir, "other").resolve("jnote.db"))
        // 경로를 바꿀 때 DB를 복사하므로 새 DB에도 uid가 같은 노트가 있다
        repo.restore(saved, emptyList()).get()

        save()

        drainWrites()
        assertEquals(saved, stored(saved))
        assertEquals(0, failures().size)
        assertEquals(0, changes())
    }

    fun testSavingAFileWithoutNotesTouchesNoDatabase() {
        dbService.switchTo(dir.resolve("never-created.db"))
        openFile("TrackSaveNone.txt", lines(10))
        loadedMarkers()
        val changes = countNotesChanged()
        insertTwoLinesAtTop()

        save()

        drainWrites()
        assertFalse(gutter.isTracking(document))
        assertEquals(0, changes())
        assertFalse(Files.exists(dir.resolve("never-created.db")))
        assertNull(dbService.openDatabaseOrNull())
    }
}
