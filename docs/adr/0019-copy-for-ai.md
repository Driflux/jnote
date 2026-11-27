# 0019. Copy for AI: copy formats and the instruction text source

- Status: Proposed
- Date: 2026-11-27
- Issue: #19
- PR: #93 (copy as Markdown / JSON), #95 (copy format instructions)

## Context
intent.md sections 7 and 10.4 ask for two clipboard features. People paste notes into an AI chat, so selected notes are copied as Markdown (title, file location, snippet, note text) or as JSON format v1. Web AIs such as ChatGPT and Gemini cannot use the Claude Code skill, so "Copy format instructions for AI" copies a prompt that teaches them JSON format v1. The issue requires one source for that text: `docs/ai-format.md` (ADR 0016), bundled as a resource at build time.

Constraints from intent.md section 2: no startup work, no DB or file I/O on the EDT, no new dependency. The DB file is shared with other IDE windows that can edit or delete a note while it is selected here.

## Decision
- **Where.** The Tool Window list gets a right-click menu with "Copy as Markdown" and "Copy as JSON". Right-clicking a row outside the selection selects that row first, as list views usually do; right-clicking inside a multi-row selection keeps it. The items are disabled without a selection.
- **Several notes at once.** The list switches from single to multiple-interval selection, because pasting several notes into one chat is the point of the feature. The detail panel keeps showing `selectedValue` (the first selected row). A reload restores every selected note, matched by `uid` because another IDE can delete a note and a new note can receive its id; if none of them is left the detail panel is cleared.
- **What is read.** Copy takes the `uid`s of the selected rows at the moment of the click and reads those notes through the DB executor in one statement on `v_notes_export`, in selection order. A single SQLite statement sees one snapshot, so a note and its tags cannot come from two different moments. `uid` is used instead of `id` because SQLite can reuse the id of a deleted note. Notes deleted in the meantime are left out; if all are gone, the clipboard is not touched. Reading from the DB instead of the list rows also copies the latest text when another window edited the note, and it does not depend on what the list rows hold (the rows are summaries since #62, so they carry only `id` and `uid`).
- **Threads.** Reading runs on the DB executor, turning the notes into text runs on the application pool, and the EDT only writes the finished string to the clipboard once through `CopyPasteManager`. Each copy takes a ticket from one counter that all panels share, because the clipboard is one per IDE while every project window has its own panel; a copy that finishes after a newer one started is dropped, so an older, slower copy never overwrites a newer one. A failed read shows a notification instead of touching the clipboard.
- **Markdown shape.** Per note: `## title`, the location as inline code (`src/A.kt:L3-5`, the same form as the detail panel), the snippet in a fenced block whose info string is the file extension, then the note text. FREE notes have no location or snippet; empty parts are left out. The fence is one backtick longer than the longest backtick run inside the snippet, so a snippet that contains a fence (a README, a Markdown file) cannot close the block early. The location inline code is built the same way, with padding spaces only when the path starts or ends with a backtick, so a path that contains backticks cannot end the span early. Tags and AI provenance stay out: the issue names four parts, and the JSON copy carries everything.
- **JSON shape.** The same document an export writes (`NoteFormatCodec.encode`), including `uid`, so a web AI can send back updates that import as upserts.
- **Instruction text (#95).** `docs/ai-format.md` is the only copy of the spec; `processResources` in `build.gradle.kts` places it in the plugin resources as `ai/ai-format.md`, so the copied prompt is always the spec of the build. `AiInstructions.text()` returns the spec's own "For a web AI" prompt example (the quote markers removed, a multi-line quote joined) followed by the whole spec; if that section is missing, the spec alone. Nothing is written in Kotlin or the message bundle, so the spec stays the only source. The menu item sits below a separator, is always enabled, and needs no selection. The resource is read on the application pool because it is file I/O; the item shares `copyToClipboard` with the note copies, so it takes a ticket from the same counter and an older, slower copy cannot overwrite a newer one. A text of `null` (all selected notes were deleted meanwhile) leaves the clipboard untouched.

## Alternatives considered
- Copy only the one selected note and keep single selection: smallest change, but copying several notes for one question would take several round trips. Rejected.
- Format the rows already in the list: no DB read, but the rows can be stale after another window edits a note, and #62 removes the full note from the rows. Rejected.
- Read notes and tags with two queries (`note`, then `note_tag`): needs an explicit transaction to stay consistent. The view gives the same in one statement and is the read contract that migrations keep. Rejected.
- Reuse the `SELECTED_NOTES` data key and the note mapper from the export PR (#16, feat/16-3): this track uses only the codec. Merging the two later needs a small reconciliation in `JNotePanel`. Accepted as a known cost, not an alternative taken.
- Write the instruction text in the message bundle: a second copy of the spec that drifts. Rejected by the issue.

## Consequences
- Pros
  - No DB or file I/O on the EDT and no work until the menu is used.
  - The Markdown is valid whatever the snippet contains, and the JSON round-trips through import.
- Cons and what we accept
  - Multiple selection changes what Delete means from the keyboard. The Delete key (added by #20) still acts only on the note shown in the detail panel, which is the first selected row, so with several rows selected it deletes one note, not all of them. This is tracked as a follow-up issue from the PR #93 review.
  - `JNotePanel` and the export PR (feat/16-3) both add selection plumbing; the later merge reconciles them.
  - Two review findings are left as follow-up issues: a note body with an unclosed code fence can swallow the next note in a multi-note Markdown copy, and a slow copy can overwrite text the user copied elsewhere in the meantime (the ticket only orders JNote copies).
  - The source files carry no comments by project decision, so the reasons above live here.
- Follow-up work
  - #21 skill reuses `docs/ai-format.md` the same way.
  - Two low review findings of #95 were held: fence-aware heading parsing (the real-spec test already fails if the prompt is lost) and a dedicated error for a missing bundled resource (that is a broken build, not a runtime state).
