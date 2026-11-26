package io.github.driflux.jnote.db

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
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
