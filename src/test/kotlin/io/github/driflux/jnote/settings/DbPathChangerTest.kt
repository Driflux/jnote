package io.github.driflux.jnote.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
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

    fun testPlanIsMadeInTheBackgroundNotOnTheCallingThread() {
        // 테스트에서는 백그라운드 작업이 기본으로 동기 실행되므로, 실제 IDE처럼 비동기로 돌게 한다
        val key = "intellij.progress.task.ignoreHeadless"
        val before = System.getProperty(key)
        System.setProperty(key, "true")
        try {
            DbPathChanger.request(dir) // 폴더라는 사실은 파일 시스템을 봐야 안다
            assertTrue(notifications.isEmpty())
            PlatformTestUtil.waitWithEventsDispatching("plan was not made", { notifications.isNotEmpty() }, 20)
        } finally {
            if (before == null) System.clearProperty(key) else System.setProperty(key, before)
        }
        assertEquals(JNoteBundle.message("settings.dbPath.invalid.isDirectory"), notifications.single().content)
    }

    fun testSecondRequestWhileOneIsRunningIsRejected() {
        val other = dir.resolve("other.db").also { Files.writeString(it, "x") }
        val another = dir.resolve("another.db")
        DbPathChanger.request(other) {
            // 첫 요청이 확인을 기다리는 동안 두 번째 Apply가 온 상황
            DbPathChanger.request(another) { true }
            false
        }
        val n = notifications.single()
        assertEquals(NotificationType.WARNING, n.type)
        assertEquals(JNoteBundle.message("notification.dbPath.invalid.title"), n.title)
        assertEquals(JNoteBundle.message("notification.dbPath.busy.content"), n.content)
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertFalse(Files.exists(another))

        // 첫 요청이 끝나면 다음 요청을 받는다
        DbPathChanger.request(another)
        assertEquals(another, DatabaseService.getInstance().currentPath())
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

    fun testStaleWalAtTheNewLocationIsReportedAndNothingChanges() {
        val stale = dir.resolve("next.db-wal").also { Files.writeString(it, "stale") }
        DbPathChanger.request(dir.resolve("next.db"))
        val n = notifications.single()
        assertEquals(JNoteBundle.message("notification.dbPath.failed.title"), n.title)
        assertEquals(JNoteBundle.message("notification.dbPath.targetExists.content", stale), n.content)
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertEquals(current.toString(), DbPathSettings.getInstance().state.dbPath)
        assertEquals("stale", Files.readString(stale))
    }

    fun testSameFileIsNoChange() {
        DbPathChanger.request(current)
        assertTrue(notifications.isEmpty())
        assertEquals(current, DatabaseService.getInstance().currentPath())
    }

    fun testDecliningConfirmationKeepsCurrentDatabase() {
        val other = dir.resolve("other.db").also { Files.writeString(it, "x") }
        var asked: Path? = null
        DbPathChanger.request(other) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            asked = it
            false
        }
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
