# 0013. Relocation on file open, path recovery, and confirming approximate locations

- Status: Proposed
- Date: 2026-11-26
- Issue: #13
- PR: #53 (validation on open, path recovery, and APPROX confirmation in one PR)

## Context
A note stores its path, line range, and code snapshot. Branch switches, other worktrees, and edits made outside the editor move the code, so the stored lines drift (intent.md section 6). Issue #12 built the pure matching core (ADR 0012). Issue #14 added the shared code that turns relocation results into anchors and writes them (`FileRelocation`, `AnchorUpdate`, `NoteRepository.updateAnchors`). This issue wires that core into the IDE: when it runs, what it changes automatically, how a broken path is recovered, and how an approximate location is cleared.

intent.md section 2 applies in full. There is no startup work and no app listener, no DB or file I/O on the EDT, and a file without notes costs only an in-memory cache lookup. While a file is open, issue #9 tracks note positions with RangeMarkers, so this issue must not compare a snapshot with code the user is editing.

## Decision
- **Trigger.** Validation hangs off the gutter marker pass that ADR 0008 already runs for every highlighted file. After the pass has the file's cached notes and they are not empty, it calls `OpenValidationService` (a project light service, not in `plugin.xml`). The pass only decides and schedules. Files without notes return before this point, so their cost is unchanged.
- **Once per open.** The service reads the document's editors with `EditorFactory.editors(document, project)` and marks them with an Editor user-data key. If no editor of the document carries the mark, it marks them all and schedules one validation. If one does, it only marks the new ones (a split view) and returns. When every editor of the file closes and the file opens again, the new editor has no mark and the file is validated again. While the file stays open it is never validated again. Positions during editing belong to issue #9. In IDE 2024.2 the editor list is a lock-free copy-on-write list with no EDT assertion (checked in the platform bytecode), so the pass reads it from its background read action.
- **What runs.** Off the EDT: resolve the project key, read the file's notes with `listByPath`, read the document text, line count, and modification stamp in one read action, and call `FileRelocation.relocate`. A file of 5,000 lines or fewer runs exact match and then similarity. A larger file runs exact match only. There, an OK note that is not found becomes NEEDS_RELOCATE, and APPROX, ORPHAN, and NEEDS_RELOCATE notes keep their status and lines, because an exact search could never find them and its failure says nothing new (issue #14's shared rule). Lines are counted without the empty piece after a final line break, so a 5,000-line file that ends with a line break still gets similarity. The rule lives in one function, `OpenValidationService.similarityAllowed`, which path recovery uses too. The run is cancelled when the project closes, the service is disposed, or the document changes. A cancelled run writes nothing.
- **What is written.** `updateAnchors` writes path, lines, and status in one transaction, skips rows whose values are already equal, and leaves `updated_at` alone, because a position the system found is not a user edit and must not reorder the "recently modified" list. `NotesChanged` is published once, on the EDT, only when at least one row changed. The gutter and the Tool Window reload from that event.
- **Idempotent, not locked.** Two passes can race and schedule the same file twice. Both compute the same anchors from the same text, and the second write changes nothing, so no lock is needed.
- **Failures.** A `DatabaseClosedException` (the DB path was switched during the run) is dropped. Other failures are logged at warn level without a notification. The gutter load reads the same database first and already reports database failures.
- **Path recovery.** When Go to Code finds no file at the note's path, `PathRecovery` searches once with `FilenameIndex` in the project scope. Candidate names are the file name, then the outermost class name with the original extension. At most 20 candidates are read. Each runs the same relocation for this one note with the same 5,000-line rule. A candidate counts as found when the result is OK, or APPROX from a candidate where similarity ran (in an exact-only candidate an APPROX note comes back APPROX without being found). The first exact match wins, then the first approximate one. A found note gets its new path, lines, and status, and the editor opens there. A note found nowhere becomes ORPHAN, or NEEDS_RELOCATE if a large candidate skipped similarity, and the existing "file not found" notification shows. A missing file is new information, so the note's earlier status is not kept here. The search is skipped, and nothing is written, during indexing, for a note without a snapshot, and when the project is closing. `NotesChanged` is published only when the write changed a row.
- **Confirming APPROX.** The detail area shows a short notice and a Confirm Location button for APPROX notes only. Confirming reads the current text of the note's line range with the same rules as note creation (including the snapshot cap) and saves status OK with that snapshot. Saving an edit of an APPROX note does the same. Both are user actions, so they use `NoteRepository.update` and bump `updated_at`. If the file or the range is gone, confirming fails with a notification and the note is unchanged. An edit still saves and the note stays APPROX.

