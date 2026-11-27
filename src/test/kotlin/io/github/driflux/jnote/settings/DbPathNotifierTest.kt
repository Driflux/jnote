package io.github.driflux.jnote.settings

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

class DbPathNotifierTest : BasePlatformTestCase() {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val markupPath = Paths.get("/tmp/<b>notes</b> & more/jnote.db")
    private val escapedPath = "/tmp/&lt;b&gt;notes&lt;/b&gt; &amp; more/jnote.db"

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification
                }
            })
    }

    private fun content(): String = notifications.single().content

    private fun assertEscaped(content: String, vararg escaped: String) {
        escaped.forEach { assertTrue(content, content.contains(it)) }
        assertFalse(content, content.contains("<b>"))
        assertFalse(content, content.contains("<i>"))
    }

    fun testFailedEscapesTheCauseMessage() {
        DbPathNotifier.failed(IllegalStateException("<i>disk</i> & full"))
        assertEscaped(content(), "&lt;i&gt;disk&lt;/i&gt; &amp; full")
    }

    fun testTargetExistsEscapesThePath() {
        DbPathNotifier.failed(FileAlreadyExistsException(markupPath.toString()))
        assertEscaped(content(), escapedPath)
    }

    fun testUnusableTargetEscapesThePath() {
        DbPathNotifier.failed(DbPathSwitcher.UnusableTarget(markupPath, IllegalStateException("bad")))
        assertEscaped(content(), escapedPath)
    }

    fun testCloseFailedEscapesThePathAndTheCauseMessage() {
        DbPathNotifier.failed(DbPathSwitcher.OldDatabaseNotClosed(markupPath, IllegalStateException("<i>stuck</i>")))
        assertEscaped(content(), escapedPath, "&lt;i&gt;stuck&lt;/i&gt;")
    }
}
