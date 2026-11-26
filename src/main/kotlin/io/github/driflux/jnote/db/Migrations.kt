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
        // v2: 에이전트가 읽는 외부 계약 뷰. 열 이름과 순서를 바꾸지 않는다
        """
        CREATE VIEW v_notes_export AS
        SELECT n.uid, n.kind, n.project, n.rel_path, n.class_name, n.start_line, n.end_line,
               n.snapshot, n.status, n.title, n.body, n.ai_source, n.source_url, n.prompt,
               n.created_at, n.updated_at,
               (SELECT group_concat(t.name, ',' ORDER BY t.name)
                  FROM note_tag nt JOIN tag t ON t.id = nt.tag_id
                 WHERE nt.note_id = n.id) AS tags
          FROM note n
        """.trimIndent(),
        // v3: 검색용 FTS5 trigram 외부 콘텐츠 테이블. note가 원본이라 트리거로 동기화하고 기존 행은 rebuild로 채운다
        """
        CREATE VIRTUAL TABLE note_fts USING fts5(
            title, body, snapshot, class_name, prompt,
            content='note', content_rowid='id', tokenize='trigram'
        );
        CREATE TRIGGER note_fts_ai AFTER INSERT ON note BEGIN
            INSERT INTO note_fts(rowid, title, body, snapshot, class_name, prompt)
            VALUES (new.id, new.title, new.body, new.snapshot, new.class_name, new.prompt);
        END;
        CREATE TRIGGER note_fts_ad AFTER DELETE ON note BEGIN
            INSERT INTO note_fts(note_fts, rowid, title, body, snapshot, class_name, prompt)
            VALUES ('delete', old.id, old.title, old.body, old.snapshot, old.class_name, old.prompt);
        END;
        CREATE TRIGGER note_fts_au AFTER UPDATE OF title, body, snapshot, class_name, prompt ON note BEGIN
            INSERT INTO note_fts(note_fts, rowid, title, body, snapshot, class_name, prompt)
            VALUES ('delete', old.id, old.title, old.body, old.snapshot, old.class_name, old.prompt);
            INSERT INTO note_fts(rowid, title, body, snapshot, class_name, prompt)
            VALUES (new.id, new.title, new.body, new.snapshot, new.class_name, new.prompt);
        END;
        INSERT INTO note_fts(note_fts) VALUES ('rebuild');
        """.trimIndent(),
    )

    fun run(connection: Connection, migrations: List<String> = ALL) {
        while (true) {
            val current = userVersion(connection)
            check(current <= migrations.size) {
                "Database schema version $current is newer than this JNote version supports (${migrations.size}). Update the plugin."
            }
            if (current == migrations.size) return
            // .bak은 본 파일만 복사하므로 WAL을 먼저 비워야 한다. 체크포인트는 트랜잭션 안에서 SQLITE_LOCKED로 실패해서 쓰기 잠금 전에 한다
            if (current > 0) {
                try {
                    WalCheckpoint.truncateOrFail(connection)
                } catch (e: IllegalStateException) {
                    // 기다리는 사이 다른 연결이 버전을 올렸으면 이 체크포인트는 필요 없었으므로 새 버전으로 다시 판단한다
                    if (userVersion(connection) != current) continue
                    throw e
                }
            }
            if (upgrade(connection, migrations, current)) return
        }
    }

    /**
     * 남은 마이그레이션을 한 트랜잭션으로 적용해 다른 연결이 중간 버전을 보지 않게 한다.
     * 잠금 안의 버전이 [from]이 아니면 다른 연결이 그사이 올린 것이므로 아무것도 하지 않고 false를 돌려준다.
     */
    private fun upgrade(connection: Connection, migrations: List<String>, from: Int): Boolean {
        connection.createStatement().use { s ->
            s.execute("BEGIN IMMEDIATE")
            try {
                val upgraded = userVersion(connection) == from
                if (upgraded) {
                    if (from > 0) backup(connection)
                    for (version in from + 1..migrations.size) {
                        s.executeUpdate(migrations[version - 1])
                        // user_version은 트랜잭션에 포함되므로 스크립트가 실패하면 함께 되돌아간다
                        s.executeUpdate("PRAGMA user_version = $version")
                    }
                }
                s.execute("COMMIT")
                return upgraded
            } catch (e: Throwable) {
                runCatching { s.execute("ROLLBACK") }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
        }
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

    private fun backup(connection: Connection) {
        val file = connection.createStatement().use { s ->
            s.executeQuery("PRAGMA database_list").use { rs ->
                var found: String? = null
                while (rs.next()) if (rs.getString("name") == "main") found = rs.getString("file")
                found
            }
        }
        if (file.isNullOrEmpty()) return
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
