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
    // Path로 만들 경로에는 Windows가 허용하지 않는 <, >를 넣지 않는다
    private val ampersandPath = Paths.get("notes & more", "jnote.db")
    private val escapedAmpersandPath = Paths.get("notes &amp; more", "jnote.db").toString()

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
        DbPathNotifier.failed(FileAlreadyExistsException("/tmp/<b>notes</b> & more/jnote.db"))
        assertEscaped(content(), "/tmp/&lt;b&gt;notes&lt;/b&gt; &amp; more/jnote.db")
    }

    fun testTargetExistsWithoutAFileNameStillShows() {
        DbPathNotifier.failed(FileAlreadyExistsException(null))
        assertTrue(content(), content().startsWith("null"))
    }

    fun testUnusableTargetEscapesThePath() {
        DbPathNotifier.failed(DbPathSwitcher.UnusableTarget(ampersandPath, IllegalStateException("bad")))
        assertEscaped(content(), escapedAmpersandPath)
    }

    fun testCloseFailedEscapesThePathAndTheCauseMessage() {
        DbPathNotifier.failed(DbPathSwitcher.OldDatabaseNotClosed(ampersandPath, IllegalStateException("<i>stuck</i>")))
        assertEscaped(content(), escapedAmpersandPath, "&lt;i&gt;stuck&lt;/i&gt;")
    }
}
