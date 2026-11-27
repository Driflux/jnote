# 0121. The Delete key deletes every selected note with one undo

- Status: Proposed
- Date: 2026-11-27
- Issue: #121
- PR: linked from issue #121

## Context
The note list allows multiple selection since #19 (copy as Markdown/JSON). The Delete key clicked the detail panel's delete button, which acts on the note the detail shows, the first selected one. The user saw several rows selected and only one disappeared. The issue left the behavior as a product decision: delete the whole selection with one undo notification, or ignore Delete when more than one row is selected.

## Decision
- With two or more rows selected, Delete deletes all of them in one transaction and shows one notification, "{0} notes deleted" (new bundle key `toolWindow.notification.deletedMany`, English and Korean), whose Undo restores all of them in one transaction. While the selection is still being dragged (`valueIsAdjusting`), Delete does nothing, with one or several rows selected.
- With one row selected, Delete keeps its old path: it clicks the detail's delete button only when the detail shows the selected note.
- The detail's delete button still deletes the one note it shows.
- `NoteDeletion.delete` and `undo` take and return lists. `NoteRepository.deleteReturningTagIds` takes a list of ids, and the new `restoreAll` restores a list in one transaction and reads the restored rows before the commit, so a row deleted by another process right after the commit cannot turn a successful undo into an error; `restore` delegates to it.

## Alternatives considered
- **Ignore Delete with more than one row selected.** Simpler, but the key silently does nothing on a visible selection, which reads as broken. Delete is immediate and undoable (intent.md section 4), so acting on the whole selection is safe. Rejected.
- **One notification per note.** A selection of twenty notes would stack twenty notifications, and undoing them one by one is tedious. Rejected.
- **Restore the notes one by one on undo.** If one restore failed (for example its uid was reused meanwhile), the undo would end half applied. One transaction restores all or none. Rejected.

## Consequences
- Pros
  - The key acts on what the user sees selected, and one Undo brings everything back.
- Cons and what we accept
  - A note that another process deleted meanwhile is skipped; the notification counts only the notes this delete removed.
  - If one note of the selection cannot be restored, the whole undo fails and reports the error, and none come back.
  - The failure notifications for a delete or undo of several notes keep their single-note titles ("Cannot delete the note"). The content names the cause, and none of the notes changed.
- Tests
  - `NoteDeletionTest`: several notes are deleted together (a missing id is skipped) and undo restores all with their tags; an undo where one note cannot be restored rolls back the others. Existing cases use the list API.
  - `ToolWindowKeyboardTest`: Delete with two rows selected deletes both, shows one "2 notes deleted" notification, and its Undo restores both (failed before the change); Delete while the selection is being dragged does nothing, for the shown row alone and for several rows.
