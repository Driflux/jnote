# 0002. SQLite access, migrations, and the export view

- Status: Proposed
- Date: 2026-11-26
- Issue: #2
- PR: (to be filled)

## Context
JNote stores notes in one SQLite file (intent.md section 4) and must never slow the IDE down (intent.md section 2): no startup work, no DB access on the EDT, one dependency. AI agents read notes through a view, so its shape is an external contract (intent.md section 10.4). The schema will change in later issues (FTS in #11), so versioned migrations are needed from the start.

## Decision
- **Dependency.** `org.xerial:sqlite-jdbc` 3.54.1.0 as `implementation`. It is the only new dependency.
- **Connection.** The connection is created with `org.sqlite.JDBC().connect(url, props)`, not `DriverManager`, because the IDE plugin classloader does not reliably see `DriverManager` drivers. A `BasePlatformTestCase` proves the driver and its native library load in the plugin test sandbox.
- **Pragmas.** WAL journal mode, `busy_timeout` 5000 ms, `foreign_keys` on. One connection per `Database`.
- **Threading.** `Database` owns a single-thread daemon executor (thread name `JNote-DB`). Every access goes through `submit(block): CompletableFuture<T>`; the connection is opened and used only on that thread, so it is never touched from the EDT and needs no locking. Callers update the UI with `invokeLater` when the future completes.
- **Lazy loading.** `DatabaseService` is an application-level `@Service` created by annotation only (no `plugin.xml` entry). It builds the `Database` on first use, and the connection opens on the first `submit`. `dispose()` closes the DB only if it was ever created, so a plugin unload costs nothing when unused and releases the file otherwise.
- **Path.** `DatabasePaths.default()` returns `~/.jnote/jnote.db`. `Database` takes a `Path`; the path setting is issue #15.
- **Migrations.** A `Migrations` object holds a list of SQL scripts indexed by version (index 0 is version 1). `Migrations.run(connection)` reads `PRAGMA user_version` and applies each missing script in its own transaction, then sets `user_version` inside the same transaction (SQLite pragmas are transactional for `user_version`). A failed script rolls back and leaves `user_version` unchanged. Adding a schema change means appending one entry.
- **Backup.** Right before applying migrations to an existing database (`user_version > 0`) that has pending migrations, run `PRAGMA wal_checkpoint(TRUNCATE)` and copy the file to `jnote.db.bak` next to it, overwriting the previous backup. After a TRUNCATE checkpoint the main file holds all committed data, so a plain file copy is consistent. A fresh database (version 0) gets no backup.
- **Schema v1.** `note`, `tag`, `note_tag`, and an index on `note(project, rel_path)`, with columns exactly as in intent.md section 4 including the AI fields. `uid` is unique; `kind` and `status` have CHECK constraints; `note_tag` cascades on delete. FTS5 is not in v1.
- **Export view.** Migration v2 creates `v_notes_export`: one row per note with `uid, kind, project, rel_path, class_name, start_line, end_line, snapshot, status, title, body, ai_source, source_url, prompt, created_at, updated_at, tags`. `tags` is a sorted, comma-separated list (`group_concat`), NULL when there are none. A view needs no table change. The column names and order are a fixed contract for agents; a contract test asserts them and re-checks after an extra test-only migration. Any later migration that touches `note`, `tag`, or `note_tag` must drop and recreate the view in the same transaction so it keeps these columns.

## Alternatives considered
- `DriverManager.getConnection`: fails or is flaky under the plugin classloader. Rejected.
- Connection pool or one connection per call: SQLite allows one writer, WAL readers add little for a local single-user tool, and a single connection on one thread is simplest and cheapest. Rejected.
- Coroutines (`Dispatchers.IO.limitedParallelism(1)`): adds a dependency on coroutine plumbing and does not guarantee one named thread. A plain executor is enough.
- Eager open from a startup activity: breaks the zero-startup rule.
- `VACUUM INTO` for backup: rewrites the whole DB and is slower on large files, though it needs no checkpoint. A checkpoint plus copy is cheaper and runs only on upgrades.
- Migration files on the classpath: more I/O and naming rules for little gain at this size. A list in code is easier to test.
- Exposing tables directly to agents: any internal schema change would break them. The view isolates them.

## Consequences
- Pros
  - No startup cost; the DB opens only when first needed, and never on the EDT.
  - Migrations are a plain table, each atomic, and tested against temp files.
  - Agents get a stable read surface.
- Cons and what we accept
  - One thread serializes all DB work; a slow query delays others (acceptable for a personal note store).
  - The native sqlite library adds to the plugin size and is extracted to a temp dir at first load.
  - Migrations that rebuild tables must remember to recreate the view.
- Follow-up work
  - DB path setting and switching (#15).
  - FTS5 as schema v3 (#11; v2 is the export view).
