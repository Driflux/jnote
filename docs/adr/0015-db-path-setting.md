# 0015. DB path setting and switching behavior

- Status: Proposed
- Date: 2026-11-26
- Issue: #15
- PR: #37, #38

## Context
The DB lives at `~/.jnote/jnote.db` by default and the user must be able to move it (intent.md sections 4 and 7), for example to a synced folder or a temporary file while developing. The change must not need a restart, must never lose notes, and must obey the performance principles (intent.md section 2): no startup work, no file or DB I/O on the EDT, no new dependency. The settings page has exactly one item.

## Decision
- **Storage.** `DbPathSettings` is an application `@Service` and `PersistentStateComponent` (`@State`, file `jnote.xml`), created on first use with no `plugin.xml` entry. The storage is declared with `RoamingType.DISABLED` because the setting holds a path that differs per PC, so Settings Sync must not copy it. `State.dbPath` is a string; empty means the built-in default, so a default stored path follows any future change of the default. `DatabasePaths.default()` resolves the stored value (a leading `~` is expanded).
- **Settings page.** An `applicationConfigurable` under Tools with the display name from the bundle. The platform creates the `JNoteConfigurable` class only when the settings dialog opens, so it costs nothing at startup. It builds one field (Kotlin UI DSL) and delegates every decision to the logic below.
- **Decision as a pure function.** `DbPathChange.plan(current, requested)` reads the file system and returns a sealed `Plan`: `NoChange`, `CopyThenSwitch(target)` (target missing), `SwitchToExisting(target)` (target is a writable file), or `Invalid(reason)` (relative path, target is a directory, not writable, parent missing and not creatable). Two spellings of one file (`..`, symlinked folder, case-insensitive file system) resolve to the same canonical path and give `NoChange`. Because it reads the disk, it never runs on the EDT: the field only checks the form of the input while typing (an absolute, well-formed path), and the full plan runs in the background after Apply (second PR).
- **Copy.** (second PR) If the current DB is open, the copy runs as a job on the DB thread: `PRAGMA wal_checkpoint(TRUNCATE)`, then a copy of the main file. Running on that thread means no other write can interleave, so the copy is consistent, and after a TRUNCATE checkpoint the main file alone holds all committed data. If the DB was never opened this session, the file is copied if it exists (a leftover non-empty `-wal` from a crash is checkpointed first through a short-lived connection) and nothing is created otherwise. The copy streams into a temp file next to the target in 1 MiB chunks, checking for cancellation between chunks, then moves it into place atomically. A cancelled or failed copy deletes the temp file, so the target never holds a partial file and the old DB stays active.
- **Switch.** (second PR) `DatabaseService.switchTo(path)` swaps in the new path, closes the old `Database` only if one was created, and leaves the new one to be created lazily on next use. Existing target: a confirmation dialog first, then switch with no merge. The stored setting is updated only after the switch.
- **Threading and errors.** (second PR) Apply only starts a cancellable `Task.Backgroundable` that computes the plan. When the plan needs it, the confirmation dialog appears on the EDT after that task, and the copy and switch run in a second cancellable background task. Only one change runs at a time: an Apply while another change is still running is refused with a notification. Failures raise a notification from a `JNote` notification group with the reason and a fix; nothing is swallowed. Cancelling is not an error.

## Alternatives considered
- Apply the new path only on next IDE start: simplest, but the issue requires no restart, and a half-applied state is confusing.
- Merge when the target already has a DB: needs conflict rules for uid collisions; the agreed design says no merging.
- Move instead of copy: leaves the user with no fallback if the new location is bad. The old file stays in place.
- `VACUUM INTO` for the copy: rewrites the whole DB and needs an open connection; checkpoint plus file copy works for the never-opened case too.
- Plain `Files.copy` to the target: a cancel or crash leaves a partial DB at the final path. Temp file plus atomic move avoids that.
- Copy on the calling thread with a separate connection: other writes could land mid-copy and give an inconsistent file.

## Consequences
- Pros
  - No startup cost; the page and the settings service exist only when used.
  - Malformed input is caught while typing; the file-system rules are checked in the background after Apply.
  - Old data is never deleted; failure leaves the old DB active.
- Cons and what we accept
  - Writes made between the end of the copy and the switch land only in the old file (a short window).
  - The DB thread is blocked for the duration of the copy of an open DB.
  - Settings stay marked modified until the background task finishes.
- Follow-up work
  - None planned; a "delete the old file" action is out of scope.
