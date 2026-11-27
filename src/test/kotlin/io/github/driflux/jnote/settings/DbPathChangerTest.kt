package io.github.driflux.jnote.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.JNoteBundle
import io.github.driflux.jnote.db.Database
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
        val key = "intellij.progress.task.ignoreHeadless"
        val before = System.getProperty(key)
        System.setProperty(key, "true")
        try {
            DbPathChanger.request(dir)
            assertTrue(notifications.isEmpty())
            PlatformTestUtil.waitWithEventsDispatching("plan was not made", { notifications.isNotEmpty() }, 20)
        } finally {
            if (before == null) System.clearProperty(key) else System.setProperty(key, before)
        }
        assertEquals(JNoteBundle.message("settings.dbPath.invalid.isDirectory"), notifications.single().content)
    }

    fun testSecondRequestWhileOneIsRunningIsRejected() {
        val other = dir.resolve("other.db").also { Files.createFile(it) }
        val another = dir.resolve("another.db")
        DbPathChanger.request(other) {
            DbPathChanger.request(another) { true }
            false
        }
        val n = notifications.single()
        assertEquals(NotificationType.WARNING, n.type)
        assertEquals(JNoteBundle.message("notification.dbPath.invalid.title"), n.title)
        assertEquals(JNoteBundle.message("notification.dbPath.busy.content"), n.content)
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertFalse(Files.exists(another))

        DbPathChanger.request(another)
        assertEquals(another, DatabaseService.getInstance().currentPath())
    }

    fun testNotificationGroupIsRegistered() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(DbPathNotifier.GROUP_ID))
    }

    fun testInvalidPathShowsErrorNotificationAndChangesNothing() {
        DbPathChanger.request(dir)
        val n = notifications.single()
        assertEquals(NotificationType.ERROR, n.type)
        assertEquals(JNoteBundle.message("notification.dbPath.invalid.title"), n.title)
        assertEquals(JNoteBundle.message("settings.dbPath.invalid.isDirectory"), n.content)
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertEquals(current.toString(), DbPathSettings.getInstance().state.dbPath)
    }

    fun testFailureNotificationHasReason() {
        DbPathNotifier.failed(IllegalStateException("disk full"))
        val n = notifications.single()
        assertEquals(NotificationType.ERROR, n.type)
        assertEquals(JNoteBundle.message("notification.dbPath.failed.title"), n.title)
        assertEquals(JNoteBundle.message("notification.dbPath.failed.content", "disk full"), n.content)
    }

    private fun createProbe(): Database = DatabaseService.getInstance().database.also { db ->
        db.submitAndWait { c ->
            c.createStatement().use { s ->
                s.execute("create table probe(x integer)")
                s.execute("insert into probe values (42)")
            }
        }
    }

    fun testMissingTargetGetsACopyOfTheCurrentDatabase() {
        createProbe()
        val target = dir.resolve("moved/next.db")
        DbPathChanger.request(target)
        assertTrue(notifications.isEmpty())
        assertEquals(target, DatabaseService.getInstance().currentPath())
        assertEquals(target.toString(), DbPathSettings.getInstance().state.dbPath)
        assertTrue(Files.exists(current))
        assertEquals(1, DatabaseService.getInstance().database.submitAndWait { c ->
            c.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next(); it.getInt(1) } }
        })
    }

    fun testCopyThatCannotFinishIsReportedAndNothingChanges() {
        val db = createProbe()
        db.submitAndWait { c -> c.createStatement().use { it.execute("PRAGMA busy_timeout = 50") } }
        val target = dir.resolve("next.db")
        org.sqlite.JDBC().connect("jdbc:sqlite:$current", java.util.Properties())!!.use { reader ->
            reader.autoCommit = false
            reader.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next() } }
            DbPathChanger.request(target)
        }
        val n = notifications.single()
        assertEquals(NotificationType.ERROR, n.type)
        assertEquals(JNoteBundle.message("notification.dbPath.failed.title"), n.title)
        assertEquals(
            JNoteBundle.message(
                "notification.dbPath.failed.content",
                "WAL checkpoint could not finish because another connection is reading the database",
            ),
            n.content,
        )
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertEquals(current.toString(), DbPathSettings.getInstance().state.dbPath)
        assertFalse(Files.exists(target))
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

    fun testCloseFailureAfterTheSwitchSaysTheNewDatabaseIsInUse() {
        val target = dir.resolve("next.db")
        DbPathNotifier.failed(DbPathSwitcher.OldDatabaseNotClosed(target, IllegalStateException("stuck")))
        val n = notifications.single()
        assertEquals(NotificationType.WARNING, n.type)
        assertEquals(JNoteBundle.message("notification.dbPath.closeFailed.title"), n.title)
        assertEquals(JNoteBundle.message("notification.dbPath.closeFailed.content", target, "stuck"), n.content)
    }

    fun testSameFileIsNoChange() {
        DbPathChanger.request(current)
        assertTrue(notifications.isEmpty())
        assertEquals(current, DatabaseService.getInstance().currentPath())
    }

    fun testDecliningConfirmationKeepsCurrentDatabase() {
        val other = dir.resolve("other.db").also { Files.createFile(it) }
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

    fun testApprovingConfirmationSwitchesToExistingDatabase() {
        val other = dir.resolve("other.db")
        Database(other).use { db -> db.submitAndWait { c -> c.createStatement().use { s -> s.execute("create table other(x)") } } }
        DbPathChanger.request(other) { true }
        PlatformTestUtil.waitWithEventsDispatching(
            "DB path was not switched",
            { DbPathSettings.getInstance().state.dbPath == other.toString() },
            20,
        )
        assertEquals(other, DatabaseService.getInstance().currentPath())
        assertTrue(notifications.isEmpty())
    }

    fun testExistingFileThatIsNotADatabaseIsRefusedBeforeAskingAndNothingChanges() {
        val other = dir.resolve("other.db").also { Files.writeString(it, "not a database") }
        DbPathChanger.request(other) { fail("must not ask"); true }
        val n = notifications.single()
        assertEquals(NotificationType.ERROR, n.type)
        assertEquals(JNoteBundle.message("notification.dbPath.invalid.title"), n.title)
        assertEquals(JNoteBundle.message("settings.dbPath.invalid.notSqlite"), n.content)
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertEquals(current.toString(), DbPathSettings.getInstance().state.dbPath)
        assertEquals("not a database", Files.readString(other))
    }

    fun testAnotherApplicationsDatabaseIsRefusedBeforeAskingAndGetsNoJNoteTables() {
        val other = dir.resolve("app.db")
        org.sqlite.JDBC().connect("jdbc:sqlite:$other", java.util.Properties())!!.use { c ->
            c.createStatement().use { it.execute("create table item(x)") }
        }
        val before = Files.readAllBytes(other)
        DbPathChanger.request(other) { fail("must not ask"); true }
        val n = notifications.single()
        assertEquals(JNoteBundle.message("notification.dbPath.invalid.title"), n.title)
        assertEquals(JNoteBundle.message("settings.dbPath.invalid.notJNote"), n.content)
        assertEquals(current, DatabaseService.getInstance().currentPath())
        assertTrue(before.contentEquals(Files.readAllBytes(other)))
    }
}
