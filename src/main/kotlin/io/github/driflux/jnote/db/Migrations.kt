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
        check(current <= migrations.size) {
            "Database schema version $current is newer than this JNote version supports (${migrations.size}). Update the plugin."
        }
        if (current == migrations.size) return
        if (current > 0) backup(connection)
        for (version in current + 1..migrations.size) apply(connection, version, migrations[version - 1])
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

    private fun apply(connection: Connection, version: Int, sql: String) {
        // 쓰기 잠금을 먼저 잡아야 다른 프로세스와 동시에 시작해도 같은 버전을 두 번 적용하지 않는다
        execute(connection, "BEGIN IMMEDIATE")
        try {
            // 잠금을 잡기 전에 다른 프로세스가 먼저 적용했을 수 있으므로 버전을 다시 확인한다
            if (userVersion(connection) < version) {
                connection.createStatement().use { s ->
                    s.executeUpdate(sql)
                    // user_version은 트랜잭션에 포함되므로 스크립트가 실패하면 함께 되돌아간다
                    s.executeUpdate("PRAGMA user_version = $version")
                }
            }
            execute(connection, "COMMIT")
        } catch (e: Throwable) {
            // 롤백 실패가 원래 예외를 가리지 않게 덧붙여 둔다
            try {
                execute(connection, "ROLLBACK")
            } catch (rollbackError: Exception) {
                e.addSuppressed(rollbackError)
            }
            throw e
        }
    }

    private fun execute(connection: Connection, sql: String) {
        connection.createStatement().use { it.execute(sql) }
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
        WalCheckpoint.truncateOrFail(connection)
        val source = Paths.get(file)
        val backup = source.resolveSibling(source.fileName.toString() + ".bak")
        val partial = source.resolveSibling(source.fileName.toString() + ".bak.tmp")
        // 복사가 중간에 실패해도 이전 백업이 남도록 임시 파일에 복사한 뒤 한 번에 바꿔 끼운다
        try {
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
