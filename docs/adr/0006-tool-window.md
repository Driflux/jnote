# 0006. Tool Window layout, list, and delete/undo

- Status: Proposed
- Date: 2026-11-26
- Issue: #6
- PR: (to be filled)

## Context
Users need to see saved notes, inspect one, jump to its code, and delete it (intent.md sections 5 and 7). The plugin must stay invisible to the IDE until used (intent.md section 2): no startup work, no DB or file I/O on the EDT, no new dependency. Delete is immediate with an Undo notification instead of a confirmation dialog (intent.md section 9). Editing needs the note dialog from issue #5 and lands in a later PR of this issue.

## Decision
- **Registration and laziness.** One `toolWindow` extension, id `JNote`, anchor right, with a `ToolWindowFactory`. Registering it costs nothing at startup; the panel (and its first DB read) is created in `createToolWindowContent`, which runs only when the user first opens the window. `doNotActivateOnStart` is set so the IDE never opens it by itself. A platform icon is used until the SVG icons of issue #8.
- **Thin panel, pure logic.** Display decisions live in plain Kotlin objects with no Swing and no message bundle: `NoteRows` (subtitle forms, project label rule, AI badge rule), `NoteDetail` (what the detail area shows, which buttons exist), `ToolWindowState` (list or not-loaded-yet, all-projects flag, empty-state rule; the spinner policy lives in `DelayedSpinner`, so a fast reload never flickers the empty text), and `DelayedSpinner` (the 300 ms policy with an injectable scheduler). Labels that need translation are passed in by the panel, so the pure code is unit-testable without the IDE.
- **Loading.** The project key is resolved on a pooled thread (it reads git config), then `NoteRepository.list(projectKey, allProjects)` runs through `Database.submit`. The result is applied with `invokeLater`. A generation counter drops results of superseded loads. Default shows the current project plus global notes, most recent first.
- **Refresh.** An application-level message bus topic `NotesChanged` (a `fun interface` listener). Writers publish after they add, change, or delete a note; each open panel subscribes with a connection tied to its own disposable and reloads. No watcher, no polling, nothing registered at startup.
- **Go to code.** `relPath` is resolved under the project base directory off the EDT (a path with `..` or an absolute path is rejected), then `OpenFileDescriptor` opens it at `startLine` on the EDT. A missing file shows a warning notification; relocation is a later issue. FREE notes have no target and no button.
- **Delete and undo.** Delete runs at once with no dialog. Before deleting, the note and its tag ids are read; after the delete the panel publishes `NotesChanged` and shows a "Note deleted" notification with an Undo action that expires after 8 seconds. Undo calls `NoteRepository.restore(note, tagIds)`, which re-inserts the row with the same `id`, `uid`, and both timestamps inside one transaction and re-attaches the tags that still exist. It does not change `create` or `update`, and a restore of an id or uid that already exists fails without changing anything. `NotesChanged` is published again after undo.
- **Empty state, toggle, spinner.** An "All projects" check box (default off) above the list. When the list is empty and not loading, the list shows guidance: select code, then use the add action, with the shortcut text taken from a bundle key. A spinner appears only if a load takes longer than 300 ms, so fast loads never flicker.
- **Strings.** Every string goes through `JNoteBundle`, keys prefixed `toolWindow.`, in both bundles.

## Alternatives considered
- Create the Tool Window content in an application or project startup activity: breaks the zero-startup rule. Rejected.
- Refresh by polling the DB or watching the file: wasted work while idle and wrong when the DB path changes. A topic fired by writers costs nothing when nothing changes. Rejected.
- Confirmation dialog on delete: slows a frequent action and was dropped in intent.md section 9; Undo is cheaper and safer. Rejected.
- Soft delete (a `deleted` column) for undo: needs a schema change and a purge policy, and the export view would have to hide deleted rows. Keeping the deleted note in memory for 8 seconds and re-inserting it is enough. Rejected.
- Re-create the note through `create` on undo: would assign a new id, uid, and timestamps, breaking the stable `uid` that AI import relies on (intent.md section 10). Rejected.
- Show the spinner immediately: flickers on the usual few-millisecond load. Rejected.

## Consequences
- Pros
  - Zero cost until the window is opened; no new dependency; no EDT I/O.
  - Display rules and the spinner policy are pure and fully unit-tested; the panel only wires them.
  - Undo preserves identity (`id`, `uid`, timestamps, tags).
- Cons and what we accept
  - The undo window is in memory: if the IDE closes within 8 seconds the deletion stands.
  - Undo re-attaches only tags that still exist.
  - Each open panel reloads the whole list on every `NotesChanged` event; fine for a personal note count.
- Follow-up work
  - Edit (needs the dialog from #5), as a later PR of this issue.
  - SVG icons (#8), tag filter and chips (#10), search (#11), relocation for missing files (#13, #14).
