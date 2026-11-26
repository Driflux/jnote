# 0014. Manual relocation and the 5,000-line benchmark

- Status: Proposed
- Date: 2026-11-26
- Issue: #14
- PR: #52

## Context
intent.md section 6 fixes when relocation is automatic. An exact match is always applied in the background. Similarity runs automatically only for files of 5,000 lines or fewer, and notes in larger files become NEEDS_RELOCATE. Manual relocation has no size limit and shows progress. Section 7 says manual relocation runs as a cancellable Background Task. Section 2 says a feature is measured on a 5,000-line file and reduced or made manual if it misses the target.

Issue #14 asks for the manual trigger, the progress, and a benchmark of 20 displaced notes in a 5,000-line file. intent.md gives no number for the target. The main agent set a provisional target for this ADR: relocating 20 notes of a 5,000-line file finishes within 1 second in the background.

Issues #9 (write-back of tracked positions) and #13 (relocation when a file opens) write the same anchor columns as #14. All three branches start from the same commit, so the write path and the result-to-status rule are built once, in one commit that the other branches cherry-pick.

NEEDS_RELOCATE and ORPHAN notes draw no gutter icon (ADR 0008), and the Tool Window list does not show a status. The user therefore starts a manual run from a file or from a note, not from a visible broken marker.

## Decision
- **Anchor update, shared with #9 and #13.** `AnchorUpdate(uid, relPath, startLine, endLine, status)` is the new anchor of one note. Its constructor rejects a blank path and a range that a CODE note cannot hold, because such a row would make `Note`'s own check fail for every list that reads it. `NoteRepository.updateAnchors(updates)` writes `rel_path`, `start_line`, `end_line`, and `status` for all updates in one transaction and returns how many notes changed.
  - It does not change `updated_at`. The list is sorted by most recent change, and a relocation is not a change the user made.
  - A row whose four values are already equal is skipped by the `WHERE` clause (row value `IS NOT`). The returned count is then the real number of changed notes, so a caller publishes `NotesChanged` only when something moved, and the FTS update trigger runs only for changed rows.
  - Updates find notes by `uid`, not by `id`. `note.id` is an `INTEGER PRIMARY KEY` without `AUTOINCREMENT`, so deleting the newest note and creating another one reuses its id. A relocation that started before the delete would otherwise write lines into the new note. A uid is never reused. Restoring a deleted note brings back the same uid, which is the same note.
- **One file at a time.** `FileRelocation.relocate(notes, documentLines, similarity, isCancelled, onProgress)` is pure. It returns one `AnchorUpdate` per CODE note with a non-blank snapshot, in input order. A note without a snapshot has nothing to search for and is left out.

  | `Relocator` result | Status | Path and lines |
  |---|---|---|
  | `Match` | OK (also clears APPROX) | found lines |
  | `Approx` | APPROX | found lines |
  | `None`, similarity on | ORPHAN | unchanged |
  | `None`, similarity off | NEEDS_RELOCATE for an OK note, any other status stays | unchanged |

  An APPROX note's snapshot differs from its code until the user confirms it (intent.md section 6), so an exact search can never find it. Without the "other status stays" rule, #13's exact-only run on a large file would turn every manually relocated APPROX note back into NEEDS_RELOCATE each time the file opens. ORPHAN and NEEDS_RELOCATE notes keep their status for the same reason. An OK note matched its code exactly before, so failing the exact search means the code changed.

  A cancelled `Relocator.locate` also returns `None` (ADR 0012), so `FileRelocation` checks `isCancelled` after each note and returns `null` instead of writing ORPHAN. `RelocationOptions.similarity = false` makes `Relocator` stop after the exact search. #13 uses it for files over 5,000 lines. The path in the result is the note's current path, and #13's path recovery replaces it.
- **Entry points.** The editor context menu has "Relocate JNote Notes in This File" (`JNote.RelocateNotes`), right after "Add JNote Note". It is visible when the editor file is under the project base path. The Tool Window detail area has a "Relocate" button for CODE notes of the current project. Both start the same task for one file. The button relocates every note of the selected note's file, not only the selected one. The task resolves the path against this project and reads this project's notes, so the button is hidden for a note of another project in the "All projects" view.
- **What one run covers.** Every CODE note of the file, whatever its status. A note that is still in place costs one exact scan and is not written. A note whose code moved while the file was closed is fixed in the same run.
- **The task.** `RelocateNotesTask` is a cancellable `Task.Backgroundable` with no size limit. Its `run` finds the file through `CodeTargets.find`, resolves the project key, reads the notes with `listByPath`, reads the document text in a read action, relocates with the progress fraction set after each note, and writes all updates in one `updateAnchors` call. The text comes from `FileDocumentManager`, so unsaved edits in an open editor count. Nothing runs on the EDT except `onSuccess` and `onThrowable`.
- **A cancel during the search writes nothing.** The search returns `null` and the task throws `ProcessCanceledException` before `updateAnchors`, so notes already relocated in that run are not written either. A rerun costs the same time again. In return, a cancel never leaves half a file relocated, as in ADR 0015 for the DB path switch. Once every note is searched, the task writes one transaction of about 2 ms. A cancel pressed after that point cannot stop the write. The platform still calls `onCancel` when the indicator is cancelled at the end, so the task reports from `onFinished`, which runs in every case, whenever the write finished.
- **Result.** When the write finishes, a notification gives the counts: found at the same code, approximate, not found. A file with no relocatable note says so. `NotesChanged` is published only when `updateAnchors` changed a row, and the gutter and the list reload from it. A missing file shows the existing "File not found" warning. A failure, such as a locked database, shows an error notification with the reason and says that no note changed.
- **Thresholds stay.** The benchmark below meets the target, so the similarity threshold 0.8, the line floor 0.5, and the 5,000-line automatic limit do not change.