## Alternatives considered
- A project `FileEditorManagerListener` or an `EditorFactoryListener` for "file opened": a listener is instantiated for every opened editor, including those of files without notes, and restored editors fire it at startup. ADR 0008 rejected the same shape for the gutter. Rejected.
- Validating on every gutter cache load (first load and every `NotesChanged` reload): simple, but it compares snapshots with code being edited. A heavily edited function would turn ORPHAN and lose its icon while issue #9's RangeMarker still knows where it is. Rejected.
- Marking the Document instead of its editors: a closed file's Document stays cached until garbage collection, and a branch switch reloads it in place. Reopening it before collection would skip validation. Rejected.
- Marking only on the first cache load per file in the service: a file opened, closed, switched to another branch, and reopened would never be validated again in that session. Rejected.
- Expected-value checks in the update (`WHERE status = ?`): see Consequences. Not added, by agreement with issue #14's shared code.
- Bumping `updated_at` on relocation: opening a file would move its notes to the top of the list. Rejected.
- A notification for background validation failures: the gutter load already notifies for the same database, and a second balloon per file adds noise. Rejected.

## Consequences
- Pros
  - No startup work, no listener, no new dependency. Files without notes pay nothing new.
  - Validation runs once per open, so it never fights issue #9's tracking.
  - Exact matches always move silently. Only real changes publish `NotesChanged`.
- Cons and what we accept
  - Icons show the stored lines for the moment between opening and the end of validation, then move.
  - In a file over 5,000 lines, an APPROX note keeps its old lines when the code moves again. Manual relocation fixes it.
  - Validation reads the notes, computes, and then writes without comparing expected values. If the user confirms an APPROX note in that window, validation can set it back to APPROX. The window is one relocation run.
  - Confirming reads the document, which includes unsaved edits.
  - A file open in a non-text view only (no Editor) is not validated.
  - Navigating to a note whose file exists still lands on the stored line when the file was closed. The open validation then moves the note and its icon.
- Follow-up work
  - Issue #9 re-syncs its RangeMarkers to the DB lines after `NotesChanged` and writes back only markers moved by edits (agreed through the coordinator).
  - Issue #14 owns manual relocation (no size limit) for NEEDS_RELOCATE and ORPHAN notes.
  - `FileRelocation` could normalize the document once per file (see Measurements).

## Measurements
Measured with `JNOTE_PERF=1 ./gradlew test --tests '*OpenValidationCostTest*' -i` on an Apple M2 Pro (10 cores) while other worktrees were building (load average 3.0 to 4.6). Pass costs are the median of 9 rounds of 2,000 calls (50 for the whole pass). Validation costs are the median of 15 runs. Each column is a separate JVM run.

| What | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Decision inside the pass, 1 editor open | 0.0013 ms | 0.0017 ms | 0.0015 ms |
| Decision inside the pass, 51 editors open | 0.0018 ms | 0.0018 ms | 0.0010 ms |
| Whole marker pass, Kotlin file, 140,003 PSI elements, 20 notes | 4.15 ms | 4.50 ms | not printed |
| Validation, 5,000 lines, 20 notes moved exactly | 12.9 ms | 17.1 ms | 16.3 ms |
| Validation, 5,000 lines, 20 notes slightly changed (similarity) | 45.7 ms | 51.4 ms | 49.4 ms |

- Run 1 was taken before the commits were reordered and before issue #14's follow-up commit, runs 2 and 3 after. That follow-up only changes the not-found branch, which these cases never reach, so the 25 to 30 percent spread between runs is machine variance under parallel builds, not a regression (inferred from the diff, not measured further).
- The decision costs under 0.1% of a pass, which ADR 0008 measured at 4.3 ms for the same kind of file. A file without notes returns before it, and a test asserts that its editor gets no mark.
- In both validation cases the DB read (0.2 ms) and write (0.9 ms) are small. The rest is computation on a pooled thread. A one-off split of the exact case showed `text.lines()` at 2.4 ms and the document normalized again for each note (`Relocator.locate` per note) at 7.9 ms for 20 notes. The similarity case is dominated by the similarity search (about 31 ms) on a templated document, which is close to the worst case in ADR 0012.
- Normalizing the document once per file instead of once per note would remove most of the 7.9 ms. That code belongs to issue #14's shared `FileRelocation` and is left as a follow-up.
