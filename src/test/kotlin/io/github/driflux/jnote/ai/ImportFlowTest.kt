package io.github.driflux.jnote.ai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ImportFlowTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var db: Database
    private lateinit var repo: NoteRepository
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val published = AtomicInteger()
    private val asked = CopyOnWriteArrayList<ImportPlan>()
    private val applied = CopyOnWriteArrayList<ImportResult>()
    private val outcomes = CopyOnWriteArrayList<ImportOutcome>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-import-flow")
        db = Database(dir.resolve("jnote.db"))
        repo = NoteRepository(db)
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
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun doc(vararg notes: String) = """{"format_version":1,"notes":[${notes.joinToString(",")}]}"""

    private fun flow(answer: (ImportPlan) -> Boolean) = ImportFlow(
        project,
        importer = { NoteImporter(db) },
        askApproval = { _, plan, _ -> asked += plan; answer(plan) },
        publish = { published.incrementAndGet() },
    )

    private fun run(flow: ImportFlow, vararg texts: String) {
        flow.start(texts.mapIndexed { i, text -> ImportSource("doc$i.json") { text } }) { outcomes += it; (it as? ImportOutcome.Finished)?.let { f -> applied += f.result } }
        PlatformTestUtil.waitWithEventsDispatching("no notification", { notifications.isNotEmpty() }, 20)
    }

    private fun titles() = repo.list(null, allProjects = true).get().map { it.title }.sorted()

    fun testApprovedImportIsAppliedAndReportedWithSkippedItems() {
        run(flow { true }, doc("""{"kind":"FREE","title":"a"}""", """{"kind":"FREE","title":"b"}""", """{"kind":"CODE","title":"c"}"""))

        assertEquals(listOf("a", "b"), titles())
        assertEquals(1, asked.size)
        assertEquals(1, published.get())
        assertEquals(listOf(ImportResult(2, 0, 0)), applied)
        assertEquals(setOf(0), (outcomes.single() as ImportOutcome.Finished).decodedSources)
        val shown = notifications.single()
        assertEquals(NotificationType.WARNING, shown.type)
        assertTrue(shown.content, shown.content.startsWith(JNoteBundle.message("notification.import.done.content", 2, 0, 0)))
        assertTrue(shown.content, shown.content.contains("anchor"))
    }

    fun testDeclinedPreviewWritesNothing() {
        flow { false }.start(listOf(ImportSource("d.json") { doc("""{"kind":"FREE","title":"a"}""") })) { outcomes += it }
        PlatformTestUtil.waitWithEventsDispatching("not finished", { outcomes.isNotEmpty() }, 20)
        assertEquals(listOf(ImportOutcome.NotApplied), outcomes)

        assertEquals(emptyList<String>(), titles())
        assertEquals(0, published.get())
        assertTrue(notifications.isEmpty())
    }

    fun testNothingNewSkipsThePreview() {
        val note = repo.create(NewNote.free("same")).get()

        run(flow { true }, doc("""{"uid":"${note.uid}","kind":"FREE","title":"same"}"""))

        assertTrue(asked.isEmpty())
        assertEquals(NotificationType.INFORMATION, notifications.single().type)
        assertEquals(JNoteBundle.message("notification.import.nothing.title"), notifications.single().title)
        assertEquals(listOf(ImportResult(0, 0, 1)), applied)
    }

    fun testRejectedDocumentIsAnErrorWithoutPreview() {
        run(flow { true }, "not json")

        assertTrue(asked.isEmpty())
        assertEquals(NotificationType.ERROR, notifications.single().type)
        assertTrue(notifications.single().content.contains("doc0.json"))
        assertTrue(applied.isEmpty())
        assertEquals(listOf(ImportOutcome.NotApplied), outcomes)
    }

    fun testChangeAfterThePreviewShowsANewPreview() {
        val note = repo.create(NewNote.free("plain")).get()
        val answers = AtomicInteger()

        run(
            flow {
                // 첫 미리보기를 보는 사이 다른 IDE가 본문을 쓴다
                if (answers.incrementAndGet() == 1) repo.update(note.copy(body = "meanwhile")).get()
                true
            },
            doc("""{"uid":"${note.uid}","kind":"FREE","title":"retitled"}"""),
        )

        assertEquals(2, asked.size)
        assertEquals(listOf(true), asked[1].updates.map { it.emptiedBody })
        assertEquals("retitled", repo.get(note.id).get()!!.title)
        assertEquals(listOf(ImportResult(0, 1, 0)), applied)
    }

    fun testApplyUsesTheDatabaseThePreviewWasPlannedOn() {
        val second = Database(dir.resolve("second.db"))
        var current = db
        try {
            val switching = ImportFlow(
                project,
                importer = { NoteImporter(current) },
                askApproval = { _, plan, _ ->
                    asked += plan
                    // 미리보기를 보는 사이 DB 경로가 바뀌었다
                    current = second
                    true
                },
                publish = { published.incrementAndGet() },
            )
            run(switching, doc("""{"kind":"FREE","title":"planned on the first"}"""))

            assertEquals(listOf("planned on the first"), titles())
            assertEquals(emptyList<Any>(), NoteRepository(second).list(null, allProjects = true).get())
        } finally {
            second.close()
        }
    }

    fun testFailingPreviewStillFinishesAsNotApplied() {
        val failing = ImportFlow(project, importer = { NoteImporter(db) }, askApproval = { _, _, _ -> error("dialog failed") }, publish = {})
        // 테스트에서는 작업이 동기로 돌아 실패가 호출한 쪽으로 그대로 올라온다
        try {
            failing.start(listOf(ImportSource("d.json") { doc("""{"kind":"FREE","title":"a"}""") })) { outcomes += it }
            PlatformTestUtil.waitWithEventsDispatching("not finished", { outcomes.isNotEmpty() }, 20)
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf(ImportOutcome.NotApplied), outcomes)
        assertEquals(emptyList<String>(), titles())
    }

    fun testFailingPublishAfterCommitStillFinishes() {
        val failing = ImportFlow(project, importer = { NoteImporter(db) }, askApproval = { _, _, _ -> true }, publish = { error("publish failed") })
        // 테스트에서는 작업이 동기로 돌아 실패가 호출한 쪽으로 그대로 올라온다
        try {
            failing.start(listOf(ImportSource("d.json") { doc("""{"kind":"FREE","title":"a"}""") })) { outcomes += it }
            PlatformTestUtil.waitWithEventsDispatching("not finished", { outcomes.isNotEmpty() }, 20)
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf("a"), titles())
        assertTrue(outcomes.single() is ImportOutcome.Finished)
    }

    fun testFileActionIsRegisteredAndPassesTheChosenFile() {
        assertTrue(ActionManager.getInstance().getAction("JNote.ImportFromFile") is ImportFromFileAction)
        val file = dir.resolve("in.json").also { Files.writeString(it, "text") }
        val started = CopyOnWriteArrayList<List<ImportSource>>()
        val event = AnActionEvent.createFromDataContext(
            ActionPlaces.MAIN_MENU,
            Presentation(),
            SimpleDataContext.getProjectContext(project),
        )

        ImportFromFileAction({ null }, { _: Project, sources -> started += sources }).actionPerformed(event)
        assertTrue(started.isEmpty())

        ImportFromFileAction({ file }, { _: Project, sources -> started += sources }).actionPerformed(event)
        assertEquals("in.json", started.single().single().name)
        assertEquals("text", started.single().single().read())
    }
}
