package io.github.driflux.jnote.ai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.NoteRepository
import io.github.driflux.jnote.model.NewNote
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteSummary
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class ExportNotesActionTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var db: Database
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-export-action")
        db = Database(dir.resolve("jnote.db"))
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

    private fun event(action: AnAction, selected: List<NoteSummary>?): AnActionEvent {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (selected != null) builder.add(SELECTED_NOTES, selected)
        return AnActionEvent.createFromDataContext(ActionPlaces.MAIN_MENU, Presentation(), builder.build())
    }

    private fun note(id: Long) = NoteSummary(id, "uid-$id", io.github.driflux.jnote.model.NoteKind.FREE, null, null, null, null, null,
        io.github.driflux.jnote.model.NoteStatus.OK, "t", null, 0)

    private fun recorder(target: Path?, calls: MutableList<Pair<Path, Set<String>?>>) =
        Triple<(Project) -> Path?, (Project, Path, Set<String>?) -> Unit, Unit>({ target }, { _, path, ids -> calls += path to ids }, Unit)

    fun testActionsAreRegisteredInToolsJNoteMenu() {
        val manager = ActionManager.getInstance()
        assertTrue(manager.getAction("JNote.ExportAllNotes") is ExportAllNotesAction)
        assertTrue(manager.getAction("JNote.ExportSelectedNotes") is ExportSelectedNotesAction)
        assertEquals(JNoteBundle.message("group.JNote.ToolsMenu.text"), manager.getAction("JNote.ToolsMenu").templatePresentation.text)
    }

    fun testSelectedExportIsDisabledWithoutSelection() {
        val action = ExportSelectedNotesAction()
        for (selected in listOf(null, emptyList<NoteSummary>())) {
            val e = event(action, selected)
            action.update(e)
            assertFalse(e.presentation.isEnabled)
        }
        val e = event(action, listOf(note(1)))
        action.update(e)
        assertTrue(e.presentation.isEnabled)
    }

    fun testExportAllPassesNoIdsAndSelectedPassesTheSelection() {
        val calls = mutableListOf<Pair<Path, Set<String>?>>()
        val target = dir.resolve("out.json")
        val (ask, start) = recorder(target, calls)

        ExportAllNotesAction(ask, start).actionPerformed(event(ExportAllNotesAction(), listOf(note(1))))
        ExportSelectedNotesAction(ask, start).actionPerformed(event(ExportSelectedNotesAction(), listOf(note(2), note(3))))

        assertEquals(listOf<Pair<Path, Set<String>?>>(target to null, target to setOf("uid-2", "uid-3")), calls)
    }

    fun testCancelledFileDialogStartsNothing() {
        val calls = mutableListOf<Pair<Path, Set<String>?>>()
        val (ask, start) = recorder(null, calls)

        ExportAllNotesAction(ask, start).actionPerformed(event(ExportAllNotesAction(), null))

        assertEquals(emptyList<Pair<Path, Set<String>?>>(), calls)
    }

    fun testTaskWritesTheFileAndReportsTheCount() {
        val repo = NoteRepository(db)
        repo.create(NewNote.free("a")).get()
        repo.create(NewNote.free("b")).get()
        val target = dir.resolve("out.json")

        ProgressManager.getInstance().run(ExportNotesTask(project, target, null) { NoteExporter(db) })
        PlatformTestUtil.waitWithEventsDispatching("no notification", { notifications.isNotEmpty() }, 20)

        val shown = notifications.single()
        assertEquals(NotificationType.INFORMATION, shown.type)
        assertEquals(JNoteBundle.message("notification.export.done.content", 2, target.toString()), shown.content)
        assertEquals(2, (NoteFormatCodec.decode(Files.readString(target)) as DecodeResult.Decoded).notes.size)
    }

    fun testTaskFailureIsReported() {
        db.close()
        val target = dir.resolve("out.json")

        ProgressManager.getInstance().run(ExportNotesTask(project, target, null) { NoteExporter(db) })
        PlatformTestUtil.waitWithEventsDispatching("no notification", { notifications.isNotEmpty() }, 20)

        assertEquals(NotificationType.ERROR, notifications.single().type)
        assertFalse(Files.exists(target))
    }
}
