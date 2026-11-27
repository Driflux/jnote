# 0017. JSON import: upsert, preview, backup, and the no-delete rule

- Status: Proposed
- Date: 2026-11-27
- Issue: #17
- PR: (import engine), (preview dialog and file import), (clipboard import and pre-import backup)

## Context
AI agents and people write notes in JSON format v1 (ADR 0016, `docs/ai-format.md`). JNote must take those notes in safely: a person confirms every write, nothing is lost, and a bad item does not block the good ones (intent.md section 10.3). Several IDE processes share one DB file, so the import must not race with another IDE's writes, and the preview a person reads may be stale by the time they click Apply. The inbox (#18) feeds the same flow.

Constraints from intent.md section 2: no startup work, no DB or file I/O on the EDT, no new dependency.

## Decision
- **Upsert by `uid`.** An item whose `uid` matches a note updates it; an item with an unknown `uid` creates a note with that `uid`; an item without `uid` creates a note with a new UUID. `repo_url` is already normalized into the project key by the codec (ADR 0016).
- **Whole-note replacement.** An update replaces every field with the item's values. A field left out is cleared (`body` becomes empty, `tags` become none, `ai_source`, `source_url`, `prompt` become null). `kind` may change. This keeps one simple rule that matches "export, edit, import back", and the spec tells agents to always send the whole note (agreed with the coordinator, see the #16 spec).
- **Location status.** An update keeps the stored status (`APPROX`, `ORPHAN`, `NEEDS_RELOCATE`) while the project, path, and line range stay the same, because relocation set it from the real code. A changed location, a new code note, and any free note get `OK`. A change to only `class_name` or `snapshot` keeps the status.
- **Unchanged items.** An item equal to its note in every imported field (tags compared as a set) is `Unchanged`: it is not written, `updated_at` does not move, and the preview does not count it as an update.
- **Emptied fields.** When an update clears a `body` or tags that had values, the plan marks it (`emptiedBody`, `emptiedTags`) so the preview can flag it and a person catches an accidental wipe.
- **Never delete.** Import only inserts and updates notes. A note left out of a document is untouched. Tags removed from a note stay in the dictionary, as with editing in the UI. Unknown tags are registered.
- **Duplicate `uid`s.** When one document holds the same `uid` twice, every copy is skipped and reported (`FieldProblem.DUPLICATE`), instead of letting the last one win. The check lives in the codec because only it knows item positions, and it counts every item with a string `uid`, valid or not, so a broken copy still marks its valid twin as ambiguous.
- **Plan, then apply again.** `NoteImporter.plan` reads the DB in one read transaction (so a note row and its tags come from one snapshot) and classifies each item for the preview; it writes nothing. `NoteImporter.apply` runs one write transaction started with `BEGIN IMMEDIATE` on the DB thread and classifies each item again inside it. The preview is advisory: if another IDE deleted or created a note after the preview, apply still follows the upsert rule, and its result reports what really happened. One exception protects what the person approved: if re-classifying finds that an update would now clear a body or tags that the preview did not flag (another IDE added them meanwhile), apply rolls back with `PlanOutdatedException`, and the UI plans again and shows the new preview. Waiting for another process's write lock is bounded by the 5 s busy timeout; past it the import fails with an error and writes nothing. Taking the write lock first means no other process can slip a write between the read and the write, and two processes importing the same new `uid` cannot both insert it (tested with two connections).
- **Cancellation.** Apply checks for cancellation before each item and once more right before `COMMIT`; a cancel or any SQL error rolls the whole transaction back, so an import is all or nothing.
- **Preview and file import (second PR).** Reading the file, decoding, and planning run in a cancellable `Task.Backgroundable`. The modal preview on the EDT shows "N new, M updated" with each title, the unchanged count, and a mark on updates that clear a body or tags; the person chooses Apply or Cancel. Apply runs in a second cancellable background task, then publishes `NotesChanged`. Skipped items and their reasons (bundle strings per `FieldProblem` and field) are collected into one notification together with the result. A rejected document (malformed, wrong version) gets one error notification and no preview. The entry points live in the `Tools > JNote` group. The flow takes a list of documents so the inbox (#18) can pass several files; the same `uid` in two documents skips both copies, like a duplicate inside one document. When nothing is new or changed, no preview opens and an information notification says so. Unreadable files and rejected documents are listed with the skipped items; when nothing valid remains the notification is an error. The preview and the apply use the same `Database` instance captured when planning, so a DB path switch in between makes the apply fail on the closed database instead of writing the approved changes into a different file. Documents are told apart by position, not name, and skipped items' `uid`s count toward cross-document duplicates. A caller callback runs after a finished import (or one with nothing to import), so the inbox can move processed files.
- **Clipboard and backup (third PR).** Clipboard import reads the text with `CopyPasteManager` on the EDT (an in-memory read) and runs the same flow. Right before the apply transaction, on the same DB-thread job, JNote writes `VACUUM INTO` a uniquely named temp file next to the DB and moves it atomically to `<db name>.pre-import.bak` (`jnote.pre-import.bak` for the default `jnote.db`), overwriting the previous backup. `VACUUM INTO` copies one read snapshot, so it needs no WAL checkpoint and stays consistent while another IDE writes; a plain file copy after a checkpoint could tear if another process checkpoints during the copy. The unique temp name keeps two IDEs importing at once from deleting each other's temp file (a shared name failed the two-connection test). If the backup fails, nothing is imported. Another IDE can still write between the backup and the import's write lock; the backup is the state just before, not a lock-held snapshot.

## Alternatives considered
- Patch semantics (a missing field keeps the stored value): friendlier for "just retag", but needs absent, null, and empty to mean three things in the spec, the model, and the preview. Rejected in favor of one rule plus the emptied-field marks.
- Apply the plan as computed for the preview: a stale plan would insert a duplicate `uid` (a UNIQUE error that fails the whole import) or update a note that no longer exists. Rejected; apply decides again under the write lock.
- One transaction per item: a failure or cancel halfway would leave a half-imported document. Rejected.
- Reuse `NoteRepository` and `TagRepository` calls: each is its own DB job, so the whole import could not be one transaction. The importer writes its SQL on one connection instead.
- Delete notes missing from the document (sync semantics): could wipe notes when an agent sends a partial list. Rejected by intent.md section 10.3.

## Consequences
- Pros
  - An import is atomic, race-free across IDE processes, and never deletes.
  - The preview counts only real changes and flags wiped fields.
- Cons and what we accept
  - A careless agent that sends a partial note clears fields; the preview mark and the pre-import backup are the safety net.
  - The importer has its own INSERT and UPDATE statements next to `NoteRepository`'s; a schema change must update both (the repository and importer tests both cover every column).
- Follow-up work
  - #18 inbox feeds this flow; #19 copy as JSON produces documents it accepts.
