package io.github.driflux.jnote.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class DbPathSwitcherTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var storedBefore: String
    private lateinit var current: Path
    private val service get() = DatabaseService.getInstance()
    private val settings get() = DbPathSettings.getInstance()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-switcher").toRealPath()
        storedBefore = settings.state.dbPath
        current = dir.resolve("cur.db")
        settings.store(current)
        service.switchTo(current)
    }

    override fun tearDown() {
        try {
            settings.state.dbPath = storedBefore
            service.switchTo(DatabasePaths.default())
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun writeProbe() = service.database.submitAndWait { c ->
        c.createStatement().use { s ->
            s.execute("create table probe(x integer)")
            s.execute("insert into probe values (42)")
        }
    }

    private fun insert(db: Database, value: Int): CompletableFuture<Unit> =
        db.submit { c -> c.createStatement().use { s -> s.execute("insert into probe values ($value)") } }

    private fun probeCount(file: Path): Int {
        val db = Database(file)
        try {
            return db.submitAndWait { c ->
                c.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next(); it.getInt(1) } }
            }
        } finally {
            db.close()
        }
    }

    private fun run(plan: DbPathChange.Plan, check: () -> Unit = {}) =
        DbPathSwitcher.execute(plan, service, settings, check)

    fun testNeverOpenedWithoutFileJustSwitches() {
        val target = dir.resolve("new/next.db")
        run(DbPathChange.Plan.CopyThenSwitch(target))
        assertEquals(target, service.currentPath())
        assertEquals(target.toString(), settings.state.dbPath)
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(target.parent))
        assertNull(service.openDatabaseOrNull())
    }

    fun testOpenDatabaseIsCopiedClosedAndNewOneIsLazy() {
        writeProbe()
        val old = service.database
        val target = dir.resolve("next.db")
        run(DbPathChange.Plan.CopyThenSwitch(target))

        assertTrue(Files.exists(current))
        assertFalse(old.isOpen)
        assertEquals(target, service.currentPath())
        assertEquals(target.toString(), settings.state.dbPath)
        assertFalse(service.database.isOpen)
        assertEquals(1, probeCount(target))
        assertEquals(1, service.database.submitAndWait { c ->
            c.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next(); it.getInt(1) } }
        })
    }

    fun testWriteQueuedBeforeTheCopyIsCopiedAndLaterWritesGoToTheNewFile() {
        writeProbe()
        val old = service.database
        val gate = CountDownLatch(1)
        old.submit { gate.await() }
        val queuedBefore = insert(old, 43)
        val target = dir.resolve("next.db")
        val switching = CompletableFuture.runAsync { run(DbPathChange.Plan.CopyThenSwitch(target)) }
        gate.countDown()
        switching.get(20, TimeUnit.SECONDS)
        queuedBefore.get()
        assertEquals(2, probeCount(target))

        insert(service.database, 44).get()
        assertEquals(3, probeCount(target))
        assertEquals(2, probeCount(current))
    }

    fun testWriteQueuedDuringTheCopyFailsInsteadOfLandingOnlyInTheOldFile() {
        writeProbe()
        val target = dir.resolve("next.db")
        var duringCopy: CompletableFuture<Unit>? = null
        run(DbPathChange.Plan.CopyThenSwitch(target)) {
            if (duringCopy == null) duringCopy = insert(service.database, 99)
        }
        assertEquals(target, service.currentPath())
        val error = org.junit.Assert.assertThrows(ExecutionException::class.java) { duringCopy!!.get(20, TimeUnit.SECONDS) }
        assertTrue(error.cause is java.sql.SQLException)
        assertEquals(1, probeCount(current))
        assertEquals(1, probeCount(target))
    }

    fun testDatabaseOpenedDuringACopyOnTheCallingThreadIsCopiedAgain() {
        Database(current).use { db -> db.submitAndWait { c -> c.createStatement().use { s -> s.execute("create table probe(x integer)") } } }
        service.switchTo(current)
        val target = dir.resolve("next.db")
        var opened = false
        run(DbPathChange.Plan.CopyThenSwitch(target)) {
            if (!opened) {
                opened = true
                insert(service.database, 77).get()
            }
        }
        assertEquals(target, service.currentPath())
        assertEquals(1, probeCount(target))
    }

    fun testCancelArrivingAfterTheCopyIsInPlaceStillFinishesTheSwitch() {
        writeProbe()
        val target = dir.resolve("next.db")
        run(DbPathChange.Plan.CopyThenSwitch(target)) { if (Files.exists(target)) throw CancellationException() }
        assertEquals(target, service.currentPath())
        assertEquals(target.toString(), settings.state.dbPath)
        assertEquals(1, probeCount(target))
    }

    fun testSwitchToExistingDoesNotCopyOrMerge() {
        writeProbe()
        val other = dir.resolve("other.db")
        Database(other).use { it.submitAndWait { c -> c.createStatement().use { s -> s.execute("create table other(x)") } } }
        val before = Files.size(other)
        run(DbPathChange.Plan.SwitchToExisting(other))
        assertEquals(other, service.currentPath())
        assertEquals(before, Files.size(other))
        assertFalse(Files.exists(dir.resolve("other.db.copying")))
    }

    fun testExistingFileThatCannotBeOpenedAtSwitchTimeIsReportedAndNothingChanges() {
        val broken = dir.resolve("broken.db").also { Files.writeString(it, "changed after the plan") }
        val error = org.junit.Assert.assertThrows(DbPathSwitcher.UnusableTarget::class.java) {
            run(DbPathChange.Plan.SwitchToExisting(broken))
        }
        assertEquals(broken, error.target)
        assertEquals(current, service.currentPath())
        assertEquals(current.toString(), settings.state.dbPath)
    }

    fun testExistingFileReplacedByAnotherApplicationsDatabaseAfterThePlanIsLeftUntouched() {
        val other = dir.resolve("app.db")
        org.sqlite.JDBC().connect("jdbc:sqlite:$other", java.util.Properties())!!.use { c ->
            c.createStatement().use { it.execute("create table item(x)") }
        }
        val before = Files.readAllBytes(other)
        org.junit.Assert.assertThrows(DbPathSwitcher.UnusableTarget::class.java) {
            run(DbPathChange.Plan.SwitchToExisting(other))
        }
        assertTrue(before.contentEquals(Files.readAllBytes(other)))
        assertEquals(current, service.currentPath())
    }

    fun testCancelledCopyKeepsOldDatabaseActive() {
        writeProbe()
        val old = service.database
        val target = dir.resolve("next.db")
        try {
            run(DbPathChange.Plan.CopyThenSwitch(target)) { throw CancellationException() }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertSame(old, service.database)
        assertTrue(old.isOpen)
        assertEquals(current, service.currentPath())
        assertEquals(current.toString(), settings.state.dbPath)
        assertFalse(Files.exists(target))
        assertEquals(listOf("cur.db", "cur.db-shm", "cur.db-wal").filter { Files.exists(dir.resolve(it)) }.toSet(),
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
    }
}
