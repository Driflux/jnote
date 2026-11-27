# 0059. Tag rename returns Conflict on a cross-process UNIQUE race

- Status: Accepted
- Date: 2026-11-27
- Issue: #59
- PR: #69

## Context
`TagRepository.rename` checks for a name clash with a SELECT and then runs `UPDATE tag SET name = ?`. The DB thread is single, but a second IDE process sharing `jnote.db` can insert or rename a tag to the same name between those two statements. The UPDATE then failed with `SQLITE_CONSTRAINT_UNIQUE` and the Tool Window got an exception instead of `TagRenameResult.Conflict`, which ADR 0003 promises for an expected duplicate.

## Decision
- `rename` keeps the pre-check for the common case and catches `SQLiteException` from the UPDATE. If `resultCode` is `SQLITE_CONSTRAINT_UNIQUE` it returns `Conflict`; any other SQL error is rethrown and completes the future exceptionally.
- The regression test reproduces the interleaving deterministically on one connection with a TEMP trigger that inserts `NEW.name` right before the UPDATE. It asserts `Conflict` and an unchanged tag.
- Reasons formerly kept as code comments, now recorded here: a comma is rejected in tag names because it collides with the `group_concat` separator of the `tags` column in `v_notes_export`; `getOrCreate` and `replaceTags` insert with `INSERT OR IGNORE` and then select so two processes creating the same name do not fail on UNIQUE; the other-process race test opens both connections in order first so first-open migration races (ADR 0045) are not mixed with the getOrCreate race. The source code of this repository carries no comments by owner decision.

## Alternatives considered
- Wrap the existence check, the clash check, and the UPDATE in `BEGIN IMMEDIATE`: also correct, but it takes the write lock on every rename; the catch is the smaller change.
- Drop the pre-check and rely only on the UNIQUE failure: the pre-check also separates NotFound and same-id renames cheaply, so it stays.

## Consequences
- Pros: the Tool Window always receives a value for a name clash, with or without another process.
- Cons and what we accept: a tag id read when the list loaded can be deleted and reused by another process (no `AUTOINCREMENT` on `tag.id`) before `rename` runs, which would rename the new tag. This needs a schema or API change and is out of scope.
- Follow-up work: the tag id reuse gap (separate issue).
