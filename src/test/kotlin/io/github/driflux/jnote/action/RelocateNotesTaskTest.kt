package io.github.driflux.jnote.action

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.NotesChanged
import io.github.driflux.jnote.NotesChangedListener
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RelocateNotesTaskTest : BasePlatformTestCase() {
    private val created = mutableListOf<VirtualFile>()
    private lateinit var dir: Path
    private lateinit var db: Database
    private lateinit var repo: NoteRepository
    private val published = AtomicInteger()
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-relocate")
        db = Database(dir.resolve("jnote.db"))
        repo = NoteRepository(db)
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(NotesChanged.TOPIC, NotesChangedListener { published.incrementAndGet() })
        val collect = object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, collect)
    }

    override fun tearDown() {
        try {
            runWriteAction { created.forEach { if (it.isValid) it.delete(this) } }
            db.close()
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private val snippet = listOf(
        "fun compute(total: Int): Int {",
        "    val doubled = total * 2",
        "    return doubled + 1",
        "}",
    )

    private fun filler(n: Int, tag: String) = List(n) { "// $tag $it unrelated filler text" }

    private fun writeProjectFile(name: String, lines: List<String>): VirtualFile {
        val path = Path.of(project.basePath!!, "src", name)
        Files.createDirectories(path.parent)
        Files.writeString(path, lines.joinToString("\n"))
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!.also { created += it }
    }

    private fun note(title: String, start: Int, snapshot: List<String>, relPath: String = "src/A.kt"): Note =
        repo.create(NewNote.code(KEY, relPath, start, start + snapshot.size - 1, title, body = "body", snapshot = snapshot.joinToString("\n"))).get()

    private fun task(relPath: String = "src/A.kt", projectKey: () -> String = { KEY }) =
        RelocateNotesTask(project, relPath, repository = { repo }, projectKey = { projectKey() })

    private fun runAndAwaitNotification(task: RelocateNotesTask) {
        ProgressManager.getInstance().run(task)
        PlatformTestUtil.waitWithEventsDispatching("no notification", { notifications.isNotEmpty() }, 20)
    }

    fun testRelocatesMovedEditedAndMissingNotes() {
        val average = listOf("fun average(values: List<Int>): Int {", "    val sum = values.sum()", "    return sum / values.size", "}")
        val edited = listOf(average[0], "    val total = values.sum()", "    return total / values.size", average[3])
        writeProjectFile("A.kt", filler(30, "top") + snippet + filler(10, "mid") + edited + filler(5, "end"))
        val moved = note("moved", 1, snippet)
        val approx = note("edited", 40, average)
        val missing = note("missing", 7, listOf("class Removed {", "    val gone = true", "}"))
        val untouched = note("other file", 1, snippet, relPath = "src/B.kt")

        runAndAwaitNotification(task())

        assertEquals(moved.copy(startLine = 31, endLine = 34), repo.get(moved.id).get())
        assertEquals(approx.copy(startLine = 45, endLine = 48, status = NoteStatus.APPROX), repo.get(approx.id).get())
        assertEquals(missing.copy(status = NoteStatus.ORPHAN), repo.get(missing.id).get())
        assertEquals(untouched, repo.get(untouched.id).get())
        assertEquals(1, published.get())
        val shown = notifications.single()
        assertEquals(NotificationType.INFORMATION, shown.type)
        assertEquals(JNoteBundle.message("notification.relocate.done.content", 1, 1, 1), shown.content)
    }

    fun testReadsUnsavedEditorText() {
        val file = writeProjectFile("A.kt", filler(3, "top") + snippet)
        val moved = note("moved", 4, snippet)
        myFixture.openFileInEditor(file)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, filler(10, "typed").joinToString("\n", postfix = "\n")) }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))

        runAndAwaitNotification(task())

        assertEquals(14, repo.get(moved.id).get()?.startLine)
    }

    fun testNothingChangedPublishesNothing() {
        writeProjectFile("A.kt", filler(3, "top") + snippet)
        val same = note("same", 4, snippet)

        runAndAwaitNotification(task())

        assertEquals(same, repo.get(same.id).get())
        assertEquals(0, published.get())
        assertEquals(JNoteBundle.message("notification.relocate.done.content", 1, 0, 0), notifications.single().content)
    }

    fun testCancelDuringRelocationChangesNothing() {
        writeProjectFile("A.kt", filler(30, "top") + snippet + filler(5, "mid") + snippet.map { "$it // copy" })
        val first = note("first", 1, snippet)
        val second = note("second", 2, snippet.map { "$it // copy" })
        val stopped = AtomicBoolean()
        // 첫 노트를 끝내고 진행률을 올리는 순간 취소한다. 둘째 노트는 아직 찾지 않았다
        val indicator = object : ProgressIndicatorBase() {
            override fun setFraction(fraction: Double) {
                super.setFraction(fraction)
                cancel()
            }

            override fun stop() {
                super.stop()
                stopped.set(true)
            }
        }

        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task(), indicator)
        PlatformTestUtil.waitWithEventsDispatching("task did not stop", { stopped.get() }, 20)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(first, repo.get(first.id).get())
        assertEquals(second, repo.get(second.id).get())
        assertEquals(0, published.get())
        assertTrue(notifications.isEmpty())
    }

    fun testCancelAfterTheLastNoteStillWritesAndReports() {
        writeProjectFile("A.kt", filler(30, "top") + snippet)
        val moved = note("moved", 1, snippet)
        val stopped = AtomicBoolean()
        // 하나뿐인 노트를 찾고 진행률을 1로 올리는 순간 취소한다. 남은 일은 쓰기 하나다
        val indicator = object : ProgressIndicatorBase() {
            override fun setFraction(fraction: Double) {
                super.setFraction(fraction)
                cancel()
            }

            override fun stop() {
                super.stop()
                stopped.set(true)
            }
        }

        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task(), indicator)
        PlatformTestUtil.waitWithEventsDispatching("task did not stop", { stopped.get() }, 20)
        PlatformTestUtil.waitWithEventsDispatching("no notification", { notifications.isNotEmpty() }, 20)

        assertEquals(31, repo.get(moved.id).get()?.startLine)
        assertEquals(1, published.get())
        assertEquals(JNoteBundle.message("notification.relocate.done.content", 1, 0, 0), notifications.single().content)
    }

    fun testFileWithoutNotesSaysSo() {
        writeProjectFile("A.kt", snippet)

        runAndAwaitNotification(task())

        assertEquals(JNoteBundle.message("notification.relocate.none.content"), notifications.single().content)
    }

    fun testMissingFileIsReportedAndChangesNothing() {
        val orphanCandidate = note("gone", 1, snippet, relPath = "src/Gone.kt")

        runAndAwaitNotification(task(relPath = "src/Gone.kt"))

        assertEquals(orphanCandidate, repo.get(orphanCandidate.id).get())
        assertEquals(NotificationType.WARNING, notifications.single().type)
    }

    fun testDatabaseFailureIsReported() {
        writeProjectFile("A.kt", snippet)
        db.close()

        runAndAwaitNotification(task())

        assertEquals(NotificationType.ERROR, notifications.single().type)
        assertEquals(0, published.get())
    }

    private companion object {
        const val KEY = "key"
    }
}
