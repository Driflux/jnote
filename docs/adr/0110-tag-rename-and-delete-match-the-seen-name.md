# 0110. Tag rename and delete match the id and the name the user saw

- Status: Accepted
- Date: 2026-11-27
- Issue: #110, #111
- PR: (this PR)

## Context
The Tool Window reads the tag list, and a later rename or delete runs `UPDATE tag ... WHERE id = ?` or `DELETE FROM tag WHERE id = ?` with the id from that list. `tag.id` is an `INTEGER PRIMARY KEY` without `AUTOINCREMENT`, so SQLite hands out `max(id) + 1` and reuses the id of a deleted tag that had the largest id. If another IDE process sharing `jnote.db` deletes the tag and creates a new one between the list read and the operation, the rename or delete hits the new, different tag (ADR 0059 recorded this gap; #111 reported the same hole).

## Decision
- The key is the pair the user saw: id plus name. `TagRepository.rename(seen: Tag, newName)` and `delete(seen: Tag)` take the `Tag` from the list instead of a bare id.
- Both are a compare-and-set on that pair: `UPDATE tag SET name = ? WHERE id = ? AND name = ?` and `DELETE FROM tag WHERE id = ? AND name = ?`. One statement decides, so there is no window between the check and the write.
- A miss is a stale tag. `TagRenameResult.NotFound` becomes `TagRenameResult.Stale` ("the tag you saw is gone or no longer has that name"); `delete` keeps returning `false`. The Tool Window already refreshes on both (`publishChanged`), so the user sees the current tags and can act again. No new notification is added.
- A tag renamed by another process (same id, different name) also counts as stale. The user acted on a name that no longer exists, so applying their rename or delete on top of the other process's rename would overwrite a change they never saw.
- The rename keeps its pre-check (now by id and name) so a stale tag reports `Stale` before a name `Conflict`, as the old id check did for `NotFound`.

## Alternatives considered
- `AUTOINCREMENT` on `tag.id` through a migration: ids would never be reused, but SQLite cannot add it with `ALTER TABLE`; the table must be rebuilt, which touches `note_tag` foreign keys and the `v_notes_export` view, and every user pays a migration and a `.bak` copy. It also would not catch a rename by another process.
- A stable `uid` column on `tag` with a migration: closes the gap but needs a schema change, a backfill, and new API shapes for a personal dictionary of a few dozen tags. Id plus name gives the same guarantee for the user-visible cases without a migration.
- Wrapping the check and the write in `BEGIN IMMEDIATE`: still keyed by id only, so it does not detect reuse.

## Consequences
- Pros: rename and delete never touch a tag the user did not see. No schema change and no migration.
- Cons and what we accept: if another process deletes a tag and creates a new one with the same id and the same name, the operation applies to the new tag. For the user the two are indistinguishable (same name), so this is the intended outcome.
- Follow-up work: none.
