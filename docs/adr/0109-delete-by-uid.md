# 0109. Delete identifies notes by uid

- Status: Proposed
- Date: 2026-11-27
- Issue: #109
- PR: linked from issue #109

## Context
The Tool Window's Delete button and Delete key deleted by numeric note id. Between a DB path switch and the next list reload, the selected row still holds an id from the old database. If the new database has a note with the same id, that note was deleted instead. SQLite also reuses the highest id after a delete (the `note` table has no `AUTOINCREMENT`), so the same can happen across IDE processes sharing one database.

## Decision
- `NoteRepository.deleteReturningTagIds` and `NoteDeletion.delete` take uids. Inside the `BEGIN IMMEDIATE` transaction each uid is looked up, and only the row found by that uid is deleted (by its current id). A uid with no row is skipped.
- `JNotePanel` passes `Note.uid` for the detail's delete button and `NoteSummary.uid` for a multi-row Delete (ADR 0121).
- When fewer notes were deleted than requested, the panel shows a WARNING, "Note not found" / "1 selected note was not found in the current database. The list was refreshed." (`toolWindow.error.deleteStale.*`, English and Korean, with the count of notes not found). Whenever the delete returns without a database error, `NotesChanged` is published, so the list reloads, even when nothing was deleted. Before, a delete of a missing note refreshed the list silently.
- Undo is unchanged: `NoteDeletion` keeps the database it deleted from, and restores the deleted rows with their original id and uid.

## Alternatives considered
- **Delete by id with `AND uid = ?`.** Same safety, but a note that only changed id (it cannot today) would be missed, and the caller has to carry both values. The uid is the stable identity used by import and copy already. Rejected.
- **Clear the selection on a DB switch.** Closes one path but not the id reuse across processes. Rejected.
- **Add `AUTOINCREMENT` to stop id reuse.** A schema migration, and it does not help the DB switch. Rejected.

## Consequences
- Pros
  - A stale row can no longer delete another note, whatever the database or process.
- Cons and what we accept
  - The lookup is by the `uid` unique index instead of the primary key, one indexed read per note.
  - Deleting a note that is already gone now shows a warning instead of a silent refresh.
- Tests
  - `NoteDeletionTest`: a stale uid whose id was reused deletes nothing and keeps the new note; a selection from the database before a switch deletes nothing in the new database; existing cases use uids.
  - `NoteDetailPanelTest.testDeletingAStaleNoteWhoseIdWasReusedReportsItAndKeepsTheOtherNote`: the panel reports the stale delete, keeps the other note, and refreshes.
