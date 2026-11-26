# 0003. Note and tag repository

- Status: Proposed
- Date: 2026-11-26
- Issue: #3
- PR: (to be filled)

## Context
Every later feature (Tool Window, gutter, search, AI import) reads and writes notes and tags. They need one repository layer on top of the schema from ADR 0002. It must keep the performance rules of intent.md section 2: all DB access through `Database.submit`, nothing on the EDT. Schema v1 already allows rows that make no sense (for example a CODE note without a path), so the layer must keep illegal states out.

## Decision
- **Model (`model/`).** `NoteKind`, `NoteStatus`, `Note` (all schema v1 columns) and `NewNote` (a draft without id, uid, and timestamps). Both `Note` and `NewNote` validate their shape in `init` through one shared function: a CODE note needs a project, a relPath, and a line range (1-based, end inclusive, end >= start); a FREE note has no project and no anchor. `NewNote.code(...)` and `NewNote.free(...)` are the intended entry points. Violations throw `IllegalArgumentException` at construction, so a repository never sees an illegal value.
- **Note repository (`db/NoteRepository`).** Takes a `Database` and an injectable clock. Every method returns a `CompletableFuture` that runs on the DB thread. `create` generates `uid` as a random UUID and sets `createdAt = updatedAt = clock()`. `update` overwrites every field except `id`, `uid`, `createdAt`, bumps `updatedAt`, and returns the stored note, or null if the id does not exist. `update` never touches tags. `delete` returns whether a row was removed; links go away through the schema's `ON DELETE CASCADE`. SQL errors (constraint violations) complete the future exceptionally with the `SQLException` as cause; they are not swallowed.
- **List.** `list(project, allProjects)` orders by `updated_at DESC, id DESC`. With `allProjects = false` it returns the project's notes plus global FREE notes (`project IS NULL`), which are visible from every project; a null project returns only the global FREE notes. `allProjects = true` returns everything.
- **Tag repository (`db/TagRepository`, second PR).** Tags are a registered dictionary (intent.md section 5): a tag stays in the dictionary when no note uses it, and only an explicit delete removes it. Names are trimmed and must be non-blank; uniqueness is case-sensitive (the schema's `UNIQUE`). `getOrCreate` is idempotent. Rename returns a result value (renamed, conflict, not found, invalid name) instead of throwing on a unique conflict. Deleting a tag removes its links through the cascade and leaves notes intact. Attach is idempotent; detach keeps the tag. Tags of a note are sorted by name (binary order); notes of a tag use the list order above.

## Alternatives considered
- Nullable fields only, with checks in the repository: illegal values could be built and passed around until a save fails. Rejected; constructor validation fails at the source.
- A sealed hierarchy (`CodeNote` / `FreeNote`): precise, but every consumer (list, JSON import, UI) would branch on the type, and the schema is one table. Rejected as heavier than the single validated class.
- Auto-deleting a tag when its last note is removed: contradicts the registered-dictionary rule in intent.md section 5; users would lose tags they set up on purpose.
- Throwing on a rename conflict: the Tool Window must show a message for this expected case, so a value is easier to handle than an exception.
- Letting `update` replace tags: an edit dialog and an AI re-import would silently drop tags. Tag changes stay explicit.

## Consequences
- Pros
  - Illegal note shapes cannot reach the DB; tests need no SQL knowledge.
  - Callers get uniform async futures and never block the EDT.
- Cons and what we accept
  - `Note` is a data class, so `copy` runs the validation again (intended) but a copy that changes `kind` must also change the anchor fields.
  - `list` loads all matching notes; there is no paging (a personal store; revisit if measured slow).
  - Unused tags accumulate until deleted by hand.
- Follow-up work
  - Search query over the repository (#11), uid upsert for AI import, and the undo for delete (the undo can re-create a note from the deleted `Note`; a uid-preserving create is not in this issue).
