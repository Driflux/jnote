package io.github.driflux.jnote.ai

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class InboxReviewTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private lateinit var inbox: Inbox
    private lateinit var db: Database
    private val rechecked = CopyOnWriteArrayList<Project>()
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("jnote-inbox-review")
        inbox = Inbox(root.resolve("inbox"))
        db = Database(root.resolve("jnote.db"))
        val collect = object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, collect)
    }

    override fun tearDown() {
        try {
            db.close()
            root.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun put(name: String, text: String) {
        Files.writeString(Files.createDirectories(inbox.dir).resolve(name), text)
    }

    private fun doc(vararg titles: String) =
        """{"format_version":1,"notes":[${titles.joinToString(",") { """{"kind":"FREE","title":"$it"}""" }}]}"""

    private fun names(dir: Path) = if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() } else emptyList()

    /** 흐름 대신 원본을 읽기만 하고 정한 결과로 끝낸다. */
    private fun fakeFlow(outcome: (List<Result<String>>) -> ImportOutcome, reads: MutableList<List<Result<String>>> = mutableListOf()) =
        { _: Project, sources: List<ImportSource>, done: (ImportOutcome) -> Unit ->
            val results = sources.map { runCatching { it.read() } }
            reads += results
            done(outcome(results))
        }

    private fun review(start: (Project, List<ImportSource>, (ImportOutcome) -> Unit) -> Unit) =
        InboxReview(inbox, start, Executor { it.run() }) { rechecked += it }

    fun testImportedFilesGoToDoneAndTheRestBackToTheInbox() {
        put("good.json", doc("a"))
        put("bad.json", "not json")
        val realFlow = { p: Project, sources: List<ImportSource>, done: (ImportOutcome) -> Unit ->
            ImportFlow(p, importer = { NoteImporter(db) }, askApproval = { _, _, _ -> true }, publish = {}).start(sources, done)
        }

        review(realFlow).review(project, inbox.scan())
        PlatformTestUtil.waitWithEventsDispatching("not rechecked", { rechecked.isNotEmpty() }, 20)

        assertEquals(listOf("a"), NoteRepository(db).list(null, allProjects = true).get().map { it.title })
        assertEquals(listOf("good.json"), names(inbox.dir.resolve(Inbox.DONE)))
        assertEquals(listOf("bad.json"), inbox.scan().map { it.path.fileName.toString() })
        assertEquals(emptyList<String>(), names(inbox.dir.resolve(Inbox.PROCESSING)))
        assertEquals(listOf(project), rechecked)
    }

    fun testDeclinedOrCancelledReviewPutsEveryFileBack() {
        put("a.json", doc("a"))
        put("b.json", doc("b"))

        review(fakeFlow({ ImportOutcome.NotApplied })).review(project, inbox.scan())

        assertEquals(listOf("a.json", "b.json"), inbox.scan().map { it.path.fileName.toString() })
        assertEquals(emptyList<String>(), names(inbox.dir.resolve(Inbox.DONE)))
    }

    fun testFileTakenByAnotherWindowIsSkippedWithAReason() {
        put("a.json", doc("a"))
        val seen = inbox.scan()
        val firstReads = mutableListOf<List<Result<String>>>()
        val secondReads = mutableListOf<List<Result<String>>>()
        // 첫 창은 읽은(선점한) 뒤 미리보기를 보는 중이라 아직 끝내지 않는다
        var finishFirst: (() -> Unit)? = null
        val first = { _: Project, sources: List<ImportSource>, done: (ImportOutcome) -> Unit ->
            firstReads += sources.map { runCatching { it.read() } }
            finishFirst = { done(ImportOutcome.Finished(ImportResult(1, 0, 0), setOf(0))) }
        }

        review(first).review(project, seen)
        review(fakeFlow({ ImportOutcome.NotApplied }, secondReads)).review(project, seen)
        finishFirst!!()

        assertTrue(firstReads.single().single().isSuccess)
        assertTrue("the second window cannot read it", secondReads.single().single().isFailure)
        assertEquals(listOf("a.json"), names(inbox.dir.resolve(Inbox.DONE)))
        assertEquals(emptyList<InboxFile>(), inbox.scan())
    }

    fun testFileRemovedBeforeReviewIsReportedByTheFlow() {
        put("a.json", doc("a"))
        val seen = inbox.scan()
        Files.delete(seen.single().path)
        val realFlow = { p: Project, sources: List<ImportSource>, done: (ImportOutcome) -> Unit ->
            ImportFlow(p, importer = { NoteImporter(db) }, askApproval = { _, _, _ -> true }, publish = {}).start(sources, done)
        }

        review(realFlow).review(project, seen)
        PlatformTestUtil.waitWithEventsDispatching("not rechecked", { rechecked.isNotEmpty() }, 20)

        assertTrue(notifications.single().content, notifications.single().content.contains("a.json"))
        assertEquals(emptyList<String>(), names(inbox.dir.resolve(Inbox.DONE)))
    }
}
