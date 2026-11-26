package io.github.driflux.jnote.db

import java.sql.Connection
import java.util.concurrent.CompletableFuture

/**
 * 노트 검색. 경로는 하나뿐이다: note_fts(trigram)에 대한 LIKE '%q%'.
 * 3자 이상은 trigram 인덱스를 쓰고, 2자는 SQLite가 자동으로 전체 스캔으로 처리한다.
 * ESCAPE 절을 붙이면 FTS5가 LIKE 가속을 포기하고 전체 스캔을 하므로, % _ \ 가 없는 질의에는 ESCAPE를 붙이지 않는다.
 * 태그 이름은 FTS 대상이 아니므로 note_tag/tag에 대한 LIKE를 합집합으로 붙인다.
 */
class SearchRepository(private val database: Database) {

    /** 일치한 노트 id를 최근 수정 순으로 돌려준다. 질의가 너무 짧으면 DB에 가지 않고 빈 목록을 돌려준다. */
    fun search(
        query: String,
        project: String?,
        allProjects: Boolean,
        limit: Int = DEFAULT_LIMIT,
    ): CompletableFuture<List<Long>> {
        val trimmed = query.trim()
        if (trimmed.codePointCount(0, trimmed.length) < MIN_QUERY_LENGTH) {
            return CompletableFuture.completedFuture(emptyList())
        }
        val needsEscape = trimmed.any { it in SPECIAL }
        val pattern = "%" + (if (needsEscape) escapeLike(trimmed) else trimmed) + "%"
        return database.submit { run(it, pattern, needsEscape, project, allProjects, limit) }
    }

    private fun run(c: Connection, pattern: String, needsEscape: Boolean, project: String?, allProjects: Boolean, limit: Int): List<Long> {
        return c.prepareStatement(sql(needsEscape)).use { ps ->
            ps.setString(1, pattern)
            ps.setInt(2, if (allProjects) 1 else 0)
            ps.setString(3, project)
            ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1)) }
            }
        }
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEFAULT_LIMIT = 200
        private val SPECIAL = setOf('%', '_', '\\')
        private val COLUMNS = listOf("title", "body", "snapshot", "class_name", "prompt")

        internal fun sql(needsEscape: Boolean): String {
            val escape = if (needsEscape) " ESCAPE '\\'" else ""
            val matchSql = COLUMNS.joinToString(" OR ") { "$it LIKE ?1$escape" }
            return """
                SELECT n.id FROM note n
                 WHERE (n.id IN (SELECT rowid FROM note_fts WHERE $matchSql)
                        OR n.id IN (SELECT nt.note_id FROM note_tag nt JOIN tag t ON t.id = nt.tag_id
                                     WHERE t.name LIKE ?1$escape))
                   AND (?2 = 1 OR n.project IS NULL OR n.project = ?3)
                 ORDER BY n.updated_at DESC, n.id DESC
                 LIMIT ?4
            """.trimIndent()
        }

        /** 사용자 입력의 % _ 와 이스케이프 문자 자체를 문자 그대로 매칭하도록 한다. */
        internal fun escapeLike(query: String): String =
            query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
