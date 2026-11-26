package io.github.driflux.jnote.db

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.BusyHandler
import org.sqlite.Function
import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class MigrationsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<Database>()
    private val dbPath get() = tmp.root.toPath().resolve("jnote.db")
    private val bakPath get() = tmp.root.toPath().resolve("jnote.db.bak")

    private fun newDatabase(): Database = Database(dbPath).also { opened += it }

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun version(c: Connection): Int =
        c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

    private fun exec(c: Connection, sql: String) = c.createStatement().use { it.executeUpdate(sql) }

    private fun count(c: Connection, sql: String): Int =
        c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } }

    private val insertNote = "INSERT INTO note(uid, kind, title, created_at, updated_at) VALUES ('%s', '%s', 't', 1, 1)"

    @Test
    fun freshDatabaseReachesLatestVersionWithoutBackup() {
        val db = newDatabase()
        assertEquals(Migrations.ALL.size, db.submitAndWait { version(it) })
        assertFalse(Files.exists(bakPath))
    }

    @Test
    fun twoDatabasesOpeningOneNewFileAtOnceBothSucceed() {
        repeat(20) {
            val path = tmp.newFolder().toPath().resolve("jnote.db")
            val a = Database(path).also { opened += it }
            val b = Database(path).also { opened += it }
            val versions = listOf(a.submit { version(it) }, b.submit { version(it) }).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(listOf(Migrations.ALL.size, Migrations.ALL.size), versions)
            assertFalse(Files.exists(path.resolveSibling("jnote.db.bak")))
        }
    }

    @Test
    fun twoDatabasesUpgradingOneFileAtOnceBothSucceedAndKeepThePreUpgradeBackup() {
        repeat(20) {
            val dir = tmp.newFolder().toPath()
            val path = dir.resolve("jnote.db")
            val walConfig = SQLiteConfig().apply { setJournalMode(SQLiteConfig.JournalMode.WAL) }
            checkNotNull(JDBC().connect("jdbc:sqlite:$path", walConfig.toProperties())).use { c ->
                Migrations.run(c, Migrations.ALL.take(1))
                exec(c, insertNote.format("a", "FREE"))
            }
            val a = Database(path).also { opened += it }
            val b = Database(path).also { opened += it }
            val versions = listOf(a.submit { version(it) }, b.submit { version(it) }).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(listOf(Migrations.ALL.size, Migrations.ALL.size), versions)
            assertEquals(setOf("jnote.db", "jnote.db.bak"), dir.toFile().list()!!.toSet() - setOf("jnote.db-wal", "jnote.db-shm"))
            val copy = tmp.newFile().toPath()
            Files.copy(dir.resolve("jnote.db.bak"), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            checkNotNull(JDBC().connect("jdbc:sqlite:$copy", java.util.Properties())).use {
                assertEquals(1, version(it))
                assertEquals(1, count(it, "SELECT count(*) FROM note"))
            }
        }
    }

    @Test
    fun rerunningIsIdempotent() {
        val db = newDatabase()
        db.submitAndWait { exec(it, insertNote.format("a", "FREE")) }
        db.submitAndWait { Migrations.run(it) }
        assertEquals(1, db.submitAndWait { count(it, "SELECT count(*) FROM note") })
        assertFalse(Files.exists(bakPath))
    }

    @Test
    fun backupIsCreatedOnlyWhenUpgradingExistingDatabaseAndOverwritten() {
        val db = newDatabase()
        db.submitAndWait { exec(it, insertNote.format("a", "FREE")) }
        val v1 = Migrations.ALL
        val v2 = v1 + "CREATE TABLE extra2(x)"
        val v3 = v2 + "CREATE TABLE extra3(x)"

        db.submitAndWait { Migrations.run(it, v2) }
        assertTrue(Files.exists(bakPath))
        assertEquals(0, backupValue("extra2"))

        // 두 번째 업그레이드 시점의 백업에는 extra2가 들어 있어야 한다
        db.submitAndWait { Migrations.run(it, v3) }
        assertEquals(1, backupValue("extra2"))
        assertEquals(0, backupValue("extra3"))
    }

    /** 백업 파일을 열어 해당 테이블이 있으면 1, 없으면 0. 백업이 더 높은 버전일 수 있어 마이그레이션 없이 연다. */
    private fun backupValue(table: String): Int {
        val copy = tmp.newFile().toPath()
        Files.copy(bakPath, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return checkNotNull(JDBC().connect("jdbc:sqlite:$copy", java.util.Properties())).use {
            count(it, "SELECT count(*) FROM sqlite_master WHERE name = '$table'")
        }
    }

    @Test
    fun backupContainsCommittedWalData() {
        val db = newDatabase()
        db.submitAndWait { exec(it, insertNote.format("a", "FREE")) }
        db.submitAndWait { Migrations.run(it, Migrations.ALL + "CREATE TABLE extra(x)") }
        val copy = tmp.newFile().toPath()
        Files.copy(bakPath, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val backup = Database(copy).also { opened += it }
        assertEquals(1, backup.submitAndWait { count(it, "SELECT count(*) FROM note") })
    }

    @Test
    fun failedMigrationRollsBackAndKeepsVersion() {
        val db = newDatabase()
        val broken = Migrations.ALL + "CREATE TABLE half(x); INSERT INTO missing_table VALUES (1);"
        try {
            db.submitAndWait { Migrations.run(it, broken) }
            fail("expected failure")
        } catch (e: Exception) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is SQLException })
        }
        assertEquals(Migrations.ALL.size, db.submitAndWait { version(it) })
        assertEquals(0, db.submitAndWait { count(it, "SELECT count(*) FROM sqlite_master WHERE name = 'half'") })
    }

    @Test
    fun failedMigrationRollsBackEveryMigrationOfTheRun() {
        val db = newDatabase()
        val broken = Migrations.ALL + "CREATE TABLE extra(x)" + "INSERT INTO missing_table VALUES (1)"
        try {
            db.submitAndWait { Migrations.run(it, broken) }
            fail("expected failure")
        } catch (e: Exception) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is SQLException })
        }
        assertEquals(Migrations.ALL.size, db.submitAndWait { version(it) })
        assertEquals(0, db.submitAndWait { count(it, "SELECT count(*) FROM sqlite_master WHERE name = 'extra'") })
    }

    /**
     * [parkedRun]의 마지막 스크립트가 park()에서 멈춘 동안 [waitingRun]을 시작한다. waitingRun은 버전을 읽은 뒤에야
     * 체크포인트에서 쓰기 잠금을 기다리므로, 그 대기가 시작된 뒤에 parkedRun을 커밋시킨다.
     * waitingRun은 parkedRun이 커밋하고 [afterParkedCommit]이 끝날 때까지 기다리고, 잠금 하나에 [waitingGivesUpAfter]번까지 기다린다.
     */
    private fun runWhileAnotherUpgradeIsParked(
        parkedRun: List<String>,
        waitingRun: List<String>,
        afterParkedCommit: () -> Unit = {},
        waitingGivesUpAfter: Int = 1000,
    ): Result<Unit> {
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(Database.BUSY_TIMEOUT_MS)
        }
        val parked = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = CountDownLatch(1)
        checkNotNull(JDBC().connect("jdbc:sqlite:$dbPath", config.toProperties())).use { first ->
            checkNotNull(JDBC().connect("jdbc:sqlite:$dbPath", config.toProperties())).use { second ->
                Function.create(first, "park", object : Function() {
                    override fun xFunc() {
                        parked.countDown()
                        release.await()
                        result(0)
                    }
                })
                BusyHandler.setHandler(second, object : BusyHandler() {
                    override fun callback(nbPrevInvok: Int): Int {
                        waiting.countDown()
                        committed.await(10, TimeUnit.SECONDS)
                        Thread.sleep(10)
                        return if (nbPrevInvok < waitingGivesUpAfter) 1 else 0
                    }
                })
                try {
                    val upgrade = CompletableFuture.runAsync { Migrations.run(first, parkedRun) }
                    assertTrue(parked.await(10, TimeUnit.SECONDS))
                    val open = CompletableFuture.runAsync { Migrations.run(second, waitingRun) }
                    assertTrue(waiting.await(10, TimeUnit.SECONDS))
                    release.countDown()
                    upgrade.get(30, TimeUnit.SECONDS)
                    afterParkedCommit()
                    committed.countDown()
                    return runCatching { open.get(30, TimeUnit.SECONDS) }.map { }
                } finally {
                    release.countDown()
                    committed.countDown()
                }
            }
        }
    }

    private fun seedV1() = checkNotNull(JDBC().connect("jdbc:sqlite:$dbPath", java.util.Properties())).use {
        Migrations.run(it, Migrations.ALL.take(1))
        exec(it, insertNote.format("a", "FREE"))
    }

    @Test
    fun olderPluginOpeningWhileANewerOneUpgradesIsRefused() {
        seedV1()
        val result = runWhileAnotherUpgradeIsParked(Migrations.ALL + "SELECT park()", Migrations.ALL)
        val cause = (result.exceptionOrNull() as? ExecutionException)?.cause
        assertTrue(result.toString(), cause is IllegalStateException && "newer than this JNote version supports" in cause.message.orEmpty())
    }

    @Test
    fun newerPluginOpeningWhileAnOlderOneUpgradesBacksUpWhatTheOlderOneLeft() {
        seedV1()
        // 이전 플러그인은 v2까지 올리고 같은 트랜잭션에서 노트 하나를 더 남긴다
        val olderRun = Migrations.ALL.take(1) + (Migrations.ALL[1] + ";\n" + insertNote.format("later", "FREE") + ";\nSELECT park()")
        runWhileAnotherUpgradeIsParked(olderRun, Migrations.ALL).getOrThrow()
        val copy = tmp.newFile().toPath()
        Files.copy(bakPath, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        checkNotNull(JDBC().connect("jdbc:sqlite:$copy", java.util.Properties())).use {
            assertEquals(2, version(it))
            assertEquals(2, count(it, "SELECT count(*) FROM note"))
        }
    }

    @Test
    fun openingSucceedsWhenTheCheckpointFailsAfterAnotherConnectionFinishedTheUpgrade() {
        seedV1()
        val parkedRun = Migrations.ALL.dropLast(1) + (Migrations.ALL.last() + ";\nSELECT park()")
        val walConfig = SQLiteConfig().apply { setJournalMode(SQLiteConfig.JournalMode.WAL) }
        checkNotNull(JDBC().connect("jdbc:sqlite:$dbPath", walConfig.toProperties())).use { reader ->
            // 업그레이드가 커밋된 뒤 읽기 트랜잭션이 WAL을 붙잡아 기다리던 연결의 TRUNCATE 체크포인트가 끝나지 못하게 한다
            val result = runWhileAnotherUpgradeIsParked(parkedRun, Migrations.ALL, afterParkedCommit = {
                reader.autoCommit = false
                reader.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM note").use { it.next() } }
            }, waitingGivesUpAfter = 5)
            reader.rollback()
            result.getOrThrow()
        }
    }

    @Test
    fun badKindIsRejected() {
        val db = newDatabase()
        try {
            db.submitAndWait { exec(it, insertNote.format("a", "BAD")) }
            fail("expected constraint failure")
        } catch (e: Exception) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is SQLException })
        }
    }

    @Test
    fun duplicateUidIsRejected() {
        val db = newDatabase()
        db.submitAndWait { exec(it, insertNote.format("a", "FREE")) }
        try {
            db.submitAndWait { exec(it, insertNote.format("a", "CODE")) }
            fail("expected unique failure")
        } catch (e: Exception) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is SQLException })
        }
    }

    @Test
    fun deletingNoteCascadesToNoteTag() {
        val db = newDatabase()
        val remaining = db.submitAndWait { c ->
            exec(c, insertNote.format("a", "FREE"))
            exec(c, "INSERT INTO tag(name) VALUES ('x')")
            exec(c, "INSERT INTO note_tag(note_id, tag_id) SELECT note.id, tag.id FROM note, tag")
            exec(c, "DELETE FROM note")
            count(c, "SELECT count(*) FROM note_tag")
        }
        assertEquals(0, remaining)
    }

    private fun connectWithoutBusyWait(): Connection {
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(0)
        }
        return checkNotNull(JDBC().connect("jdbc:sqlite:$dbPath", config.toProperties()))
    }

    @Test
    fun upgradeIsAbortedWhenAReaderBlocksTheBackupCheckpoint() {
        val previousBackup = "previous backup".toByteArray()
        Files.write(bakPath, previousBackup)
        connectWithoutBusyWait().use { writer ->
            connectWithoutBusyWait().use { reader ->
                Migrations.run(writer)
                exec(writer, insertNote.format("a", "FREE"))
                reader.autoCommit = false
                reader.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM note").use { it.next() } }
                try {
                    Migrations.run(writer, Migrations.ALL + "CREATE TABLE extra(x)")
                    fail("expected the upgrade to stop when the backup cannot be made consistent")
                } catch (e: IllegalStateException) {
                    // 체크포인트가 끝나지 않으면 업그레이드하지 않는다
                }
                assertEquals(Migrations.ALL.size, version(writer))
                assertEquals(0, count(writer, "SELECT count(*) FROM sqlite_master WHERE name = 'extra'"))
                reader.rollback()
            }
        }
        assertArrayEquals(previousBackup, Files.readAllBytes(bakPath))
        assertEquals(setOf("jnote.db", "jnote.db.bak"), tmp.root.list()!!.toSet() - setOf("jnote.db-wal", "jnote.db-shm"))
    }

    @Test
    fun failedBackupCopyKeepsThePreviousBackup() {
        assumeTrue(dbPath.fileSystem.supportedFileAttributeViews().contains("posix"))
        val previousBackup = "previous backup".toByteArray()
        val db = newDatabase()
        db.submitAndWait { exec(it, insertNote.format("a", "FREE")) }
        Files.write(bakPath, previousBackup)
        val permissions = Files.getPosixFilePermissions(dbPath)
        // 열린 연결은 그대로 쓰면서 파일 복사만 실패하게 만든다
        Files.setPosixFilePermissions(dbPath, PosixFilePermissions.fromString("---------"))
        try {
            db.submitAndWait { Migrations.run(it, Migrations.ALL + "CREATE TABLE extra(x)") }
            fail("expected the backup copy to fail")
        } catch (e: Exception) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException })
        } finally {
            Files.setPosixFilePermissions(dbPath, permissions)
        }
        assertArrayEquals(previousBackup, Files.readAllBytes(bakPath))
        assertEquals(Migrations.ALL.size, db.submitAndWait { version(it) })
        assertEquals(setOf("jnote.db", "jnote.db.bak"), tmp.root.list()!!.toSet() - setOf("jnote.db-wal", "jnote.db-shm"))
    }

    @Test
    fun databaseFromANewerPluginIsRefused() {
        val db = newDatabase()
        db.submitAndWait { exec(it, "PRAGMA user_version = ${Migrations.ALL.size + 1}") }
        db.close()
        try {
            newDatabase().submitAndWait { 1 }
            fail("expected a newer schema to be refused")
        } catch (e: Exception) {
            assertTrue(e.toString(), generateSequence<Throwable>(e) { it.cause }.any { it is IllegalStateException })
        }
    }

    @Test
    fun concurrentMigrationsOnTheSameFileBothSucceed() {
        repeat(20) { round ->
            val file = tmp.root.toPath().resolve("race$round.db")
            // WAL 전환 경합은 이 테스트의 관심사가 아니므로 파일을 WAL로 먼저 만들어 둔다
            checkNotNull(JDBC().connect("jdbc:sqlite:$file", SQLiteConfig().apply { setJournalMode(SQLiteConfig.JournalMode.WAL) }.toProperties())).close()
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val results = (1..2).map {
                    pool.submit<Int> {
                        val config = SQLiteConfig().apply {
                            setJournalMode(SQLiteConfig.JournalMode.WAL)
                            setBusyTimeout(Database.BUSY_TIMEOUT_MS)
                        }
                        checkNotNull(JDBC().connect("jdbc:sqlite:$file", config.toProperties())).use { c ->
                            barrier.await()
                            Migrations.run(c)
                            version(c)
                        }
                    }
                }
                // 한쪽이 먼저 적용했으면 다른 쪽은 건너뛰어야 하므로 둘 다 예외 없이 최신 버전이어야 한다
                results.forEach { assertEquals(Migrations.ALL.size, it.get(30, java.util.concurrent.TimeUnit.SECONDS)) }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun projectPathIndexExistsAfterMigration() {
        val db = newDatabase()
        val columns = db.submitAndWait { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA index_info('idx_note_project_path')").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("name") else null }.toList()
                }
            }
        }
        assertEquals(listOf("project", "rel_path"), columns)
    }
}
