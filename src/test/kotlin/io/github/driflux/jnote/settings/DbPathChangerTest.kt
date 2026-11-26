package io.github.driflux.jnote.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Files
import java.nio.file.Path

class DbPathChangerTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var storedBefore: String
    private lateinit var current: Path
    private val notifications = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-changer").toRealPath()
        storedBefore = DbPathSettings.getInstance().state.dbPath
        current = dir.resolve("cur.db")
        DbPathSettings.getInstance().store(current)
        DatabaseService.getInstance().switchTo(current)
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification
                }
            })
    }

    override fun tearDown() {
        try {
            DbPathSettings.getInstance().state.dbPath = storedBefore
            DatabaseService.getInstance().switchTo(DatabasePaths.default())
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testNotificationGroupIsRegistered() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID))
    }

    fun testInvalidPathShowsErrorNotificationAndChangesNothing() {
        DbPathChanger.request(dir) // 폴더
        val n = notifications.single()
        assertEquals(NotificationType.ERROR, n.type)
        assertTrue(n.content.isNotBlank())
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertEquals(current.toString(), DbPathSettings.getInstance().state.dbPath)
    }

    fun testFailureNotificationHasReason() {
        DbPathNotifier.failed(IllegalStateException("disk full"))
        val n = notifications.single()
        assertEquals(NotificationType.ERROR, n.type)
        assertTrue(n.content.contains("disk full"))
    }

    fun testSameFileIsNoChange() {
        DbPathChanger.request(current)
        assertTrue(notifications.isEmpty())
        assertEquals(current, DatabaseService.getInstance().currentPath())
    }

    fun testDecliningConfirmationKeepsCurrentDatabase() {
        val other = dir.resolve("other.db").also { Files.writeString(it, "x") }
        var asked: Path? = null
        DbPathChanger.request(other) { asked = it; false }
        assertEquals(other, asked)
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertEquals(current.toString(), DbPathSettings.getInstance().state.dbPath)
    }

    fun testApprovingConfirmationSwitchesToExistingFile() {
        val other = dir.resolve("other.db").also { Files.writeString(it, "x") }
        DbPathChanger.request(other) { true }
        PlatformTestUtil.waitWithEventsDispatching(
            "DB path was not switched",
            { DbPathSettings.getInstance().state.dbPath == other.toString() },
            20,
        )
        assertEquals(other, DatabaseService.getInstance().currentPath())
        assertEquals("x", Files.readString(other))
    }
}
