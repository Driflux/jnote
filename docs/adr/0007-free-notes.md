# 0007. Free notes (global notes)

- Status: Proposed
- Date: 2026-11-26
- Issue: #7
- PR: (to be filled)

## Context
Some notes are not about code: a query, a command, a snippet worth keeping. intent.md sections 4 and 7 define them as `kind = FREE` notes with `project = NULL` and no anchor, shown in every project, in a monospace font, with an all / code / free filter. The schema, `NewNote.free`, and the list query (current project plus global notes) already exist, and the detail area already uses a monospace body for FREE notes (#6). What is missing is a way to create one and the filter.

Constraints from intent.md section 2 still apply: no startup work, no DB access on the EDT, no new dependency.

## Decision
This ADR covers both PRs of issue 7.

### Create a free note (first PR)
- **Button.** A "New Note" action (`JNote.NewFreeNote`) is set as a Tool Window title action by the factory, so it sits in the window's toolbar. It is registered in `plugin.xml` without a menu group, and it costs nothing until the window is opened. It uses the platform "add" icon; the tooltip carries the text. `JNotePanel` is not touched.
- **Dialog.** The add-note dialog is reused with no anchor: title, note text, and the collapsible AI source group. Only the dialog title differs.
- **Save.** `NoteDraft.toFreeNote()` trims the text the same way as for code notes and rejects a blank title. `NoteSaver.saveFree` creates the note through `NewNote.free` off the EDT, without resolving a project key, then publishes `NotesChanged` once on the UI thread. Open panels reload and show it.
- **Result.** Success shows the same "Note saved" notification as a code note. Failure (including a closed database after a path switch) shows the error notification with the escaped reason. Nothing is swallowed, and a project closed meanwhile gets no notification.
- **Visibility.** The list query already returns FREE notes for any project key, so the note appears in every project.

### Filter (second PR)
- **Pure model.** `NoteFilter` (ALL, CODE, FREE) decides which notes it accepts and carries the bundle keys for its label and empty-state title. `ToolWindowState` keeps all loaded rows plus the filter and derives `visibleNotes` and `showEmptyState`, so the rules are tested without Swing.
- **Panel.** A combo box next to the "All projects" box. Changing it re-renders from the rows already loaded; it does not read the database again. The filter survives reloads and project toggles, defaults to All, and is not persisted across IDE restarts.
- **Empty state.** When the filter hides everything, the list shows "No code notes" or "No free notes". The Free hint points to the New Note button; the others keep the add-note hint with the real shortcut. A filtered-out selected note clears the detail area.

## Alternatives considered
- A button inside the panel header: puts more code in `JNotePanel`, which several issues touch. A title action needs no panel change. Rejected.
- A separate dialog class for free notes: duplicates the fields and validation. Rejected.
- Resolve the project key and store the project on free notes: contradicts intent.md ("global notes"). Rejected.

## Consequences
- Pros
  - No new schema, no new dependency, no startup work.
  - The pure mapping (`toFreeNote`) and the save path are unit-tested without the dialog.
- Cons and what we accept
  - The toolbar button is icon only, with the text as tooltip, because title actions render as icons.
  - The dialog and the notification are not verified in a running IDE.
- Follow-up work
  - The tag filter (#10) and search (#11) can extend `ToolWindowState` the same way.
