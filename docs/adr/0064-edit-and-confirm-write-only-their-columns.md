# 0064. Edit and location confirm write only the columns they own

- Status: Proposed
- Date: 2026-11-27
- Issue: #64
- PR: (draft, number after push)

## Context
The Tool Window edit saved through `NoteDraft.applyTo(note)` and `NoteRepository.update(note)`. `applyTo` copied the `Note` loaded when the list was last read, and `update` writes every column except `id`, `uid`, and `created_at` (ADR 0003). Open-time validation (#53), position write-back (#54), and another IDE process sharing `jnote.db` now change `rel_path`, `start_line`, `end_line`, and `status` in the background. An edit dialog can stay open for minutes, so a save wrote the stale anchor back. `NoteSaver.confirmLocation` and the APPROX branch of `update` had the same problem, and they also read the confirm snapshot from the stale line range.

The `update` chain also started from `completedFuture(draft)` and used `thenCompose`, so `repository()` could be evaluated on the EDT (the follow-up to #47 on this issue).

## Decision
- **Text edit.** `NoteRepository.updateText(uid, text, confirm = null)` runs `UPDATE note SET title, body, ai_source, source_url, prompt, updated_at WHERE uid = ?`. `NoteDraft.toText()` returns the new `NoteText` model in place of `applyTo`. The anchor and status columns are never written by an edit. It matches by `uid`, like `updateAnchors`.
- **Location confirm is a compare-and-set on the location the user saw.** `NoteSaver` reads the snapshot from the note's line range as the Tool Window showed it, off the EDT, as before, and passes it as `LocationConfirmation(relPath, startLine, endLine, snapshot)`. The repository runs `UPDATE note SET status = 'OK', snapshot = ?, updated_at = ? WHERE uid = ? AND rel_path = ? AND start_line = ? AND end_line = ?`. If no row matches, the location changed after the list was read, and nothing is confirmed. "Confirm" means "the location I am looking at is right", so it applies only while that location is still the stored one. The status is not part of the condition, because the user's judgment about that location overrides a status that changed in the meantime.
- **The result says whether the confirm applied.** Both methods return `SavedNote(note, locationMoved)`, or null when the note is gone. On `locationMoved`, the Tool Window shows a warning notification (English and Korean bundle keys `toolWindow.locationMoved.*`) that says the location was not confirmed and, for an edit, that the text was saved. `NotesChanged` is still published, so the list shows the new location.
- **One transaction per save.** `updateText` runs the text update and the confirm in one transaction, so the edit of an APPROX note and its confirm commit together.
- **Off the EDT.** `update` starts with `supplyAsync(..., background)` and uses `thenComposeAsync(..., background)` for every step that evaluates `repository()` or `tags()`.
- `NoteRepository.update` stays for callers that replace a whole note (JSON import). The edit no longer uses it.

## Alternatives considered
- **Reload the note by uid, then read the snapshot from its current lines.** It confirms a location the user never saw. Rejected.
- **Write the status, the snapshot, and the lines the user saw.** It overwrites the newer location from validation with a stale one, and the snapshot can belong to different code. Rejected.
- **Keep the whole-row `update` and compare `updated_at` (optimistic lock on the row).** `updateAnchors` keeps `updated_at` on purpose, so the version would not change, and a background position update would still make every edit fail. Rejected.
- **A status condition (`AND status = 'APPROX'`) on the confirm.** When validation sets OK or ORPHAN on the same lines, the confirm would be reported as "moved" though the location did not move. Rejected.

## Consequences
- Pros
  - A background location update is never lost to an edit or a confirm.
  - A confirm never stores a snapshot for lines other than the stored ones.
- Cons and what we accept
  - When the location changed, the user confirms again after looking at the new location. This needs a file open or another IDE writing during the edit, so it is rare.
  - The edit's text update and its tag replacement are still two transactions, as before. A failure in the tag step keeps the text, and saving again is idempotent.
- Follow-up work
  - Merge the edit's tag replacement into the `updateText` transaction if a later change needs it.
