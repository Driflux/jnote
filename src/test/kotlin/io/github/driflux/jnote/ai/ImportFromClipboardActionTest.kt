package io.github.driflux.jnote.ai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CopyOnWriteArrayList

class ImportFromClipboardActionTest : BasePlatformTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val started = CopyOnWriteArrayList<List<ImportSource>>()

    override fun setUp() {
        super.setUp()
        val collect = object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, collect)
    }

    private fun perform() {
        val event = AnActionEvent.createFromDataContext(ActionPlaces.MAIN_MENU, Presentation(), SimpleDataContext.getProjectContext(project))
        ImportFromClipboardAction { _: Project, sources -> started += sources }.actionPerformed(event)
    }

    fun testIsRegisteredInTheJNoteGroup() {
        assertTrue(ActionManager.getInstance().getAction("JNote.ImportFromClipboard") is ImportFromClipboardAction)
    }

    fun testClipboardTextStartsTheImport() {
        CopyPasteManager.getInstance().setContents(StringSelection("{\"format_version\":1,\"notes\":[]}"))

        perform()

        val source = started.single().single()
        assertEquals(JNoteBundle.message("import.source.clipboard"), source.name)
        assertEquals("{\"format_version\":1,\"notes\":[]}", source.read())
        assertTrue(notifications.isEmpty())
    }

    fun testBlankClipboardSaysSoAndStartsNothing() {
        CopyPasteManager.getInstance().setContents(StringSelection("  "))

        perform()

        assertTrue(started.isEmpty())
        assertEquals(NotificationType.WARNING, notifications.single().type)
        assertEquals(JNoteBundle.message("notification.importClipboard.empty.title"), notifications.single().title)
    }
}
