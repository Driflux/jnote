# 0060. Resolve the DB path on the DB thread

- Status: Proposed
- Date: 2026-11-27
- Issue: #60
- PR: (this PR)

## Context
Since the DB path became a setting (ADR 0015), `DatabasePaths.default()` reads the `DbPathSettings` application service. `DatabaseService.database` built its `Database` with that path, so the path was resolved on the thread of the first caller. When that caller is the EDT (for example the Tool Window or a delete action), the settings service is created and `jnote.xml` is read on the EDT. This breaks the rule that file I/O never runs on the EDT (intent.md section 2).

## Decision
- `Database` can take a path supplier instead of a path. The supplier is called inside `open()`, which runs on the `JNote-DB` thread on the first `submit`. The `Database(path)` constructor stays and wraps the path in a supplier.
- `DatabaseService.database` creates `Database(::currentPath)`. Getting the database is now free of I/O; the settings service is first touched on the DB thread.
- The path of a `Database` is fixed when its connection first opens, not when the object is created. If a switch replaces a `Database` that never opened, a job already queued on it opens the new path and the retired object is closed right after. The job reaches the file that is current, and two connections to one file are safe (WAL, locked migrations from ADR 0045).
- `currentPath()`, `current()` and `replace()` still resolve the path on their calling thread. Their callers (`DbPathChanger`, `DbPathSwitcher`, gutter loading) already run in background tasks or pool threads.
- Out of scope: the settings page (`JNoteConfigurable`) reads the setting on the EDT when it opens, like any platform `Configurable` reading its `PersistentStateComponent`. It is user-initiated and small, so it stays, and it is noted as a candidate for a separate issue.

## Alternatives considered
- Resolve the path on a pool thread before creating the `Database`: `database` would have to become asynchronous, and every caller would change. Rejected for the size of the change.
- Read the setting eagerly at startup: breaks the no-startup-work rule.
- Bind the path when the `Database` object is created and resolve it lazily only for the default: needs a second state ("path not yet known") inside the service for no visible benefit, since a retired `Database` is closed anyway.

## Consequences
- Pros
  - No settings load or other file I/O on the caller thread when the database is first used.
  - No change for callers of `DatabaseService.database`.
- Cons and what we accept
  - A failure to read the setting now surfaces as a failed future from the first job instead of an exception from the getter.
- Follow-up work
  - The settings page still reads the setting on the EDT (see above).
