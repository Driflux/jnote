package io.github.driflux.jnote.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CancellationException

class DatabaseCopierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<Database>()

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    // 커밋된 행이 WAL에만 있고 본 파일에는 아직 없는 DB를 만든다
    private fun walOnlyDatabase(name: String): Database =
        Database(path(name)).also {
            opened += it
            it.submitAndWait { c ->
                c.createStatement().use { s ->
                    s.execute("create table probe(x integer)")
                    s.execute("insert into probe values (42)")
                }
            }
        }

    private fun probeCount(file: Path): Int? =
        JDBC().connect("jdbc:sqlite:$file", Properties()).use { c ->
            try {
                c.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next(); it.getInt(1) } }
            } catch (_: java.sql.SQLException) {
                null
            }
        }

    private fun filesIn(dir: Path): Set<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() }

    // 읽기 트랜잭션을 연 채로 두어 체크포인트가 끝나지 못하게 한다
    private fun holdReadTransaction(file: Path): java.sql.Connection =
        JDBC().connect("jdbc:sqlite:$file", Properties())!!.also { reader ->
            reader.autoCommit = false
            reader.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next() } }
        }

    @Test
    fun busyCheckpointFailsTheCopyInsteadOfCopyingAnIncompleteFile() {
        val db = walOnlyDatabase("src.db")
        holdReadTransaction(path("src.db")).use {
            // 읽는 쪽 스냅샷 뒤의 커밋은 체크포인트가 본 파일로 옮기지 못한다
            db.submitAndWait { c ->
                c.createStatement().use { s ->
                    s.execute("insert into probe values (43)")
                    // 시도마다 5초씩 기다리지 않도록 이 연결의 대기 시간만 줄인다
                    s.execute("PRAGMA busy_timeout = 50")
                }
            }
            assertThrows(IllegalStateException::class.java) {
                DatabaseCopier.copy(path("src.db"), path("copy.db"), db) {}
            }
        }
        assertEquals(setOf("src.db", "src.db-shm", "src.db-wal"), filesIn(tmp.root.toPath()))
        assertEquals(2, db.submitAndWait { c ->
            c.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next(); it.getInt(1) } }
        })
    }

    @Test
    fun busyCheckpointOfALeftoverWalFailsTheCopy() {
        walOnlyDatabase("live.db")
        Files.copy(path("live.db"), path("crashed.db"))
        Files.copy(path("live.db-wal"), path("crashed.db-wal"))
        holdReadTransaction(path("crashed.db")).use {
            assertThrows(IllegalStateException::class.java) {
                DatabaseCopier.copy(path("crashed.db"), path("out/copy.db"), null) {}
            }
        }
        assertFalse(Files.exists(path("out/copy.db")))
    }

    @Test
    fun checkpointBeforeCopyMakesTheCopyComplete() {
        val db = walOnlyDatabase("src.db")
        DatabaseCopier.copy(path("src.db"), path("out/copy.db"), db) {}
        assertEquals(1, probeCount(path("out/copy.db")))
        assertTrue(Files.exists(path("src.db")))
    }

    @Test
    fun neverOpenedAndMissingSourceCreatesNothing() {
        DatabaseCopier.copy(path("none.db"), path("out/copy.db"), null) {}
        assertFalse(Files.exists(path("out")))
    }

    @Test
    fun neverOpenedExistingSourceIsCopied() {
        walOnlyDatabase("src.db").close()
        DatabaseCopier.copy(path("src.db"), path("copy.db"), null) {}
        assertEquals(1, probeCount(path("copy.db")))
    }

    @Test
    fun leftoverWalOfAnUnopenedDatabaseIsCheckpointedBeforeCopy() {
        val db = walOnlyDatabase("live.db")
        // 비정상 종료로 -wal이 남은 상태를 흉내 낸다: 연결이 열린 채로 본 파일과 -wal을 복사한다
        Files.copy(path("live.db"), path("crashed.db"))
        Files.copy(path("live.db-wal"), path("crashed.db-wal"))
        assertEquals(1, db.submitAndWait { 1 })
        DatabaseCopier.copy(path("crashed.db"), path("copy.db"), null) {}
        assertEquals(1, probeCount(path("copy.db")))
    }

    private fun bigSource(): Path = path("big.db").also { Files.write(it, ByteArray(4 shl 20)) }

    private fun assertNoLeftovers() {
        assertFalse(Files.exists(path("out/copy.db")))
        val leftovers = Files.list(path("out")).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun cancelMidCopyLeavesNoPartialFile() {
        val source = bigSource()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            DatabaseCopier.copy(source, path("out/copy.db"), null) { if (++calls == 3) throw CancellationException() }
        }
        assertNoLeftovers()
        assertEquals(4L shl 20, Files.size(source))
    }

    @Test
    fun failureMidCopyLeavesNoPartialFile() {
        val source = bigSource()
        var calls = 0
        val error = assertThrows(IllegalStateException::class.java) {
            DatabaseCopier.copy(source, path("out/copy.db"), null) { if (++calls == 2) error("boom") }
        }
        assertEquals("boom", error.message)
        assertNoLeftovers()
    }

    @Test
    fun staleWalOrShmNextToTheTargetRefusesTheCopy() {
        walOnlyDatabase("src.db").close()
        for (suffix in listOf("-wal", "-shm")) {
            val stale = path("copy.db$suffix").also { Files.writeString(it, "stale") }
            val error = assertThrows(FileAlreadyExistsException::class.java) {
                DatabaseCopier.copy(path("src.db"), path("copy.db"), null) {}
            }
            assertEquals(stale.toString(), error.file)
            assertEquals("stale", Files.readString(stale))
            assertFalse(Files.exists(path("copy.db")))
            Files.delete(stale)
        }
        assertTrue(filesIn(tmp.root.toPath()).none { it.endsWith(".copying") })
    }

    @Test
    fun targetCreatedDuringTheCopyIsNotOverwritten() {
        val source = bigSource()
        val target = path("out/copy.db")
        val error = assertThrows(FileAlreadyExistsException::class.java) {
            DatabaseCopier.copy(source, target, null) {
                // 복사하는 사이 다른 프로그램이 같은 경로에 파일을 만든 상황
                if (!Files.exists(target)) Files.writeString(target, "other")
            }
        }
        assertEquals(target.toString(), error.file)
        assertEquals("other", Files.readString(target))
        assertEquals(setOf("copy.db"), filesIn(path("out")))
    }

    @Test
    fun cancelInsideTheDatabaseThreadKeepsTheDatabaseUsable() {
        val db = walOnlyDatabase("src.db")
        assertThrows(CancellationException::class.java) {
            DatabaseCopier.copy(path("src.db"), path("out/copy.db"), db) { throw CancellationException() }
        }
        assertNoLeftovers()
        assertEquals(1, db.submitAndWait { c ->
            c.createStatement().use { s -> s.executeQuery("select count(*) from probe").use { it.next(); it.getInt(1) } }
        })
    }
}
