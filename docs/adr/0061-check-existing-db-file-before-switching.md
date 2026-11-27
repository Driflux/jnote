# 0061. Check an existing DB file before switching to it

- Status: Proposed
- Date: 2026-11-27
- Issue: #61
- PR: #80

## Context
When the new DB path points to an existing file, `DbPathChange.plan` returned `SwitchToExisting` for any writable regular file (ADR 0015). After the confirmation dialog, `DbPathSwitcher.requireUsable` opens the file through `Database`, which switches it to WAL and runs the migrations. For another application's SQLite database this creates the JNote tables inside that file, and it does so even when a later step fails. Files that are not SQLite at all were only rejected after the user had confirmed the switch.

## Decision
- `DbPathChange.existingFileProblem` inspects an existing target without writing to it. `plan` calls it in the background plan task before returning `SwitchToExisting`, so a bad file is refused before the confirmation dialog. `DbPathSwitcher.requireUsable` calls it again right before its open-and-migrate probe, because the file can change while the dialog is open.
- The file is resolved to its real path first, so a symlink finds the `-wal` next to the real file.
- A 0-byte file is allowed: SQLite opens it as an empty database.
- Otherwise the first 16 bytes must be `SQLite format 3\0`; if not, `Invalid(NOT_SQLITE)`.
- Then the schema objects and `user_version` are read. "No tables" means no schema object of any kind (tables, views, indexes, triggers) other than `sqlite_*`. Allowed: a database with no objects and `user_version = 0`, or a database whose `user_version` is at least 1 and that has every table and column the `Migrations` definition creates up to that version (a version above the latest is compared with the latest schema, so a newer JNote database still reaches the existing "newer version" error). The expected tables and columns are not listed by hand: the first `user_version` migrations are applied to an in-memory database and its tables and columns are read. So an old valid JNote database at version 1 or 2 passes, and a foreign database that merely has a `note` table is `Invalid(NOT_JNOTE)`. Anything else is `Invalid(NOT_JNOTE)`.
- A non-empty `-journal` file next to the target is refused with `Invalid(HAS_JOURNAL)`, checked first. The immutable read does not replay a rollback journal, so a table dropped in an unfinished change could be missed and revived by journal recovery on the later write open. An empty `-journal` is ignored.
- How it reads without touching the file:
  - No `-wal` file: the main file holds all of its content, so the connection uses `mode=ro&immutable=1`. No lock is taken and no `-wal` or `-shm` file is created. Measured: a plain `mode=ro` connection on a WAL-mode file without sidecars created `-wal` and `-shm` and left them behind.
  - A `-wal` file exists (the DB is in use, or a writer crashed): the main file and its `-wal` are copied into a temp folder and the copy is read, so tables that exist only in the WAL are seen. Size and modification time of both files are compared before and after the copy; if either changed (a write or checkpoint happened between the two copies), the copy is retried, up to 3 times, and then the plan fails with the generic failure notification. The temp folder is deleted in `finally`; a failed delete is logged.
  - A `-wal` file exists and the main file plus `-wal` exceed 64 MiB: nothing is copied and the file is refused (`NOT_JNOTE`). The main file alone cannot tell, because the WAL can rename or drop the `note` table.
- The path is passed as a `file:` URI, so spaces, `#` and `%` in folder names are encoded.
- Three new reasons with messages in both bundles: `settings.dbPath.invalid.notSqlite`, `settings.dbPath.invalid.notJNote` and `settings.dbPath.invalid.hasJournal`. A refusal in `requireUsable` uses the existing `UnusableTarget` notification.
- This is an early refusal before asking plus a re-check right before opening. It is not a guarantee: a file replaced between the re-check and the open can still be migrated.

## Reasons kept from the removed code comments
- The check is called from the plan and again right before the switch, and cannot prevent a change between the two.
- A `-wal` that cannot be copied because of its size means the content is unknown, so the file is not let through.
- The two files are copied separately, so a write or checkpoint in between yields copies from different moments; the copy is repeated.
- A symlink and a case-insensitive file system must see the same file as the same, so only the existing part of a path is resolved to its real path.
- The state is re-read after the check and the switcher's save happens before the old DB is closed, so a close failure still keeps the new path after a restart.

## Alternatives considered
- Only require a `note` table and a plausible `user_version`: a foreign database with a `note` table passes, then fails later on missing columns, or at version 1 or 2 later migrations write into the foreign file (review of #80).
- Require only the current schema's `note` columns: an old valid JNote database at version 1 or 2 would be refused, although the migrations upgrade it.
- Make `Migrations.run` verify the schema and refuse: the refusal would come after the confirmation dialog and in the open path, not before asking.
- Copy `-journal` into the temp copy and read the recovered copy: SQLite would need to replay a journal of unknown origin, and the copy can mix moments like the WAL case.
- Accept a leftover `-journal` as a documented best-effort limit: a crashed writer's file is the case where the user most needs the check.
- Treat a database that has only views as empty: the migrations would add the JNote tables to another application's file. Any schema object makes it non-empty (review of #80).
- Reject only when tables exist, `user_version = 0` and there is no `note` table (the issue's first wording): many applications set `user_version`, and the migrations would treat such a file as a JNote database at that version and skip the earlier steps. The same holds for an empty database with `user_version > 0`. Requiring the `note` table, or version 0 when there are no tables, costs nothing for real JNote files.
- Read a WAL database with a plain `mode=ro` connection: it takes part in SQLite locking and can create a missing `-shm` next to another application's file.
- Copy any size when a `-wal` exists: a large foreign database would be copied in full on every Apply.
- Treat an inconclusive large file as usable and leave it to `requireUsable`: `requireUsable` runs the same check, so nothing would stop the migrations.
- Read only the main file of a large database with a `-wal`: the WAL can rename the `note` table, so the main file can say "JNote" for another application's database (found in review).
- Allow only JNote databases and refuse empty files: an empty file is a natural way to start a new database at a chosen path.
- Run the check in the switcher after the confirmation: the user would confirm a switch that then fails.
- Parse page 1 of the file by hand instead of opening SQLite: does not see content that is still in a `-wal` file, and duplicates the file format.

## Consequences
- Pros
  - A path change refuses another application's database before asking, and checks again right before opening it.
  - Non-database files are refused with a specific message before the confirmation dialog.
  - The check takes no lock and leaves no file behind next to the target.
- Cons and what we accept
  - For a database with a `-wal` file, up to 64 MiB is copied into a temp folder in the background task.
  - A JNote database over 64 MiB that another process has open (so it has a `-wal`) is refused. Closing the other IDE first makes it pass. JNote databases are expected to stay far below that size.
  - The size limit is checked before each copy attempt. A file that grows during a copy can make that one copy exceed the limit; the before/after comparison then discards it and the next attempt checks the limit again.
  - The before/after comparison only catches changes it can observe. A same-size write within the file system's timestamp resolution, a `-wal` created right after the check saw none, or a rollback-journal writer changing the main file during an immutable read can still give an inconsistent view. The check is a best-effort early refusal, like the re-check.
  - A database with a leftover non-empty `-journal` is refused until the application that owns it has recovered it.
  - The check opens an in-memory database and runs the migrations on every call that reaches the schema comparison; this is small next to the file read.
  - A file whose header is valid but whose content is damaged fails the plan with the generic failure notification.
  - The window between the re-check and the open remains.
- Follow-up work
  - None.
