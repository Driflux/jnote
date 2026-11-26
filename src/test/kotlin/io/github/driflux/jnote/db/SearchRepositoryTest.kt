package io.github.driflux.jnote.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import java.sql.Connection
import java.util.Properties

class SearchRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<Database>()
    private fun newDatabase(name: String = "jnote.db"): Database =
        Database(tmp.root.toPath().resolve(name)).also { opened += it }

    @After
    fun closeAll() = opened.forEach { it.close() }

    private val db by lazy { newDatabase() }
    private val repo by lazy { SearchRepository(db) }

    private var seq = 0

    /** 노트를 일반 SQL로 넣고 id를 돌려준다. */
    private fun note(
        title: String = "t",
        body: String = "",
        project: String? = null,
        snapshot: String? = null,
        className: String? = null,
        prompt: String? = null,
        updatedAt: Long = 1,
        tags: List<String> = emptyList(),
    ): Long = db.submitAndWait { c -> insertNote(c, title, body, project, snapshot, className, prompt, updatedAt, tags) }

    private fun insertNote(
        c: Connection, title: String, body: String, project: String?, snapshot: String?,
        className: String?, prompt: String?, updatedAt: Long, tags: List<String>,
    ): Long {
        c.prepareStatement(
            "INSERT INTO note(uid, kind, project, class_name, snapshot, title, body, prompt, created_at, updated_at) " +
                "VALUES (?, 'FREE', ?, ?, ?, ?, ?, ?, 1, ?)",
        ).use { ps ->
            ps.setString(1, "u" + seq++)
            ps.setString(2, project)
            ps.setString(3, className)
            ps.setString(4, snapshot)
            ps.setString(5, title)
            ps.setString(6, body)
            ps.setString(7, prompt)
            ps.setLong(8, updatedAt)
            ps.executeUpdate()
        }
        val id = c.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
        for (tag in tags) {
            c.prepareStatement("INSERT OR IGNORE INTO tag(name) VALUES (?)").use { it.setString(1, tag); it.executeUpdate() }
            c.prepareStatement("INSERT INTO note_tag(note_id, tag_id) SELECT ?, id FROM tag WHERE name = ?")
                .use { it.setLong(1, id); it.setString(2, tag); it.executeUpdate() }
        }
        return id
    }

    private fun exec(sql: String) = db.submitAndWait { c -> c.createStatement().use { it.executeUpdate(sql) } }

    private fun ftsIds(q: String): List<Long> = db.submitAndWait { c ->
        c.prepareStatement("SELECT rowid FROM note_fts WHERE note_fts MATCH ? ORDER BY rowid").use { ps ->
            ps.setString(1, "\"" + q + "\"")
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
    }

    private fun search(q: String, project: String? = null, all: Boolean = true, limit: Int = 200): List<Long> =
        repo.search(q, project, all, limit).get()

    private fun plan(query: String): List<String> = db.submitAndWait { c ->
        val escape = query.any { it in "%_\\" }
        c.prepareStatement("EXPLAIN QUERY PLAN " + SearchRepository.sql(escape)).use { ps ->
            ps.setString(1, "%$query%")
            ps.setInt(2, 1)
            ps.setString(3, null)
            ps.setInt(4, 10)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) } }
        }
    }

    @Test
    fun migrationV3FromEmptyDatabaseCreatesFtsAndTriggers() {
        val names = db.submitAndWait { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'note_fts%'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
        assertTrue(names.containsAll(listOf("note_fts", "note_fts_ai", "note_fts_ad", "note_fts_au")))
    }

    @Test
    fun migrationV3BackfillsRowsFromPopulatedV2Database() {
        // Database는 열 때 마지막 버전까지 올리므로, v2 파일은 연결을 직접 열어 만든다
        val file = tmp.root.toPath().resolve("old.db")
        JDBC().connect("jdbc:sqlite:$file", Properties())!!.use { c ->
            Migrations.run(c, Migrations.ALL.take(2))
            assertEquals(2, c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } })
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO note(uid, kind, title, body, created_at, updated_at) VALUES ('a', 'FREE', 'old', '결제 취소건을 확인', 1, 1)",
                )
            }
        }
        // 같은 파일을 다시 열면 v3가 적용되고 기존 행이 백필된다
        val reopened = newDatabase("old.db")
        assertEquals(1, SearchRepository(reopened).search("취소", null, true).get().size)
        val exported = reopened.submitAndWait { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM v_notes_export").use { it.next(); it.getInt(1) } }
        }
        assertEquals(1, exported)
    }

    @Test
    fun triggersKeepIndexInSync() {
        val id = note(body = "alpha beta")
        assertEquals(listOf(id), ftsIds("alpha"))
        exec("UPDATE note SET body = 'gamma delta' WHERE id = $id")
        assertEquals(emptyList<Long>(), ftsIds("alpha"))
        assertEquals(listOf(id), ftsIds("gamma"))
        exec("DELETE FROM note WHERE id = $id")
        assertEquals(emptyList<Long>(), ftsIds("gamma"))
        // 삭제 뒤에도 인덱스가 손상되지 않아야 한다
        db.submitAndWait { c -> c.createStatement().use { it.execute("INSERT INTO note_fts(note_fts) VALUES ('integrity-check')") } }
    }

    @Test
    fun twoCharacterQueriesMatchKoreanAndAscii() {
        val ko = note(body = "취소 처리")
        val en = note(body = "an id field")
        note(body = "unrelated")
        assertEquals(listOf(ko), search("취소"))
        assertEquals(listOf(en), search("id"))
    }

    @Test
    fun threeOrMoreCharacterQueriesMatchAnyIndexedColumn() {
        val title = note(title = "Refactor Handler")
        val body = note(body = "call the handler twice")
        val snap = note(snapshot = "fun handlerOf()")
        val cls = note(className = "PaymentHandler")
        val prompt = note(prompt = "explain handler")
        note(title = "other")
        assertEquals(setOf(title, body, snap, cls, prompt), search("HANDLER").toSet())
    }

    @Test
    fun koreanParticleStillMatches() {
        val id = note(body = "결제 취소건을 처리한다")
        assertEquals(listOf(id), search("취소"))
        assertEquals(listOf(id), search("취소건"))
        assertEquals(emptyList<Long>(), search("환불"))
    }

    @Test
    fun specialCharactersAreLiteral() {
        val pct = note(body = "rate 50% off")
        val under = note(body = "snake_case name")
        val slash = note(body = "path a\\b here")
        note(body = "rate 500 off")
        note(body = "snakeXcase name")
        assertEquals(listOf(pct), search("50%"))
        assertEquals(listOf(under), search("e_c"))
        assertEquals(listOf(slash), search("a\\b"))
        assertEquals(emptyList<Long>(), search("%%"))
        assertEquals(emptyList<Long>(), search("__"))
    }

    @Test
    fun matchesTagNames() {
        val tagged = note(title = "x", tags = listOf("billing", "urgent"))
        note(title = "y", tags = listOf("other"))
        assertEquals(listOf(tagged), search("billing"))
        assertEquals(listOf(tagged), search("ur"))
    }

    @Test
    fun noteMatchingTextAndTagAppearsOnce() {
        val id = note(body = "billing notes", tags = listOf("billing"))
        assertEquals(listOf(id), search("billing"))
    }

    @Test
    fun projectFilterIncludesGlobalNotes() {
        val a = note(body = "needle", project = "A")
        val b = note(body = "needle", project = "B")
        val g = note(body = "needle", project = null)
        assertEquals(setOf(a, g), search("needle", "A", all = false).toSet())
        assertEquals(setOf(b, g), search("needle", "B", all = false).toSet())
        assertEquals(setOf(a, b, g), search("needle", "A", all = true).toSet())
    }

    @Test
    fun resultsAreMostRecentlyModifiedFirstAndLimited() {
        val old = note(body = "needle", updatedAt = 10)
        val newest = note(body = "needle", updatedAt = 30)
        val mid = note(body = "needle", updatedAt = 20)
        assertEquals(listOf(newest, mid, old), search("needle"))
        assertEquals(listOf(newest, mid), search("needle", limit = 2))
    }

    @Test
    fun updatingNoteReordersIt() {
        val a = note(body = "needle", updatedAt = 10)
        val b = note(body = "needle", updatedAt = 20)
        exec("UPDATE note SET updated_at = 30 WHERE id = $a")
        assertEquals(listOf(a, b), search("needle"))
    }

    @Test
    fun tooShortQueryReturnsEmptyWithoutQuerying() {
        assertEquals(emptyList<Long>(), SearchRepository(newDatabase("fresh.db")).search("a", null, true).get())
        val fresh = newDatabase("fresh2.db")
        assertEquals(emptyList<Long>(), SearchRepository(fresh).search("", null, true).get())
        assertEquals(emptyList<Long>(), SearchRepository(fresh).search("가", null, true).get())
        assertEquals(false, fresh.isOpen)
    }

    @Test
    fun asciiIsCaseInsensitiveAndKoreanHasNoCase() {
        val id = note(body = "NullPointerException 발생")
        assertEquals(listOf(id), search("nullpointer"))
        assertEquals(listOf(id), search("NULLPOINTER"))
        assertEquals(listOf(id), search("발생"))
    }

    @Test
    fun threeOrMoreCharacterQueryUsesTrigramIndex() {
        val ftsScans = plan("abc").filter { "note_fts" in it }
        assertTrue("plan: $ftsScans", ftsScans.isNotEmpty() && ftsScans.all { Regex("INDEX 0:L\\d").containsMatchIn(it) })
    }

    // 알려진 한계: 밑줄이 든 질의는 ESCAPE 때문에 FTS 인덱스를 못 쓰고 전체 스캔으로 떨어진다
    @Test
    fun queryWithUnderscoreFallsBackToScan() {
        val ftsScans = plan("a_c").filter { "note_fts" in it }
        assertTrue("plan: $ftsScans", ftsScans.isNotEmpty() && ftsScans.none { Regex("INDEX 0:L\\d").containsMatchIn(it) })
    }

    @Test
    fun blankAndPaddedQueriesAreTrimmedBeforeLengthCheck() {
        val id = note(body = "needle")
        assertEquals(emptyList<Long>(), search("   "))
        assertEquals(emptyList<Long>(), search(" a "))
        assertEquals(emptyList<Long>(), search("\t\n"))
        assertEquals(listOf(id), search("  needle \n"))
        assertEquals(listOf(id), search(" ne "))
    }

    @Test
    fun projectFilterAppliesToTagOnlyMatches() {
        val a = note(title = "x", project = "A", tags = listOf("billing"))
        val b = note(title = "y", project = "B", tags = listOf("billing"))
        val g = note(title = "z", project = null, tags = listOf("billing"))
        assertEquals(setOf(a, g), search("billing", "A", all = false).toSet())
        assertEquals(setOf(b, g), search("billing", "B", all = false).toSet())
    }

    @Test
    fun nullProjectWithoutAllProjectsReturnsOnlyGlobalNotes() {
        note(body = "needle", project = "A")
        val g = note(body = "needle", project = null)
        assertEquals(listOf(g), search("needle", null, all = false))
    }
}
