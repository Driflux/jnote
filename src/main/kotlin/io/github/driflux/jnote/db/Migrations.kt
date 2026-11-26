package io.github.driflux.jnote.db

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.Connection

/** 스키마 버전 표. 인덱스 + 1이 user_version이며, 변경은 항목을 뒤에 추가하는 것으로만 한다. */
object Migrations {
    val ALL: List<String> = listOf(
        // v1
        """
        CREATE TABLE note (
            id INTEGER PRIMARY KEY,
            uid TEXT NOT NULL UNIQUE,
            kind TEXT NOT NULL CHECK(kind IN ('CODE','FREE')),
            project TEXT,
            rel_path TEXT,
            class_name TEXT,
            start_line INTEGER,
            end_line INTEGER,
            snapshot TEXT,
            status TEXT NOT NULL DEFAULT 'OK' CHECK(status IN ('OK','APPROX','ORPHAN','NEEDS_RELOCATE')),
            title TEXT NOT NULL,
            body TEXT NOT NULL DEFAULT '',
            ai_source TEXT,
            source_url TEXT,
            prompt TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE tag (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE
        );
        CREATE TABLE note_tag (
            note_id INTEGER NOT NULL REFERENCES note(id) ON DELETE CASCADE,
            tag_id INTEGER NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
            PRIMARY KEY(note_id, tag_id)
        );
        CREATE INDEX idx_note_project_path ON note(project, rel_path);
        """.trimIndent(),
    )

    fun run(connection: Connection, migrations: List<String> = ALL) {
        val current = userVersion(connection)
        if (current >= migrations.size) return
        if (current > 0) backup(connection)
        for (version in current + 1..migrations.size) apply(connection, version, migrations[version - 1])
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

    private fun apply(connection: Connection, version: Int, sql: String) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { s ->
                s.executeUpdate(sql)
                // user_version은 트랜잭션에 포함되므로 스크립트가 실패하면 함께 되돌아간다
                s.executeUpdate("PRAGMA user_version = $version")
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /** WAL을 본 파일로 비운 뒤 복사해야 .bak 하나만으로 일관된 상태가 된다. */
    private fun backup(connection: Connection) {
        val file = connection.createStatement().use { s ->
            s.executeQuery("PRAGMA database_list").use { rs ->
                var found: String? = null
                while (rs.next()) if (rs.getString("name") == "main") found = rs.getString("file")
                found
            }
        }
        if (file.isNullOrEmpty()) return
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        val source = Paths.get(file)
        Files.copy(source, source.resolveSibling(source.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
    }
}
