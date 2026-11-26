package io.github.driflux.jnote.ui

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList

class ToolWindowNotifierTest : BasePlatformTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    fun testFileMissingEscapesTheRelPathBecauseContentIsHtml() {
        ToolWindowNotifier.fileMissing(project, "<b>a</b> & b.kt")
        val content = notifications.single().content
        assertTrue(content, content.contains("&lt;b&gt;a&lt;/b&gt; &amp; b.kt"))
        assertFalse(content, content.contains("<b>"))
    }

    fun testLoadFailedEscapesTheCauseMessage() {
        ToolWindowNotifier.loadFailed(project, IllegalStateException("<i>bad</i> & worse"))
        val content = notifications.single().content
        assertTrue(content, content.contains("&lt;i&gt;bad&lt;/i&gt; &amp; worse"))
        assertFalse(content, content.contains("<i>"))
    }
}
