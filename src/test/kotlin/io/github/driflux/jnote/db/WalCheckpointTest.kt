package io.github.driflux.jnote.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.sql.Connection

class WalCheckpointTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dbPath get() = tmp.root.toPath().resolve("w.db")
    private val walPath get() = tmp.root.toPath().resolve("w.db-wal")

    // busy_timeout 0이면 체크포인트가 읽는 쪽을 기다리지 않고 바로 busy를 돌려준다
    private fun connect(): Connection {
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(0)
        }
        return checkNotNull(JDBC().connect("jdbc:sqlite:$dbPath", config.toProperties()))
    }

    private fun exec(c: Connection, sql: String) = c.createStatement().use { it.executeUpdate(sql) }

    @Test
    fun truncatesWalWhenNobodyIsReading() {
        connect().use { c ->
            exec(c, "CREATE TABLE t(x)")
            exec(c, "INSERT INTO t VALUES (1)")
            assertTrue(Files.size(walPath) > 0)
            WalCheckpoint.truncateOrFail(c)
            assertEquals(0L, Files.size(walPath))
        }
    }

    @Test
    fun failsWhileAnotherConnectionHoldsAReadTransaction() {
        connect().use { writer ->
            connect().use { reader ->
                exec(writer, "CREATE TABLE t(x)")
                exec(writer, "INSERT INTO t VALUES (1)")
                reader.autoCommit = false
                reader.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM t").use { it.next() } }
                try {
                    WalCheckpoint.truncateOrFail(writer, attempts = 2, waitMillis = 10)
                    fail("expected the checkpoint to fail while a reader holds the WAL")
                } catch (e: IllegalStateException) {
                    // 읽는 쪽이 WAL을 붙잡고 있으면 TRUNCATE가 끝나지 않는다
                }
                reader.rollback()
                WalCheckpoint.truncateOrFail(writer)
                assertEquals(0L, Files.size(walPath))
            }
        }
    }
}