## Measurements
`JNOTE_PERF=1 ./gradlew test --tests '*RelocationBenchmarkTest*' -i | grep JNOTE-PERF` on an Apple M2 Pro (10 cores), JVM 21. Other builds ran on the machine, with a load average of 5 to 11. Each value is the median of 9 timed runs after 3 warm-up runs. A range spans the medians of two separate JVM runs. The time covers `FileRelocation.relocate` for all 20 notes, so it includes normalization, the exact search, and the similarity search, and it excludes the database and the document read. The inserted and deleted lines make the documents 4,858 to 5,050 lines long.

The benchmark places 20 notes evenly in the file. Snippet sizes cycle through 3, 5, 8, 12, and 20 lines. 50 lines are inserted at the top, so every note moves. Then each note's code is kept (moved), edited (every fifth non-blank character of its longest line replaced), or deleted. "Mixed" is 10 moved, 5 edited, and 5 deleted. The templated file has 5,000 lines `val valueN = compute(N, N * 7) // generated line`, the worst case for the similarity bounds (ADR 0012). The repository file is the first 5,000 lines of this repository's Kotlin sources in path order.

| 20 notes, 5,000 lines | Templated | Repository code |
|---|---|---|
| Moved (all exact) | 13.7 to 14.9 ms | 21.6 to 22.4 ms |
| Edited (all approximate) | 110.6 to 112.5 ms | 65.4 to 69.7 ms |
| Deleted | 117.9 to 120.7 ms | 62.8 to 65.6 ms |
| Mixed | 62.3 to 63.7 ms | 42.0 to 45.3 ms |
| Mixed, similarity off | 13.3 to 13.5 ms | 20.6 to 23.6 ms |
| Normalizing the document 20 times, alone | 12.4 to 13.0 ms | 19.7 to 23.6 ms |

- The worst case, 120.7 ms, is about 8 times under the 1-second target.
- Every run returned one update per note. Moved and edited notes landed at the expected lines, except one edited note in the templated file. A debugging run printed it: a 3-line note matched a window 10 lines higher whose lines differ from the snapshot only in digits, so that window's mean similarity was higher than the edited original's. The committed benchmark prints only the count (19 of 20). ADR 0012 accepts this for templated code. Deleted notes in real code became ORPHAN (19 of 20; one found an approximate match elsewhere). In templated code they became APPROX, because neighboring lines are similar.
- What limits the time. The work is CPU on one thread with no I/O. Without similarity, nearly all the time is normalization. `Relocator.locate` normalizes the whole document once per note, and normalizing 20 times alone takes as long as the exact-only run. With similarity, the rest of the time is the similarity search. ADR 0012 found that the first-pass bound checks limit that search. This run did not profile it again, and the split between normalization and search comes from the separate timings above, not from a profiler.
- Database share. With 20 notes, `listByPath` took 0.3 to 0.7 ms. Two `updateAnchors` transactions that each change 20 rows took 3.5 to 4.1 ms, so one takes about 2 ms (derived, not timed alone). The document read was not timed separately.
- Larger files, manual only (templated, 5 runs each): 20,000 lines took 439.5 to 456.8 ms (deleted) and 231.7 to 245.5 ms (mixed). 50,000 lines took 1,264 to 1,339 ms (deleted) and 655 to 782 ms (mixed). The time grows with the file size, so the progress indicator and the cancel button matter there.

## Alternatives considered
- A project service that owns all relocation runs, with one run per file at a time and a write after each note: it would order #13's automatic run and a manual run on the same file. Both compute the same result from the same snapshot and text, so a race writes equal values twice, which `updateAnchors` skips. Rejected as shared state without an invariant to protect.
- Keep the notes finished before a cancel: saves part of a rerun, but leaves a file half relocated and needs a write per note. Rejected for all or nothing.
- Raise `updated_at` on relocation: every file open (#13) would reorder the "most recent first" list. Rejected.
- Find notes by `id`: ids are reused after deleting the newest note. Rejected for `uid`.
- Make the detail button relocate only the selected note: a second scope with its own filter, while siblings cost one exact scan each. Rejected.
- Normalize the document once per file instead of once per note: it would remove about 12 to 24 ms of each 5,000-line run. The target is met without it, and it needs a `Relocator` entry that takes a normalized document. Not done now.
- Lower the 5,000-line automatic limit: not needed at 120.7 ms for the worst case.

## Consequences
- Pros
  - The user can relocate any file, of any size, and cancel it. A cancel changes nothing.
  - #9, #13, and #14 share one write path and one result-to-status rule.
  - Relocation does not reorder the list and does not reload the gutter when nothing moved.
- Cons and what we accept
  - A run reads the notes and the text once at the start. Edits made during the run are not seen. A title or body edit made during the run is kept, because the run writes only the anchor columns.
  - Templated code can relocate an edited note to a similar neighbor.
  - `updateAnchors` overwrites without comparing to the values it read. If #9 writes back a tracked position while a relocation of the same file runs, the later write wins.
  - In a file over 5,000 lines, an APPROX note keeps its old lines after #13's exact-only run, even if its code moved. A manual run fixes it.
  - A cancel late in a long search throws away the work done so far.
  - A cancel pressed during the final write is reported as a finished run, because the write cannot be stopped.
  - The detail button relocates the whole file, which takes longer than one note on a large file.
- Follow-up work
  - #13 can offer this action in its notification about notes that need relocation.
  - Normalize the document once per file if larger files make manual runs too slow.
