# 0060. Resolve the DB path on the DB thread

- Status: Proposed
- Date: 2026-11-27
- Issue: #60
- PR: (this PR)

## Context
Since the DB path became a setting (ADR 0015), `DatabasePaths.default()` reads the `DbPathSettings` application service. `DatabaseService.database` built its `Database` with that path, so the path was resolved on the thread of the first caller. When that caller is the EDT (for example the Tool Window or a delete action), the settings service is created and `jnote.xml` is read on the EDT. This breaks the rule that file I/O never runs on the EDT (intent.md section 2).

## Decision
- `Database` can take a path supplier instead of a path. The supplier is called inside `open()`, which runs on the `JNote-DB` thread on the first `submit`. The `Database(path)` constructor stays and wraps the path in a supplier.
- `DatabaseService.database` binds each `Database` to the path the service holds when the object is created. When the path is already known it is a constant. When it is not known yet (first use, nothing switched), the `Database` gets a supplier that reads the setting on the DB thread. Getting the database is now free of I/O.
- A `Database` never follows a later switch. A job queued on a `Database` that a switch replaced before its first open still opens the file that `Database` was made for. Otherwise a job meant for the old file (for example a delete by numeric id picked in the UI) could run against the new file. `SwitchToExisting` swaps with `replace()` without going through the old DB thread, so this ordering matters.
- The setting is read outside the service lock, then stored under the lock only if no path was set in the meantime. Holding the lock during the read would make an EDT caller of `database` or `dispose()` wait for the file read.
- `currentPath()` and `current()` still resolve the path on their calling thread when it is not known yet. Their callers (`DbPathChanger`, `DbPathSwitcher`, gutter loading) run in background tasks or pool threads. The gutter's EDT callback (`onNotesChanged` to `notesFor` to `current()`) only runs for files already in its cache, and filling the cache resolves the path on a pool thread first.
- Out of scope: the settings page (`JNoteConfigurable`) reads the setting on the EDT when it opens, like any platform `Configurable` reading its `PersistentStateComponent`. It is user-initiated and small, so it stays, and it is noted as a candidate for a separate issue.

## Alternatives considered
- Resolve the path on a pool thread before creating the `Database`: `database` would have to become asynchronous, and every caller would change. Rejected for the size of the change.
- Read the setting eagerly at startup: breaks the no-startup-work rule.
- Give every `Database` a supplier that reads the service's current path when it opens: simpler, but a job queued before a switch would run against the new file. Rejected after review.

## Consequences
- Pros
  - No settings load or other file I/O on the caller thread when the database is first used.
  - No change for callers of `DatabaseService.database`.
- Cons and what we accept
  - A failure to read the setting now surfaces as a failed future from the first job instead of an exception from the getter.
  - `DatabaseService` has an `internal` test seam (`defaultPath`) so tests can observe on which thread the setting is read.
- Follow-up work
  - The settings page still reads the setting on the EDT (see above).
