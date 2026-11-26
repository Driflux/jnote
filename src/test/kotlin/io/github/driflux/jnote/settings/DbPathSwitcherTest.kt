package io.github.driflux.jnote.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.driflux.jnote.db.Database
import io.github.driflux.jnote.db.DatabasePaths
import io.github.driflux.jnote.db.DatabaseService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

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
