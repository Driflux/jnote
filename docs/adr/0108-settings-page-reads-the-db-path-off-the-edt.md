# 0108. The settings page reads the DB path setting off the EDT

- Status: Accepted
- Date: 2026-11-27
- Issue: #108
- PR: #127

## Context
ADR 0060 moved the DB path lookup in `DatabaseService` onto the DB thread, so the first use of the database no longer loads `DbPathSettings` (`jnote.xml`) on the EDT. The settings page (`JNoteConfigurable`) still bound its field to `DatabasePaths.default()`, which the platform calls on the EDT from `reset()` and `isModified()`. When the settings page is the first thing to touch `DbPathSettings`, the service is created there and `jnote.xml` is read from disk on the EDT. ADR 0060 kept this as an accepted exception and left the decision to this issue.

## Decision
- Remove the exception. The file read happens only when the `DbPathSettings` service is created; after that its state is in memory. `createPanel` creates the service on the application pool and only then lets the field bind to the setting.
- Until the service is loaded the field is disabled and empty, the getter returns `""` and the setter does nothing. So `isModified` is `false` before the background read completes, and an Apply in that window changes nothing.
- When the load completes, an `invokeLater` with `ModalityState.any()` (the settings dialog is modal, and the callback only touches this page's Swing components) sets the loaded flag, enables the field, and calls `reset()` to fill it from the now in-memory setting. The field is enabled before `reset()`, so a stored path that fails to parse does not leave the field disabled. The binding getter still parses the stored value, so such a path keeps failing in `isModified` as it did before this change. After that the getter reads `DatabasePaths.default()` live as before, so `isModified` keeps its old behavior: it turns `false` after Apply only when the switch has actually stored the new path.
- Each `createPanel` clears the loaded flag and takes a new generation number; `disposeUIResources` bumps the generation. The EDT step runs only if its generation is still current, so a page closed before the load completes is not reset afterwards, and a panel recreated on the same `Configurable` waits for its own load. Both the check and the bump run on the EDT.
- `loadSettings` is an `internal` test seam so tests can hold the load and observe the thread it runs on.

## Alternatives considered
- Keep the exception and document it: the platform reads most `PersistentStateComponent`s on the EDT for its own configurables, and the file is tiny. Rejected because CLAUDE.md and intent.md section 2 keep file I/O off the EDT without exceptions, and the fix is local to one class.
- Cache the path string read in the background and compare against it: `isModified` would turn `false` right after Apply even if the switch later fails, and the field would no longer reflect the stored setting. Rejected.

## Consequences
- Pros: opening the settings page never reads `jnote.xml` on the EDT. Behavior after the load is unchanged.
- Cons and what we accept: on the very first open the field is disabled and empty for the moment the background load takes. If the service is already loaded (any earlier JNote use in the session), the field is filled on the next EDT turn.
- Cons and what we accept: a failure while creating the `DbPathSettings` service leaves the field disabled. The platform logs state-loading problems and falls back to the default state, so this path is not expected to occur; no extra error UI is added for it.
- Follow-up work: none. ADR 0060's follow-up item is closed by this ADR.
