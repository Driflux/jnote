# 0009. Position tracking with RangeMarkers and write-back timing

- Status: Proposed
- Date: 2026-11-26
- Issue: #9
- PR: (to be filled)

## Context
A note stores a line range. When the user edits the file, the code under the note moves, and the gutter icon (ADR 0008) stays on the old line. intent.md section 6 fixes the agreed design. While a file is open, a RangeMarker follows the position, and changed line numbers are written to the DB on close or save. An edit that removes the range makes the note a relocation target (NEEDS_RELOCATE).

intent.md section 2 applies in full. Tracking must add no startup work, no app listener declared in `plugin.xml`, no DB or file I/O on the EDT, and no new dependency. A file with no notes must still cost only the cache lookup.

Two other issues write the same columns. Issue #13 validates a file's notes once when the file opens, from the gutter marker pass, and corrects their lines and status in the DB. Issue #14 relocates notes manually. Both go through `NoteRepository.updateAnchors` from #14, which writes `start_line`, `end_line` and `status` in one transaction and keeps `updated_at`. The agreement with #13 is that it never revalidates while the file stays open. Issue #9 re-anchors a marker when the DB lines of its note change after `NotesChanged`, and writes back only markers that edits moved.

## Decision
- **Where tracking starts.** In the existing marker pass (`NoteLineMarkerProvider`), after the file's notes are in the cache. `GutterNoteService` owns a `NotePositions` object that maps each tracked `Document` to its tracked notes. A note with status OK or APPROX whose range lies inside the document gets one RangeMarker. The marker runs from the start of the start line to the end of the end line, without the final line break. ORPHAN and NEEDS_RELOCATE notes are not tracked. No `EditorFactoryListener`, no startup activity, nothing new in `plugin.xml`.
- **What the gutter draws.** A tracked note is drawn at the lines its marker covers now. A lost note draws nothing. A note that could not be tracked, such as one past the end of the file, keeps its DB lines, as before. A file without notes returns before `NotePositions` is touched, so its cost does not change.
- **Lost.** A marker is lost when it is no longer valid, or when it was not empty and has collapsed to an empty range. Deleting the lines with their line break invalidates the marker. Selecting exactly the text of the lines and deleting it leaves an empty marker. Both mean the range was removed.
- **Changes made by others.** Each tracked note keeps two placements, a placement being a line range with a status. `stored` is what the DB holds as far as this tracker knows. `seen` is what the cache showed last. On each pass the cache's placement is compared with `seen`. If it is the same, nothing happens. A cache that has not reloaded yet after our own write still shows the old placement, so it falls in this case. If it differs from `seen` and also from `stored`, another writer changed the note (#13 on open, #14 manual relocation, a JSON import), and both placements take the DB value. When the lines changed, the marker is disposed and created again at the DB lines. When only the status changed, the marker stays. If the cache now shows ORPHAN or NEEDS_RELOCATE, or the note is gone, the marker is disposed and the note is no longer tracked.
- **Database switch.** `GutterNoteService` already drops its cache when the database path or instance changes. It drops every tracked marker at the same moment. Each tracked document also remembers the `Database` its notes came from, and write-back submits to that instance. After a switch that instance is closed, so a late write fails instead of writing old note ids into another database.
- **Write-back timing.** `GutterNoteService` subscribes when it is created, which is the first marker pass, never at startup. It listens to `FileDocumentManagerListener.beforeDocumentSaving` on the application bus and `FileEditorManagerListener.fileClosed` on the project bus. Both connections are tied to the service, so plugin unload removes them. On save, and on close once the file is open in no editor of the project, the changes are computed on the EDT from the in-memory markers. That is one map lookup for an untracked document. The write itself runs on the DB thread.
- **What is written.** For each tracked note the target placement is the marker's current lines with the stored status, or, for a lost note, the stored lines with NEEDS_RELOCATE. The stored lines stay as a hint for relocation. Only notes whose target differs from `stored` are written, as one `AnchorUpdate` each in one `updateAnchors` call. A note that is already NEEDS_RELOCATE in `stored` is not written again. `updated_at` does not change. A position that follows an edit is not a change to the note, and the list is ordered by `updated_at`. When the write succeeds, `stored` takes the written placement, unless another writer changed it in the meantime, and `NotesChanged` is published on the EDT, so the list, the detail area and the gutter cache reload. A failed write shows an error notification and leaves `stored` as it was, so the next save tries again. A write that fails with `DatabaseClosedException` is dropped silently, because the notes belong to the old database.
- **Close.** After its write-back is submitted, a closed document's markers are disposed and the document is forgotten. Opening the file again creates new markers from the DB lines, which by then hold the written values.

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
- Follow-up work
  - The range bar of #23 can read the same current lines from the marker pass.
