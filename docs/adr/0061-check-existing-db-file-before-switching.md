# 0061. Check an existing DB file before switching to it

- Status: Proposed
- Date: 2026-11-27
- Issue: #61
- PR: (this PR)

## Context
When the new DB path points to an existing file, `DbPathChange.plan` returned `SwitchToExisting` for any writable regular file (ADR 0015). After the confirmation dialog, `DbPathSwitcher.requireUsable` opens the file through `Database`, which switches it to WAL and runs the migrations. For another application's SQLite database this creates the JNote tables inside that file, and it does so even when a later step fails. Files that are not SQLite at all were only rejected after the user had confirmed the switch.

## Decision
- `DbPathChange.existingFileProblem` inspects an existing target without writing to it. `plan` calls it in the background plan task before returning `SwitchToExisting`, so a bad file is refused before the confirmation dialog. `DbPathSwitcher.requireUsable` calls it again right before its open-and-migrate probe, because the file can change while the dialog is open.
- The file is resolved to its real path first, so a symlink finds the `-wal` next to the real file.
- A 0-byte file is allowed: SQLite opens it as an empty database.
- Otherwise the first 16 bytes must be `SQLite format 3\0`; if not, `Invalid(NOT_SQLITE)`.
- Then the table names and `user_version` are read. Allowed: a database with no tables and `user_version = 0`, or a database with a `note` table. Anything else is `Invalid(NOT_JNOTE)`.
- How it reads without touching the file:
  - No `-wal` file: the main file holds all of its content, so the connection uses `mode=ro&immutable=1`. No lock is taken and no `-wal` or `-shm` file is created. Measured: a plain `mode=ro` connection on a WAL-mode file without sidecars created `-wal` and `-shm` and left them behind.
  - A `-wal` file exists (the DB is in use, or a writer crashed): the main file and its `-wal` are copied into a temp folder and the copy is read, so tables that exist only in the WAL are seen. Size and modification time of both files are compared before and after the copy; if either changed (a write or checkpoint happened between the two copies), the copy is retried, up to 3 times, and then the plan fails with the generic failure notification. The temp folder is deleted in `finally`; a failed delete is logged.
  - A `-wal` file exists and the main file plus `-wal` exceed 64 MiB: nothing is copied and the file is refused (`NOT_JNOTE`). The main file alone cannot tell, because the WAL can rename or drop the `note` table.
- The path is passed as a `file:` URI, so spaces, `#` and `%` in folder names are encoded.
- Two new reasons with messages in both bundles: `settings.dbPath.invalid.notSqlite` and `settings.dbPath.invalid.notJNote`. A refusal in `requireUsable` uses the existing `UnusableTarget` notification.
- This is an early refusal before asking plus a re-check right before opening. It is not a guarantee: a file replaced between the re-check and the open can still be migrated.

## Alternatives considered
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
  - The size limit is checked before the copy; a file that grows during the copy is caught by the before/after comparison and retried.
  - A file whose header is valid but whose content is damaged fails the plan with the generic failure notification.
  - The window between the re-check and the open remains.
- Follow-up work
  - None.
