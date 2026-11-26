package io.github.driflux.jnote.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection

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
}
