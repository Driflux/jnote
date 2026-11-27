# 0066. Create a note and its tags in one transaction

- Status: Proposed
- Date: 2026-11-27
- Issue: #66
- PR: (draft, number after push)

## Context
`NoteSaver.save` and `saveFree` created the note with `NoteRepository.create` and then set its tags with `TagRepository.replaceTags`. Each call was its own `database.submit` and its own transaction. When the tag step failed (a database error, a tag name the dialog let through, or a write lock held by another IDE process past `busy_timeout`), the note was already committed but the returned future failed. The user saw "saving failed", `NotesChanged` was not published, and saving again created a duplicate note. A test that makes every `note_tag` insert fail showed the note left behind.

## Decision
- `NoteRepository.create(draft, tagNames = emptyList())` inserts the note and sets its tags in one transaction on the DB thread. If any step fails, the transaction rolls back and no note or new tag remains. A comma in a tag name fails before anything is written, as `replaceTags` already did.
- The tag SQL moves out of `TagRepository.replaceTags` into two internal functions in `TagRepository.kt`: `cleanTagNames` (trim, drop blanks and duplicates, reject commas) and `setNoteTags(connection, noteId, names)`, which runs inside the caller's transaction. `replaceTags` and `create` both use them, so the rules for tag names stay in one place.
- `NoteSaver.save` and `saveFree` pass `draft.tagNames()` to `create` and no longer call `replaceTags`. The edit path (`NoteSaver.update`) still updates the note and then replaces its tags in two steps. A failure there leaves the edit saved without the tag change, and saving again is idempotent, so it creates no duplicate. #64 covers the edit path.

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
- Follow-up work
  - The edit path's two transactions (see Decision) stay until a later change needs them merged.
