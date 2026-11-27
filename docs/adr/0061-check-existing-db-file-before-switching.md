# 0061. Check an existing DB file before switching to it

- Status: Proposed
- Date: 2026-11-27
- Issue: #61
- PR: (this PR)

## Context
When the new DB path points to an existing file, `DbPathChange.plan` returned `SwitchToExisting` for any writable regular file (ADR 0015). After the confirmation dialog, `DbPathSwitcher.requireUsable` opens the file through `Database`, which switches it to WAL and runs the migrations. For another application's SQLite database this creates the JNote tables inside that file, and it does so even when a later step fails. Files that are not SQLite at all were only rejected after the user had confirmed the switch.

## Decision
- `plan` inspects an existing target before returning `SwitchToExisting`. It runs in the background plan task, before the confirmation dialog, so a bad file is refused without asking.
- A 0-byte file is allowed: SQLite opens it as an empty database.
- Otherwise the first 16 bytes must be `SQLite format 3\0`; if not, `Invalid(NOT_SQLITE)`.
- The table names are read from `sqlite_master` through a read-only connection. A database with no tables, or with a `note` table, is allowed. Any other database is `Invalid(NOT_JNOTE)`.
- The check never writes. With no `-wal` file next to the target, the main file holds all of its content, so the connection uses `mode=ro&immutable=1`: no lock and no `-wal` or `-shm` file is created. Measured with a plain `mode=ro` connection: on a WAL-mode file without sidecars it created `-wal` and `-shm` and left them behind. With a `-wal` file present (the DB is in use or was not closed cleanly), the connection uses `mode=ro` so that it reads the WAL content too. The path is passed as a `file:` URI, so spaces, `#`, `%` and `?` in folder names are encoded.
- Two new reasons with messages in both bundles: `settings.dbPath.invalid.notSqlite` and `settings.dbPath.invalid.notJNote`.
- The check is an early refusal before the user is asked, not a guarantee. The file can change between the plan and the switch. `requireUsable` keeps its open-and-migrate probe and its `UnusableTarget` notification for that case and for a database from a newer JNote version.

## Alternatives considered
- Accept a database whose `user_version` is greater than 0 even without a `note` table (the issue's first wording): many applications set `user_version`, and the migrations would then treat that file as a JNote database at that version. Requiring the `note` table is stricter and costs nothing for real JNote files, which always have it.
- Allow only JNote databases and refuse empty files: an empty file is a natural way to start a new database at a chosen path.
- Run the check in the switcher after the confirmation: the user would confirm a switch that then fails.
- Parse page 1 of the file by hand instead of opening SQLite: does not see content that is still in a `-wal` file, and duplicates the file format.

## Consequences
- Pros
  - Another application's database is never modified by a path change.
  - Non-database files are refused with a specific message before the confirmation dialog.
- Cons and what we accept
  - A short read-only connection is opened in the background plan task. Only for an existing target.
  - With a `-wal` file present, the read-only connection may create a missing `-shm` file. This only happens for a database that another connection is using or that crashed.
  - A file whose header is valid but whose content is damaged fails the plan with the generic failure notification.
- Follow-up work
  - None.
