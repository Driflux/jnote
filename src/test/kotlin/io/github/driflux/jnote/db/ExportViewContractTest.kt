package io.github.driflux.jnote.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.sql.Connection
import java.util.Properties

/** v_notes_export는 에이전트가 읽는 외부 계약이다. 열 이름과 순서가 바뀌면 이 테스트가 깨져야 한다. */
class ExportViewContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<Database>()

    private fun newDatabase(): Database =
        Database(tmp.root.toPath().resolve("jnote.db")).also { opened += it }

    @After
    fun closeAll() = opened.forEach { it.close() }

    private val contract = listOf(
        "uid", "kind", "project", "rel_path", "class_name", "start_line", "end_line",
        "snapshot", "status", "title", "body", "ai_source", "source_url", "prompt",
        "created_at", "updated_at", "tags",
    )

    private fun columns(c: Connection): List<String> =
        c.createStatement().use { s ->
            s.executeQuery("SELECT * FROM v_notes_export").use { rs ->
                (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
            }
        }

    private fun exec(c: Connection, sql: String) = c.createStatement().use { it.executeUpdate(sql) }

    private fun tagsOf(c: Connection, uid: String): String? =
        c.createStatement().use { s ->
            s.executeQuery("SELECT tags FROM v_notes_export WHERE uid = '$uid'").use { it.next(); it.getString(1) }
        }

    @Test
    fun columnNamesAndOrderMatchContract() {
        assertEquals(contract, newDatabase().submitAndWait { columns(it) })
    }

    @Test
    fun contractSurvivesLaterMigration() {
        val db = newDatabase()
        db.submitAndWait { Migrations.run(it, Migrations.ALL + "CREATE TABLE later(x)") }
        assertEquals(contract, db.submitAndWait { columns(it) })
    }

    @Test
    fun upgradingAV1DatabaseExposesItsDataThroughTheViewAndBacksUpV1() {
        val file = tmp.root.toPath().resolve("jnote.db")
        val config = SQLiteConfig().apply { setJournalMode(SQLiteConfig.JournalMode.WAL) }
        checkNotNull(JDBC().connect("jdbc:sqlite:$file", config.toProperties())).use { c ->
            Migrations.run(c, Migrations.ALL.take(1))
            exec(c, "INSERT INTO note(uid, kind, title, created_at, updated_at) VALUES ('a', 'FREE', 't', 1, 1)")
            exec(c, "INSERT INTO tag(name) VALUES ('beta'), ('alpha')")
            exec(c, "INSERT INTO note_tag(note_id, tag_id) SELECT n.id, t.id FROM note n, tag t")
            Migrations.run(c)
            assertEquals(contract, columns(c))
            assertEquals("alpha,beta", tagsOf(c, "a"))
        }
        val backup = file.resolveSibling("jnote.db.bak")
        assertTrue(Files.exists(backup))
        val backedUpVersion = checkNotNull(JDBC().connect("jdbc:sqlite:$backup", Properties())).use { b ->
            b.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }
        assertEquals(1, backedUpVersion)
    }

    @Test
    fun tagsAreSortedCommaSeparatedAndNullWhenNone() {
        val db = newDatabase()
        db.submitAndWait { c ->
            exec(c, "INSERT INTO note(uid, kind, title, created_at, updated_at) VALUES ('a', 'FREE', 't', 1, 1)")
            exec(c, "INSERT INTO note(uid, kind, title, created_at, updated_at) VALUES ('b', 'FREE', 't', 1, 1)")
            exec(c, "INSERT INTO tag(name) VALUES ('zeta'), ('alpha'), ('mid')")
            exec(c, "INSERT INTO note_tag(note_id, tag_id) SELECT n.id, t.id FROM note n, tag t WHERE n.uid = 'a'")
        }
        assertEquals("alpha,mid,zeta", db.submitAndWait { tagsOf(it, "a") })
        assertNull(db.submitAndWait { tagsOf(it, "b") })
        assertEquals(2, db.submitAndWait { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM v_notes_export").use { it.next(); it.getInt(1) } }
        })
    }
}
