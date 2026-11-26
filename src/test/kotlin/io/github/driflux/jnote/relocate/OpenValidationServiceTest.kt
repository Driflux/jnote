package io.github.driflux.jnote.relocate

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.gutter.GutterIcons
import io.github.driflux.jnote.gutter.GutterNoteService
import io.github.driflux.jnote.gutter.NoteLineMarkerProvider
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteStatus
import io.github.driflux.jnote.project.ProjectKeyService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class OpenValidationServiceTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private val created = mutableListOf<VirtualFile>()
    private val dbService get() = DatabaseService.getInstance()

    // 검증이 updated_at을 올렸다면 지금 시각이 되어 이 값과 달라진다
    private val repo get() = NoteRepository(dbService.database, clock = { CREATED_AT })
    private val gutter get() = project.service<GutterNoteService>()
    private val validation get() = project.service<OpenValidationService>()

    private val alpha = listOf("fun alpha() {", "    return total + 1", "}")
    private val beta = listOf("fun beta() {", "    return count * 3", "}")

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-open-validation").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
    }

    override fun tearDown() {
        try {
            try {
                // 늦은 검증이 DB 경로를 바꾼 뒤 기본 경로(홈 디렉터리)의 DB를 열지 않도록 먼저 끝낸다
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

    private fun fillers(from: Int, count: Int) = (from until from + count).map { "// filler $it" }

    // 다른 테스트의 파일 이름과 겹쳐 거터 캐시 항목을 나누지 않도록 이름 앞에 Validate를 붙인다
    private fun openFile(name: String, lines: List<String>): VirtualFile {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, lines.joinToString("\n"))
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        created += vFile
        myFixture.openFileInEditor(vFile)
        return vFile
    }

    private fun note(name: String, start: Int, end: Int, snapshot: List<String>): Note =
        repo.create(NewNote.code(projectKey(), "src/$name", start, end, name, snapshot = snapshot.joinToString("\n"))).get()

    private fun stored(note: Note): Note = repo.get(note.id).get()!!

    private fun assertAnchor(expected: Note, status: NoteStatus, start: Int, end: Int) {
        val actual = stored(expected)
        assertEquals(status, actual.status)
        assertEquals(start..end, actual.startLine!!..actual.endLine!!)
    }

    /** JUnit3 러너가 test로 시작하는 메서드 안의 람다를 테스트로 오인하므로 람다는 도우미 함수에서 만든다. */
    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $message" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun idle() = gutter.isIdle && validation.isIdle

    /** 진행 중인 읽기와 검증이 끝나고, 그 끝에 EDT로 넘긴 알림과 데몬 재시작까지 처리된 상태로 만든다. */
    private fun settle() {
        // EDT에서 처리한 일이 새 읽기를 시작할 수 있으므로, 이벤트를 비운 뒤에도 할 일이 없을 때까지 되풀이한다
        while (true) {
            awaitUntil("loads and validations drained") { idle() }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (idle()) return
        }
    }

    /** 데몬 없이 제공자를 직접 불러 한 번의 패스를 흉내 낸다(NoteLineMarkerProviderTest.directPass와 같은 이유). */
    private fun directPass() {
        val sink = mutableListOf<LineMarkerInfo<*>>()
        runReadAction {
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(myFixture.editor.document)!!
            NoteLineMarkerProvider().collectSlowLineMarkers(SyntaxTraverser.psiTraverser(psi).toList(), sink)
        }
    }

    /** 파일을 연 뒤의 하이라이팅: 첫 패스가 노트를 읽고, 노트를 본 다음 패스가 검증을 예약해 끝까지 처리한다. */
    private fun highlightAfterOpening() {
        directPass()
        settle()
        directPass()
        settle()
    }

    private fun markerLines(): List<Int> {
        myFixture.doHighlighting()
        val document = myFixture.editor.document
        return DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
            .filter { it.icon === GutterIcons.NOTE || it.icon === GutterIcons.APPROX }
            .map { document.getLineNumber(it.startOffset) + 1 }
    }

    private fun countNotesChanged(): AtomicInteger {
        val count = AtomicInteger()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { count.incrementAndGet() })
        return count
    }

    private fun publishNotesChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(NotesChanged.TOPIC).notesChanged()
    }

    private fun replaceText(lines: List<String>) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.setText(lines.joinToString("\n")) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testLinesInsertedAboveMoveTheNoteAndKeepItOk() {
        val saved = note("ValidateMoved.txt", 3, 5, alpha)
        openFile("ValidateMoved.txt", fillers(1, 4) + alpha + fillers(5, 3))

        highlightAfterOpening()

        assertAnchor(saved, NoteStatus.OK, 5, 7)
        assertEquals(CREATED_AT, stored(saved).updatedAt)
    }

    fun testASlightlyChangedSnapshotBecomesApproxAtItsNewLines() {
        val saved = note("ValidateApprox.txt", 3, 5, alpha)
        openFile("ValidateApprox.txt", fillers(1, 5) + listOf("fun alpha() {", "    return total + 2", "}") + fillers(6, 3))

        highlightAfterOpening()

        assertAnchor(saved, NoteStatus.APPROX, 6, 8)
    }

    fun testANoteWhoseCodeIsGoneBecomesOrphanAndKeepsItsLines() {
        val saved = note("ValidateGone.txt", 3, 5, alpha)
        openFile("ValidateGone.txt", fillers(1, 10))

        highlightAfterOpening()

        assertAnchor(saved, NoteStatus.ORPHAN, 3, 5)
    }

    fun testAFileOverFiveThousandLinesRelocatesOnlyExactMatches() {
        val moved = note("ValidateLarge.txt", 100, 102, alpha)
        val changed = note("ValidateLarge.txt", 3000, 3002, beta)
        val changedBeta = listOf("fun beta() {", "    return count * 4", "}")
        openFile("ValidateLarge.txt", fillers(1, 102) + alpha + fillers(103, 2900) + changedBeta + fillers(3003, 3000))
        assertEquals(6_008, myFixture.editor.document.lineCount)

        highlightAfterOpening()

        assertAnchor(moved, NoteStatus.OK, 103, 105)
        assertAnchor(changed, NoteStatus.NEEDS_RELOCATE, 3000, 3002)
    }

    fun testANoteIsValidatedOncePerOpenAndAgainAfterAllItsEditorsCloseAndReopen() {
        val saved = note("ValidateOnce.txt", 3, 5, alpha)
        val file = openFile("ValidateOnce.txt", fillers(1, 4) + alpha + fillers(5, 3))
        highlightAfterOpening()
        assertAnchor(saved, NoteStatus.OK, 5, 7)

        // 열린 동안 코드를 고쳐 스냅샷이 사라져도 다시 하이라이팅하고 노트가 바뀌었다고 알려도 그대로다
        replaceText(fillers(1, 10))
        publishNotesChanged()
        settle()
        assertEquals(listOf(5), markerLines())
        settle()
        assertAnchor(saved, NoteStatus.OK, 5, 7)

        FileEditorManager.getInstance(project).closeFile(file)
        myFixture.openFileInEditor(file)
        highlightAfterOpening()

        assertAnchor(saved, NoteStatus.ORPHAN, 5, 7)
    }

    fun testNothingIsPublishedWhenNothingChanged() {
        val published = countNotesChanged()
        val saved = note("ValidateSame.txt", 5, 7, alpha)
        openFile("ValidateSame.txt", fillers(1, 4) + alpha + fillers(5, 3))

        highlightAfterOpening()

        assertAnchor(saved, NoteStatus.OK, 5, 7)
        assertEquals(0, published.get())
    }

    fun testAChangeIsPublishedExactlyOnce() {
        val published = countNotesChanged()
        note("ValidateOnceMore.txt", 3, 5, alpha)
        openFile("ValidateOnceMore.txt", fillers(1, 4) + alpha + fillers(5, 3))

        highlightAfterOpening()

        assertEquals(1, published.get())
    }

    fun testTheGutterIconMovesToTheNewStartLine() {
        note("ValidateIcon.txt", 3, 5, alpha)
        openFile("ValidateIcon.txt", fillers(1, 4) + alpha + fillers(5, 3))

        highlightAfterOpening()

        assertEquals(listOf(5), markerLines())
    }

    private companion object {
        const val CREATED_AT = 1_000L
    }
}
