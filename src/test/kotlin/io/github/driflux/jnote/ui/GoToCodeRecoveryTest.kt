package io.github.driflux.jnote.ui

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GoToCodeRecoveryTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var root: VirtualFile
    private val dbService get() = DatabaseService.getInstance()

    // 복구가 updated_at을 올렸다면 지금 시각이 되어 이 값과 달라진다
    private val repo get() = NoteRepository(dbService.database, clock = { CREATED_AT })

    private val alpha = listOf("fun alpha() {", "    return total + 1", "}")
    private val changedAlpha = listOf("fun alpha() {", "    return total + 2", "}")

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-recovery").toRealPath()
        dbService.switchTo(dir.resolve("jnote.db"))
        // FilenameIndex가 basePath 아래 실제 파일을 보도록 이 테스트 동안만 콘텐츠 루트로 등록한다
        val rootPath = Path.of(project.basePath!!, ROOT)
        Files.createDirectories(rootPath)
        root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath)!!
        PsiTestUtil.addContentRoot(module, root)
    }

    override fun tearDown() {
        try {
            try {
                PsiTestUtil.removeContentEntry(module, root)
                Path.of(root.path).toFile().deleteRecursively()
                LocalFileSystem.getInstance().refreshNioFiles(listOf(Path.of(root.path)))
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

    private fun writeFile(relPath: String, lines: List<String>) {
        val path = Path.of(project.basePath!!, relPath)
        Files.createDirectories(path.parent)
        Files.writeString(path, lines.joinToString("\n"))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun note(
        relPath: String,
        start: Int,
        end: Int,
        snapshot: List<String>?,
        className: String? = null,
        status: NoteStatus = NoteStatus.OK,
    ): Note = repo.create(
        NewNote.code(projectKey(), relPath, start, end, "n", className = className, snapshot = snapshot?.joinToString("\n"), status = status),
    ).get()

    private fun stored(note: Note): Note = repo.get(note.id).get()!!

    private fun goTo(note: Note): Boolean = PlatformTestUtil.waitForFuture(CodeNavigator.goTo(project, note), 10_000)

    private fun countNotesChanged(): AtomicInteger {
        val count = AtomicInteger()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { count.incrementAndGet() })
        return count
    }

    private fun captureNotifications(): List<Notification> {
        val shown = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        return shown
    }

    private fun assertFileMissingShown(shown: List<Notification>, relPath: String) {
        val title = JNoteBundle.message("toolWindow.error.fileMissing.title")
        assertTrue(shown.toString(), shown.any { it.title == title && it.content.contains(relPath) })
    }

    private fun assertOpenedAt(relPath: String, line: Int) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(Path.of(project.basePath!!, relPath).toString(), editor.virtualFile.path)
        assertEquals(line - 1, editor.caretModel.logicalPosition.line)
    }

    private fun assertAnchor(note: Note, relPath: String, status: NoteStatus, start: Int, end: Int) {
        val actual = stored(note)
        assertEquals(relPath, actual.relPath)
        assertEquals(status, actual.status)
        assertEquals(start..end, actual.startLine!!..actual.endLine!!)
        assertEquals(CREATED_AT, actual.updatedAt)
    }

    fun testAFileMovedToAnotherFolderIsFoundByNameAndOpenedAtTheNewLine() {
        val published = countNotesChanged()
        writeFile("$ROOT/moved/Helpers.kt", fillers(1, 4) + alpha + fillers(5, 3))
        val saved = note("$ROOT/old/Helpers.kt", 3, 5, alpha)

        assertTrue(goTo(saved))

        assertOpenedAt("$ROOT/moved/Helpers.kt", 5)
        assertAnchor(saved, "$ROOT/moved/Helpers.kt", NoteStatus.OK, 5, 7)
        assertEquals(1, published.get())
    }

    fun testAFileRenamedToItsClassNameIsFoundByTheClassName() {
        writeFile("$ROOT/ui/Widget.kt", fillers(1, 2) + alpha)
        val saved = note("$ROOT/old/Helpers.kt", 3, 5, alpha, className = "Widget.alpha")

        assertTrue(goTo(saved))

        assertOpenedAt("$ROOT/ui/Widget.kt", 3)
        assertAnchor(saved, "$ROOT/ui/Widget.kt", NoteStatus.OK, 3, 5)
    }

    fun testSlightlyChangedCodeInTheCandidateBecomesApprox() {
        writeFile("$ROOT/moved/Approx.kt", fillers(1, 5) + changedAlpha)
        val saved = note("$ROOT/old/Approx.kt", 3, 5, alpha)

        assertTrue(goTo(saved))

        assertAnchor(saved, "$ROOT/moved/Approx.kt", NoteStatus.APPROX, 6, 8)
    }

    fun testAnExactMatchInALaterCandidateWinsOverAnApproximateOne() {
        writeFile("$ROOT/a/Pick.kt", changedAlpha)
        writeFile("$ROOT/b/Pick.kt", fillers(1, 1) + alpha)
        val saved = note("$ROOT/old/Pick.kt", 3, 5, alpha)

        assertTrue(goTo(saved))

        assertAnchor(saved, "$ROOT/b/Pick.kt", NoteStatus.OK, 2, 4)
    }

    fun testWithoutACandidateTheNoteBecomesOrphanAndKeepsItsPath() {
        val shown = captureNotifications()
        val published = countNotesChanged()
        val saved = note("$ROOT/old/Nowhere.kt", 3, 5, alpha)

        assertFalse(goTo(saved))

        assertAnchor(saved, "$ROOT/old/Nowhere.kt", NoteStatus.ORPHAN, 3, 5)
        assertFileMissingShown(shown, "$ROOT/old/Nowhere.kt")
        assertEquals(1, published.get())
        assertNull(FileEditorManager.getInstance(project).selectedTextEditor)
    }

    fun testACandidateWithoutTheCodeLeavesTheNoteOrphan() {
        val shown = captureNotifications()
        writeFile("$ROOT/moved/Gone.kt", fillers(1, 10))
        val saved = note("$ROOT/old/Gone.kt", 3, 5, alpha)

        assertFalse(goTo(saved))

        assertAnchor(saved, "$ROOT/old/Gone.kt", NoteStatus.ORPHAN, 3, 5)
        assertFileMissingShown(shown, "$ROOT/old/Gone.kt")
    }

    fun testALargeCandidateWithoutAnExactMatchLeavesTheNoteNeedingRelocation() {
        writeFile("$ROOT/moved/Large.kt", changedAlpha + fillers(1, 5_000))
        val saved = note("$ROOT/old/Large.kt", 3, 5, alpha)

        assertFalse(goTo(saved))

        assertAnchor(saved, "$ROOT/old/Large.kt", NoteStatus.NEEDS_RELOCATE, 3, 5)
    }

    fun testAnApproxNoteIsNotMovedToALargeCandidateWithoutAnExactMatch() {
        writeFile("$ROOT/moved/LargeApprox.kt", changedAlpha + fillers(1, 5_000))
        val saved = note("$ROOT/old/LargeApprox.kt", 3, 5, alpha, status = NoteStatus.APPROX)

        assertFalse(goTo(saved))

        assertAnchor(saved, "$ROOT/old/LargeApprox.kt", NoteStatus.NEEDS_RELOCATE, 3, 5)
    }

    fun testANoteWithoutSnapshotIsNotSearchedAndStaysAsItIs() {
        val shown = captureNotifications()
        writeFile("$ROOT/moved/Bare.kt", alpha)
        val saved = note("$ROOT/old/Bare.kt", 1, 3, null)

        assertFalse(goTo(saved))

        assertEquals(saved, stored(saved))
        assertFileMissingShown(shown, "$ROOT/old/Bare.kt")
    }

    private companion object {
        const val CREATED_AT = 1_000L
        const val ROOT = "recovery"
    }
}
