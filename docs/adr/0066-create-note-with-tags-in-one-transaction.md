# 0066. Create a note and its tags in one transaction

- Status: Proposed
- Date: 2026-11-27
- Issue: #66
- PR: #71

## Context
`NoteSaver.save` and `saveFree` created the note with `NoteRepository.create` and then set its tags with `TagRepository.replaceTags`. Each call was its own `database.submit` and its own transaction. When the tag step failed (a database error, a tag name the dialog let through, or a write lock held by another IDE process past `busy_timeout`), the note was already committed but the returned future failed. The user saw "saving failed", `NotesChanged` was not published, and saving again created a duplicate note. A test that makes every `note_tag` insert fail showed the note left behind.

## Decision
- `NoteRepository.create(draft, tagNames = emptyList())` inserts the note and sets its tags in one transaction on the DB thread. If any step fails, the transaction rolls back and no note or new tag remains. A comma in a tag name fails before anything is written, as `replaceTags` already did.
- The tag SQL moves out of `TagRepository.replaceTags` into two internal functions in `TagRepository.kt`: `cleanTagNames` (trim, drop blanks and duplicates, reject commas) and `setNoteTags(connection, noteId, names)`, which runs inside the caller's transaction. `replaceTags` and `create` both use them, so the rules for tag names stay in one place.
- `NoteSaver.save` and `saveFree` pass `draft.tagNames()` to `create` and no longer call `replaceTags`. The edit path (`NoteSaver.update`) still updates the note and then replaces its tags in two steps. A failure there leaves the edit saved without the tag change, and saving again is idempotent, so it creates no duplicate. #64 covers the edit path.

- `NoteRepository.create` reads the new row inside the transaction, before `commit()`. Reading after the commit lets another IDE process delete the row or reuse its id in between.
- `NoteSaver.save` and `saveFree` publish `NotesChanged` on the UI thread, not the DB thread, because a subscriber would block waiting on the DB. Their chains start on the background executor and use `thenComposeAsync`, because `thenCompose` can run on the calling thread when the previous stage is already done. Validation errors and `repository()` evaluation therefore stay off the EDT and surface through the future.
- `NoteSaver.update` builds its chain inside the future so validation and repository errors surface through it, and it always replaces tags, so an empty tag list clears them. APPROX notes are confirmed by an edit and saved with status OK and a fresh snapshot; if the file cannot be read, only the edit is saved and the note stays APPROX.

## Alternatives considered
- **Delete the new note when the tag step fails (compensation).** It needs a third DB call that can also fail, and between the two another IDE process or the Tool Window can see the note without tags. Rejected.
- **Publish `NotesChanged` and report "saved without tags" when only the tag step fails** (the issue's minimum fix). It avoids the duplicate only if the user reads the message, and it keeps a note the user thinks was not saved. Rejected in favor of the atomic save.
- **A new `createWithTags` method next to `create`.** Two methods that both create a note would differ only by the tag list. A defaulted parameter keeps one creation path. Rejected.

## Consequences
- Pros
  - A failed save leaves nothing, so retrying creates exactly one note.
  - One `database.submit` instead of two per save with tags.
- Cons and what we accept
  - `create` now always runs in an explicit transaction, even with no tags. It adds a `BEGIN`/`COMMIT` pair around a single insert on the DB thread, which does not change the number of fsyncs in WAL mode.
  - `NoteRepository` now depends on tag SQL that lives in `TagRepository.kt`.
- Notes carried over from removed source comments (all comments were removed from the files this PR changes)
  - `NoteRepository` access goes through `Database.submit`, so calling from the EDT does not block. SQL errors such as constraint violations surface as an exceptionally completed future.
  - Bulk status updates by the relocation code keep `updated_at` so the "recently modified" order does not change; rows with equal values are skipped so the changed count is exact and the FTS triggers do not run.
  - Restore skips tags deleted in the meantime so a foreign key error does not fail the whole restore. Delete reads the linked tag ids and deletes in one transaction using `BEGIN IMMEDIATE`, so undo restores exactly what was deleted and the lock is not lost when upgrading from a read.
  - `list` orders by most recent update, then larger id; with a project it also returns global FREE notes, and with a null project only those. `listByFile` orders by start line then id and uses the (project, rel_path) index; the gutter calls it once per file open.
  - `cleanTagNames` and `setNoteTags` leave the transaction to the caller; `setNoteTags` makes the note's tags exactly equal to the given names and returns them sorted by name.
  - Tests: the saver test executor finishes work before `execute` returns so a stage chained later reliably shows whether it ran on the caller or the background thread; note paths are relative to `basePath`, so tests create real files under it with unique names to keep VFS caches apart; rows that the model forbids are inserted by bypass to check that the DB constraint is the last defense, and after a failure the connection returns to auto commit so the next write works.
- Follow-up work
  - The edit path's two transactions (see Decision) stay until a later change needs them merged.
