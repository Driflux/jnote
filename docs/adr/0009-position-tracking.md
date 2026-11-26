# 0009. Position tracking with RangeMarkers and write-back timing

- Status: Proposed
- Date: 2026-11-26
- Issue: #9
- PR: (to be filled)

## Context
A note stores a line range. When the user edits the file, the code under the note moves, and the gutter icon (ADR 0008) stays on the old line. intent.md section 6 fixes the agreed design. While a file is open, a RangeMarker follows the position, and changed line numbers are written to the DB on close or save. An edit that removes the range makes the note a relocation target (NEEDS_RELOCATE).

intent.md section 2 applies in full. Tracking must add no startup work, no app listener declared in `plugin.xml`, no DB or file I/O on the EDT, and no new dependency. A file with no notes must still cost only the cache lookup.

Two other issues write the same columns. Issue #13 validates a file's notes once when the file opens, from the gutter marker pass, and corrects their lines and status in the DB. Issue #14 relocates notes manually. All three go through `NoteRepository.updateAnchors` from #14. It finds each note by `uid`, writes `rel_path`, `start_line`, `end_line` and `status` in one transaction, skips rows that already hold those values, and keeps `updated_at`. It uses `uid` because a deleted note's `id` can be given to the next new note. The agreement with #13 is that it never revalidates while the file stays open. Issue #9 re-anchors a marker when the DB lines of its note change after `NotesChanged`, and writes back only markers that edits moved.

## Decision
- **Where tracking starts.** In the existing marker pass (`NoteLineMarkerProvider`), after the file's notes are in the cache. `GutterNoteService` owns a `NotePositions` object that maps each tracked `Document` to its relative path and its tracked notes. If a pass reports another relative path for the same document, tracking of that document starts over. A note with status OK or APPROX whose range lies inside the document gets one RangeMarker. The marker runs from the start of the start line to the end of the end line, without the final line break. ORPHAN and NEEDS_RELOCATE notes are not tracked. No `EditorFactoryListener`, no startup activity, nothing new in `plugin.xml`.
- **What the gutter draws.** A tracked note is drawn at the lines its marker covers now. A lost note draws nothing. A note that could not be tracked, such as one past the end of the file, keeps its DB lines, as before. A file without notes returns before `NotePositions` is touched, so its cost does not change.
- **Lost.** A marker is lost when it is no longer valid, or when it was not empty and has collapsed to an empty range. Deleting the lines with their line break invalidates the marker. Selecting exactly the text of the lines and deleting it leaves an empty marker. Both mean the range was removed.
- **Changes made by others.** Each tracked note keeps two placements, a placement being a line range with a status. `stored` is what the DB holds as far as this tracker knows. `seen` is what the cache showed last. On each pass the cache's placement is compared with `seen`. If it is the same, nothing happens. A cache that has not reloaded yet after our own write still shows the old placement, so it falls in this case. If it differs from `seen` and also from `stored`, another writer changed the note (#13 on open, #14 manual relocation, a JSON import), and both placements take the DB value. When the lines changed, the marker is disposed and created again at the DB lines. When only the status changed, the marker stays. If the cache now shows ORPHAN or NEEDS_RELOCATE, or the note is gone, the marker is disposed and the note is no longer tracked.
- **Database switch.** `GutterNoteService` already drops its cache when the database path or instance changes. It drops every tracked marker at the same moment. Each tracked document also remembers the `Database` its notes came from, and write-back submits to that instance. After a switch that instance is closed, so a late write fails instead of writing the old database's positions into another one. A copied database holds the same `uid`s, so this matters even with `uid` keys.
- **Write-back timing.** `GutterNoteService` subscribes when it is created, which is the first marker pass, never at startup. It listens to `FileDocumentManagerListener.beforeDocumentSaving` on the application bus and `FileEditorManagerListener.fileClosed` on the project bus. Both connections are tied to the service, so plugin unload removes them. On save, and on close once the file is open in no editor of the project, the changes are computed on the EDT from the in-memory markers. That is one map lookup for an untracked document. The write itself runs on the DB thread.
- **What is written.** For each tracked note the target placement is the marker's current lines with the stored status, or, for a lost note, the stored lines with NEEDS_RELOCATE. The stored lines stay as a hint for relocation. Only notes whose target differs from `stored` are written, as one `AnchorUpdate(uid, relPath, start, end, status)` each in one `updateAnchors` call. Tracked notes are keyed by `uid`, so a write never lands on a new note that reused a deleted note's `id`. A note that is already NEEDS_RELOCATE in `stored` is not written again. `updated_at` does not change. A position that follows an edit is not a change to the note, and the list is ordered by `updated_at`. When the write succeeds, `stored` takes the written placement if the same marker still follows the note. A note whose marker was replaced in the meantime, because another writer moved it or the file was closed and opened again, keeps its `stored`. The next pass after the cache reloads moves its marker to the DB lines. The check looks at the marker and not at `stored`, so a second save that starts before the first write finishes still keeps its own placement. If the write changed a row, `NotesChanged` is published on the EDT, so the list, the detail area and the gutter cache reload. A failed write shows an error notification and leaves `stored` as it was, so the next save tries again. Saving a document that has no unsaved changes fires no save event, so the retry comes with the next save after an edit, or with the close. A write that fails with `DatabaseClosedException` is dropped silently, because the notes belong to the old database.
- **Close.** After its write-back is submitted, a closed document's markers are disposed and the document is forgotten. Opening the file again creates new markers from the cached lines. The cache entry of a closed file reloads only when the file is drawn again, so the first pass after opening can still use the old lines. The pass after the reload moves the markers to the written lines.

