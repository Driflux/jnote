package io.github.driflux.jnote.action

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.model.Note
import io.github.driflux.jnote.model.NoteKind
import io.github.driflux.jnote.model.NoteStatus
import java.util.concurrent.CopyOnWriteArrayList

class AddNoteNotifierTest : BasePlatformTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    private fun noteTitled(title: String) = Note(
        1, "uid", NoteKind.FREE, null, null, null, null, null, null, NoteStatus.OK, title, "", null, null, null, 0, 0,
    )

    fun testSavedEscapesTheTitleBecauseContentIsHtml() {
        AddNoteNotifier.saved(project, noteTitled("<b>bold</b> & more"))
        val content = notifications.single().content
        assertEquals("&lt;b&gt;bold&lt;/b&gt; &amp; more", content)
    }

    fun testFailedEscapesTheCauseMessage() {
        AddNoteNotifier.failed(project, IllegalStateException("<i>bad</i> & worse"))
        val content = notifications.single().content
        assertTrue(content, content.contains("&lt;i&gt;bad&lt;/i&gt; &amp; worse"))
        assertFalse(content, content.contains("<i>"))
    }
}
