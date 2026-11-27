package io.github.driflux.jnote.db

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DatabaseServiceTest : BasePlatformTestCase() {
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jnote-service")
    }

    override fun tearDown() {
        try {
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testCurrentReadsThePathTogetherWithTheDatabaseMadeForIt() {
        val service = DatabaseService()
        val first = dir.resolve("first.db")
        service.switchTo(first)
        assertEquals(DatabaseService.Current(first, null), service.current())

        val database = service.database
        assertEquals(DatabaseService.Current(first, database), service.current())

        val second = dir.resolve("second.db")
        service.switchTo(second)
        assertEquals(DatabaseService.Current(second, null), service.current())
    }

    fun testDefaultPathIsResolvedOnTheDatabaseThreadNotByTheFirstCaller() {
        val service = DatabaseService()
        val resolvedOn = ConcurrentLinkedQueue<String>()
        val expected = dir.resolve("lazy.db")
        service.defaultPath = {
            resolvedOn += Thread.currentThread().name
            expected
        }

        val database = service.database
        assertTrue(resolvedOn.toString(), resolvedOn.isEmpty())

        database.submitAndWait { 1 }
        assertEquals(listOf(Database.THREAD_NAME), resolvedOn.toList())
        assertTrue(Files.exists(expected))
        assertEquals(expected, service.currentPath())
        service.dispose()
    }

    fun testGettingTheDatabaseDoesNotWaitForTheSettingsLoad() {
        val service = DatabaseService()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        service.defaultPath = {
            entered.countDown()
            gate.await()
            dir.resolve("slow.db")
        }
        try {
            val database = service.database
            val first = database.submit { 1 }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val again = CompletableFuture.supplyAsync { service.database }
            assertSame(database, again.get(1, TimeUnit.SECONDS))
            gate.countDown()
            assertEquals(1, first.get(10, TimeUnit.SECONDS))
        } finally {
            gate.countDown()
            service.dispose()
        }
    }

    fun testFirstDatabaseKeepsTheDefaultPathEvenIfTheSettingChangesBeforeItOpens() {
        val service = DatabaseService()
        val first = dir.resolve("first.db")
        val second = dir.resolve("second.db")
        var setting = first
        service.defaultPath = { setting }
        val old = service.database

        assertTrue(service.replace(service.current(), second))
        setting = second

        val opened = old.submitAndWait { openedFile(it) }
        assertEquals(first.toRealPath(), Paths.get(opened).toRealPath())
        assertFalse(Files.exists(second))
        old.close()
        service.dispose()
    }

    fun testDatabaseReplacedBeforeItsFirstJobStillOpensItsOwnFile() {
        val service = DatabaseService()
        val first = dir.resolve("first.db")
        service.switchTo(first)
        val old = service.database
        val second = dir.resolve("second.db")
        assertTrue(service.replace(service.current(), second))

        val opened = old.submitAndWait { openedFile(it) }
        assertEquals(first.toRealPath(), Paths.get(opened).toRealPath())
        assertFalse(Files.exists(second))
        old.close()
        service.dispose()
    }

    private fun openedFile(c: java.sql.Connection): String =
        c.createStatement().use { s -> s.executeQuery("PRAGMA database_list").use { it.next(); it.getString("file") } }

    fun testJobQueuedBeforeASwitchUsesThePathItsDatabaseWasMadeFor() {
        val service = DatabaseService()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val first = dir.resolve("first.db")
        service.defaultPath = {
            entered.countDown()
            gate.await()
            first
        }
        val old = service.database
        val openedFile = old.submit { openedFile(it) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = dir.resolve("second.db")
        val switching = Thread { service.switchTo(second) }.apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (switching.isAlive && switching.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            assertEquals(Thread.State.TIMED_WAITING, switching.state)
        } finally {
            gate.countDown()
        }
        switching.join(15_000)

        assertEquals(first.toRealPath(), Paths.get(openedFile.get(10, TimeUnit.SECONDS)).toRealPath())
        assertFalse(Files.exists(second))
        assertEquals(second, service.currentPath())
        service.dispose()
    }

    fun testDisposeDoesNotWaitForABusyDatabaseAndClosesItLater() {
        val service = DatabaseService()
        service.switchTo(dir.resolve("busy.db"))
        val database = service.database
        val gate = CountDownLatch(1)
        database.submit { gate.await() }
        PlatformTestUtil.waitWithEventsDispatching("database did not open", { database.isOpen }, 10)

        val started = System.nanoTime()
        service.dispose()
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        gate.countDown()

        assertTrue("dispose waited ${waitedMillis} ms", waitedMillis < 1000)
        assertNull(service.openDatabaseOrNull())
        assertThrows(IllegalStateException::class.java) { service.database }
        PlatformTestUtil.waitWithEventsDispatching("database was not closed", { !database.isOpen }, 10)
    }
}
