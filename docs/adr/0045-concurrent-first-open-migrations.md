# 0045. Concurrent first opens of one database file

- Status: Proposed
- Date: 2026-11-26
- Issue: #45
- PR: #58

## Context
Two `Database` objects, or two IDE processes, can open the same `jnote.db` at the same moment. Two IDE windows that start together right after a fresh install or a plugin update do this. ADR 0002 opens the connection in WAL mode and runs the pending migrations on the first `submit`. When two connections open one file at once, they fail in two places.

- **The switch to WAL on a new file.** Both connections read the file header under a shared lock and then ask for the write lock. SQLite does not call the busy handler for that step, because waiting could deadlock, so one connection gets `SQLITE_BUSY` at once even with `busy_timeout` 5000 ms. The SQLite documentation for `sqlite3_busy_handler` describes this case. With two raw connections that switch one new file at the same moment, 152 of 200 attempts failed this way.
- **`Migrations.run` read `user_version` before it took any lock.** Both connections read the same version and both applied the next script. One of them failed with `table note already exists`, or with the same error for `v_notes_export` or `note_fts`. On an upgrade, both connections also copied to the same `jnote.db.bak.tmp`, so one of them failed with `FileAlreadyExistsException` or `NoSuchFileException`.

Before this change, two `Database` objects that opened one new file at once failed in 66 of 200 attempts (65 at the WAL switch and 1 at a migration). Two `Database` objects that upgraded one v1 file at once failed in 179 of 200 attempts.

This ADR changes how ADR 0002 runs migrations and takes the backup. The schema, the `user_version` scheme, the single `jnote.db.bak`, and the rule that the upgrade stops when the WAL checkpoint cannot finish stay the same.

## Decision
- **The WAL switch tries again on `SQLITE_BUSY`.** `Database.open` no longer sets the journal mode through `SQLiteConfig`. It runs `PRAGMA journal_mode = WAL` itself after the connection opens. On `SQLITE_BUSY` it waits 10 ms and tries again, until `BUSY_TIMEOUT_MS` has passed since the first try. One try can also wait in the busy handler, so the total wait can be longer than `BUSY_TIMEOUT_MS`. When the other connection finishes its switch, the header already says WAL, so the next try succeeds without a write. Any other error fails at once, as before.
- **All pending migrations run in one locked transaction.** When `Migrations.run` finds pending migrations, it starts `BEGIN IMMEDIATE`, which waits for the write lock through the busy handler. Inside that transaction it reads `user_version` again. If the version is still the one it read before the lock, it applies every remaining migration and commits once. Another connection sees either the starting version or the final version, never a version in between. This replaces the rule in ADR 0002 that each script runs in its own transaction. A failed script now rolls back every migration of that run, and `user_version` stays at the starting version.
- **A changed version starts the run again.** If the version under the lock is different, another connection upgraded the file in the meantime. The transaction ends with no change, and `Migrations.run` starts again from the new version. It reads the version, refuses a version newer than this plugin supports, runs the checkpoint, and takes the lock again. A connection from the same plugin release then finds the final version and returns. When two plugin releases share one file, the newer release continues from the version the older one left, with its own checkpoint and backup, and the older release refuses a file that the newer one upgraded, as ADR 0002 intends. The same restart happens when the checkpoint cannot finish but the version changed while it waited, because that checkpoint was for an upgrade that another connection already did. When the version did not change, a failed checkpoint still stops the upgrade.
- **The backup copy runs inside that transaction.** SQLite refuses a checkpoint inside a transaction with `SQLITE_LOCKED` (measured on sqlite-jdbc 3.54.1.0). So `WalCheckpoint.truncateOrFail` runs before `BEGIN IMMEDIATE`, and the upgrade still stops when it cannot finish (PR #28). The copy to `jnote.db.bak.tmp` and the move to `jnote.db.bak` run inside the transaction, only when the version under the lock is the version that the checkpoint was for, and only when that version is above 0.

## Alternatives considered
- **One `BEGIN IMMEDIATE` per migration**, as the issue first proposed. Connection A commits v2 and then takes the lock again for v3. In that gap, connection B can read v2, take the lock first, and copy the v2 file over the v1 backup that A made. On a new file, the same gap makes B create a `.bak` that a fresh install must not have. The code review found this interleaving by reading the code. A stress run with staggered starts did not hit it in 400 attempts, but nothing in the code prevents it. Rejected.
- **Continue from the version found under the lock, without a new checkpoint or backup.** It needs no second pass, but when an older release upgrades first, the newer release takes no backup of the version it upgrades. A note that the older release commits in between is then in no backup. A test that parks the older upgrade reproduced this. Rejected.
- **A lock file next to `jnote.db`**, for example `FileChannel.lock` on `jnote.db.lock`. It would put the checkpoint, the copy, and the migrations in one critical section. It also adds a file that stays in `~/.jnote` and a second lock beside the SQLite write lock, which already orders writers. Rejected.
- **Try `Database.open` again when a migration fails with "already exists".** It matches an error message, it hides a real schema error that has the same text, and a late copy can still overwrite the pre-upgrade `.bak`. Rejected for migrations. For the WAL switch, a retry is the fix, because SQLite returns `SQLITE_BUSY` there by design and leaves the wait to the application.
- **Serialize the open in the executor only.** Each `Database` already runs its work on one thread. Two `Database` objects, or two IDE processes, have separate executors, so a lock inside one process does not cover the case that fails. Rejected.
- **Take the checkpoint inside the lock as well.** SQLite does not allow this. `wal_checkpoint` fails with `SQLITE_LOCKED` inside a transaction, and without the checkpoint the main file can miss committed pages that are still in the WAL.
- **Copy to a private temp file before the lock, and move it to `.bak` inside the lock.** The copy would stay right after the checkpoint. But each connection needs a unique temp name, and a crash leaves those files behind with nothing to clean them up. Rejected. A copy inside the lock keeps the single fixed `jnote.db.bak.tmp`, which the next backup overwrites.
- **Ignore `SQLITE_BUSY` from the WAL switch.** The connection would usually end up in WAL after the other connection commits its switch. If that other switch failed, both connections would stay in rollback mode and nothing would report it. Rejected.

## Consequences
- Pros
  - Two connections that open or upgrade one file at once both succeed. After the change, a stress run passed 400 of 400 attempts for each case, with start offsets from 0 to 19.5 ms.
  - Each migration runs once. Each upgrade that runs writes `jnote.db.bak` from the version it starts at. When two connections of one release upgrade at once, only one of them upgrades and writes the backup. The gaps under "Cons" still apply to that copy.
  - An upgrade is all or nothing. A failed script leaves no half-upgraded schema.
  - No new dependency and no startup work. All of it runs on the `JNote-DB` thread.
- Cons and what we accept
  - Another connection waits for the whole upgrade, up to `busy_timeout`. If an upgrade holds the lock longer, the open of that other connection fails, and its next `submit` opens again.
  - A commit from another connection between the checkpoint and `BEGIN IMMEDIATE` is not in `jnote.db.bak`. If that connection also runs a passive checkpoint while the copy runs, the copy can mix two states. The code before this change copied with no lock at all and had the same gaps.
- Follow-up work
  - Evaluate a backup through the SQLite backup API from a second connection, while the upgrade holds the write lock. It would close both gaps of the file copy. The code review checked this with the `sqlite3` tool. It is not checked with sqlite-jdbc yet.