## Alternatives considered
- Creating markers from an `EditorFactoryListener` or a listener declared in `plugin.xml`: it runs for every editor and is registered at startup. Rejected by intent.md section 2.
- `RangeHighlighter` in each editor's markup model: it follows edits the same way, but it is per editor and must be created on the EDT. The gutter already draws through the marker pass. Rejected.
- Writing on every edit through a `DocumentListener`: a DB write per keystroke, against the agreed save or close timing. Rejected.
- Re-anchoring whenever the cache differs from `stored`: right after our own write the cache still shows the old lines, and the marker would jump back to them. Rejected for the `seen` and `stored` pair.
- Never re-anchoring while the file is open: a save after #13 corrected the lines would overwrite the correction with lines derived from the old position. Rejected, and it breaks the agreement with #13.
- Compare-and-set on the old lines in the UPDATE: it would also protect against an external write that lands between a pass and a save. Rejected for now so that #9, #13 and #14 share one `updateAnchors`. The unprotected window is the time between an external write and the next cache reload.
- A separate project service for tracking: it would need the cache's notes and database from `GutterNoteService`, and two services would reference each other. Rejected for one `NotePositions` owned by `GutterNoteService`.

## Consequences
- Pros
  - Icons follow edits at once, and the DB catches up on every save or close with one small transaction.
  - No startup work, no EDT I/O, no new dependency. Files without notes pay nothing new. Untracked documents pay one map lookup per save.
  - Relocation by #13 and #14 takes effect in open editors without any call into this code.
- Cons and what we accept
  - A RangeMarker that an edit invalidated stays invalid after undo. Deleting a noted range and undoing it hides the icon, and a save in between marks the note NEEDS_RELOCATE. The next validation on open (#13) finds the code again.
  - Edits made between opening the file and the first pass after its notes load are not followed. Markers start from the DB lines.
  - Two worktrees of one repository share the project key, so they share notes. Their saves overwrite each other's lines, and an open editor can re-anchor to the other worktree's lines until the file is opened again.
  - The FTS update trigger rewrites a note's index row on each position update. It is one row per moved note.
  - If a file closes while a marker pass for it is running, the pass can track the document again after the close dropped it. The markers then live until the next database switch, or until the file is opened and closed again. Saves of that document still write back. This was found by reading the code and was not reproduced.
- Follow-up work
  - The range bar of #23 can read the same current lines from the marker pass.

## Measurements
Measured with `JNOTE_PERF=1 ./gradlew test --tests '*NoteLineMarkerPassCostTest*' --rerun -i` on an Apple M2 Pro (10 cores, 32 GB, macOS 26.6.2). `--rerun` makes Gradle run the test even when nothing changed. Other builds were running on the machine (load average 7 to 8), so the code before and after this change ran alternately, 5 runs each. Each run reports the median of 9 rounds. A round is 2,000 passes for a file without notes and 50 passes for a file with 20 notes. Each cell is the median of the 5 run medians, with their range.

| File (5,000 lines) | Code | No notes | 20 notes | Leaf scan alone |
|---|---|---|---|---|
| XML | before | 0.002 ms (0.002 to 0.003) | 3.73 ms (3.53 to 3.95) | 2.92 ms (2.78 to 3.05) |
| XML | after | 0.002 ms (0.002 to 0.003) | 3.85 ms (3.64 to 4.11) | 3.02 ms (2.80 to 3.40) |
| Kotlin | before | 0.002 ms (0.001 to 0.002) | 4.04 ms (3.89 to 4.63) | 3.17 ms (2.93 to 3.74) |
| Kotlin | after | 0.002 ms (0.001 to 0.003) | 4.20 ms (4.01 to 4.78) | 3.30 ms (3.06 to 3.98) |

There is no measurable difference. A file without notes returns before `NotePositions` is touched and stays at about 0.002 ms. For the file with 20 notes the median rose by 0.12 ms (XML) and 0.16 ms (Kotlin). The leaf scan runs none of the new code and rose by 0.10 ms and 0.13 ms in the same runs, and the ranges overlap. The pass minus the leaf scan went from 0.82 to 0.84 ms (XML) and from 0.87 to 0.90 ms (Kotlin), within the spread of single runs. A one-off probe on the same files timed `GutterNoteService.track` alone at 0.002 to 0.003 ms per call with 20 tracked notes. The daemon calls the provider twice per root, so a pass pays this twice. Creating and disposing the 20 markers took 0.03 to 0.04 ms, which the first pass after a file's notes load pays once. The leaf scan still dominates the pass.
