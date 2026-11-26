package io.github.driflux.jnote.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DatabaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<Database>()

    private fun newDatabase(): Database =
        Database(tmp.root.toPath().resolve("sub/jnote.db")).also { opened += it }

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun pragma(c: Connection, name: String): String =
        c.createStatement().use { s -> s.executeQuery("PRAGMA $name").use { it.next(); it.getString(1) } }

    @Test
    fun nothingOpensUntilFirstSubmit() {
        val db = newDatabase()
        assertFalse(db.isOpen)
        assertFalse(tmp.root.toPath().resolve("sub").toFile().exists())
        db.submitAndWait { 1 }
        assertTrue(db.isOpen)
    }

    @Test
    fun pragmasAreApplied() {
        val db = newDatabase()
        val result = db.submitAndWait { c ->
            Triple(pragma(c, "journal_mode"), pragma(c, "busy_timeout"), pragma(c, "foreign_keys"))
        }
        assertEquals("wal", result.first)
        assertEquals("5000", result.second)
        assertEquals("1", result.third)
    }

    @Test
    fun allJobsRunOnOneNamedThread() {
        val db = newDatabase()
        val names = (1..20).map { db.submit { Thread.currentThread().name } }.map { it.get() }.toSet()
        assertEquals(setOf(Database.THREAD_NAME), names)
    }

    @Test
    fun connectionIsReusedAcrossJobs() {
        val db = newDatabase()
        val a = db.submitAndWait { System.identityHashCode(it) }
        val b = db.submitAndWait { System.identityHashCode(it) }
        assertEquals(a, b)
    }

    @Test
    fun closingWithoutUseNeverOpens() {
        val db = newDatabase()
        db.close()
        assertFalse(tmp.root.toPath().resolve("sub").toFile().exists())
    }

    private fun assertFailsAsClosed(future: CompletableFuture<*>) {
        try {
            future.get(5, TimeUnit.SECONDS)
            fail("expected the job to fail because the database is closed")
        } catch (e: ExecutionException) {
            assertTrue(e.cause.toString(), e.cause is IllegalStateException)
        }
    }

    @Test
    fun closeCanBeCalledRepeatedlyAndConcurrently() {
        val db = newDatabase()
        db.submitAndWait { 1 }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val closers = (1..8).map { Thread { runCatching { db.close() }.onFailure { errors += it } } }
        closers.forEach { it.start() }
        closers.forEach { it.join(15_000) }
        db.close()
        assertTrue(errors.toString(), errors.isEmpty())
        assertFalse(db.isOpen)
    }

    @Test
    fun submitAfterCloseReturnsFailedFuture() {
        val db = newDatabase()
        db.submitAndWait { 1 }
        db.close()
        assertFailsAsClosed(db.submit { 1 })
    }

    @Test
    fun jobQueuedBehindCloseFails() {
        val db = newDatabase()
        val release = CountDownLatch(1)
        db.submit { release.await() }
        val closer = Thread { db.close() }.apply { start() }
        while (closer.state != Thread.State.TIMED_WAITING) Thread.onSpinWait()
        val queued = db.submit { c -> c.createStatement().use { it.execute("SELECT 1") } }
        release.countDown()
        closer.join(15_000)
        assertFailsAsClosed(queued)
    }

    @Test
    fun closeShutsDownEvenWhenARunningJobOutlivesTheWait() {
        val db = Database(tmp.root.toPath().resolve("sub/jnote.db"), closeTimeoutMillis = 100).also { opened += it }
        val release = CountDownLatch(1)
        val running = db.submit { c -> release.await(); c }
        try {
            db.close()
            fail("expected close to time out while a job is running")
        } catch (e: TimeoutException) {
        }
        assertFalse(db.isOpen)
        assertFailsAsClosed(db.submit { 1 })
        db.close()

        release.countDown()
        val connection = running.get(5, TimeUnit.SECONDS)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!connection.isClosed && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(connection.isClosed)
    }
}
