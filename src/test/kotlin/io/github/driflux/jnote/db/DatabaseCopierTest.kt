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

    @Test
    fun copyingOnlyTheMainFileMissesDataStillInWal() {
        val db = walOnlyDatabase("src.db")
        DatabaseCopier.copyFile(path("src.db"), path("naive.db")) {}
        assertEquals(null, probeCount(path("naive.db")))
        assertEquals(1, db.submitAndWait { 1 })
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
