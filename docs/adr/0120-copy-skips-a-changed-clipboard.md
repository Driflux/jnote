# 0120. A note copy does not overwrite a clipboard that changed while it ran

- Status: Proposed
- Date: 2026-11-27
- Issue: #120
- PR: linked from issue #120

## Context
"Copy as Markdown/JSON" reads the selected notes from the database and formats them off the EDT, then writes the clipboard (ADR 0019). The only guard was the shared `copyGeneration` ticket, which drops a result when a newer JNote copy started. If the user copied something else in the IDE while the read was still running, the late JNote result overwrote it.

## Decision
- Each `JNotePanel` counts clipboard changes with `CopyPasteManager.addContentChangedListener`, registered with the panel as its disposable.
- `copyToClipboard` remembers the count when the copy is requested and writes only if it is unchanged when the result arrives on the EDT. A failed copy still reports its error.
- A copy that finishes after its panel was closed does not write, because the panel no longer watches the clipboard.
- The `copyGeneration` ticket stays. Without it, an older copy that finishes first would write, and the newer copy would then see a changed count and skip, leaving the older text.

## Alternatives considered
- **Compare the clipboard text at request and write time.** It also sees copies made in other applications, but it reads the system clipboard twice on the EDT, which can be slow for large or non-text contents, and copying the same text again looks like no change. Rejected.
- **Compare the `Transferable` instance.** `CopyPasteManager.getContents()` can return a new wrapper for unchanged system contents, so every copy would be skipped. Rejected.

## Consequences
- Pros
  - A copy made in the editor while a note copy runs is kept.
  - No clipboard reads; one listener per open Tool Window, removed with it.
- Cons and what we accept
  - A copy made in another application is seen only when the IDE owned the clipboard and AWT reports the lost ownership before the result arrives (`contentChanged(old, null)`). Otherwise the JNote result still overwrites it. The window runs from the request through the database read, the formatting, and the wait for the EDT.
  - Pasting the top entry of the clipboard history ("Paste from History") writes it to the system clipboard without a change event when it is already the top of the history. A JNote result that arrives after that overwrites it.
  - The count can also rise for an unchanged clipboard (the same text copied again), which skips the JNote write. The user can copy again.
- Tests
  - `ToolWindowCopyTest`: a copy whose read finishes after the user set the clipboard leaves the user's text (failed before the change); a copy that finishes after its panel closed leaves the clipboard alone; a copy started after the user copied is written.
