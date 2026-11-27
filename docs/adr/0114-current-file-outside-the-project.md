# 0114. Current file filter tells a file outside the project apart from no file

- Status: Proposed
- Date: 2026-11-27
- Issue: #114
- PR: linked from issue #114

## Context
The "Current file" filter kept the active file as `currentRelPath: String?`. `AnchorLines.relativePath` returns null for a file outside `project.basePath`, which is the same value as "no editor". With a file from another folder or a library open, the empty list said "No file is open", which is false and points the user to the wrong action.

## Decision
- `ToolWindowState.currentFile` is a sealed `CurrentFile`: `None` (no editor with a file), `OutsideProject` (a file outside the project), or `InProject(relPath)`. It replaces `currentRelPath`, so the two "no match" causes cannot be confused again.
- `JNotePanel.currentFile()` builds it from `FileEditorManager.selectedEditor?.file` and `AnchorLines.relativePath`.
- For `OutsideProject` the list stays empty and shows new bundle keys `toolWindow.empty.currentFile.outside.title` and `.hint` (English and Korean): "The open file is not in this project" / "Open a file of this project, or turn off Current file." The order of the empty-state messages is unchanged; the new message sits right after "No file is open".

## Alternatives considered
- **A second flag next to `currentRelPath`** (for example `currentFileOutside: Boolean`). Smaller diff, but a path together with "outside" would be representable. Rejected for the sealed type.
- **Show the notes of the outside file by its absolute path.** Notes are keyed by a path relative to the project, so there are none to show. Rejected.

## Consequences
- Pros
  - The empty state names the real reason.
- Cons and what we accept
  - Two more bundle keys.
- Tests
  - `ToolWindowStateTest`: an outside file matches nothing and shows the empty state; existing cases use `CurrentFile`.
  - `CurrentFileFilterTest.testFileOutsideTheProjectShowsItsOwnMessage`: a file opened from a folder outside the project shows the new message.
