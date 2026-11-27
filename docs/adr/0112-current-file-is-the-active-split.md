# 0112. Current file is the file of the active split

- Status: Proposed
- Date: 2026-11-27
- Issue: #112
- PR: linked from issue #112

## Context
The Tool Window's "Current file" filter took `FileEditorManager.selectedFiles.firstOrNull()`. `selectedFiles` holds the selected tab of every split, and its order lags behind the active split: right after the user moves from the split with B to the split with A, the array can still start with B. The filter then showed B's notes while the user looked at A.

The issue said this could not be reproduced in the light fixture, because `BasePlatformTestCase` uses `TestEditorManagerImpl`, which has no splits. `FileEditorManagerTestCase` (in the platform test framework) installs the real `FileEditorManagerImpl` in a light fixture, so `EditorWindow.split` and `setAsCurrentWindow` work there. With it, the old code showed B's notes after activating A's split.

## Decision
Read the file of `FileEditorManager.selectedEditor`, the editor of the active split, instead of the first entry of `selectedFiles`.

## Alternatives considered
- **`selectedTextEditor`.** It is null for a non-text editor such as an image, which would show "No file is open" while a project file is open. `selectedEditor` also returns non-text editors, and an image editor returns the image's own file. Rejected.
- **Keep `selectedFiles` and pick the entry of `currentWindow`.** `currentWindow` is on `FileEditorManagerEx`, an internal API, and `selectedEditor` already returns that window's editor. Rejected.

## Consequences
- Pros
  - The filter follows the split the user works in.
- Cons and what we accept
  - The filter works only for a file that has a path relative to the project. A diff tab returns a synthetic file, so it does not match any note. This was already so before this change.
  - With a detached editor window, the platform finds the active splitters from the focused or active window. When focus moves from a detached window to JNote in the main window, `selectedEditor` can return the main window's editor. Not checked in a running IDE.
- Tests
  - `ActiveSplitFileTest` (on `FileEditorManagerTestCase`): two splits with A and B; after activating A's split, turning the filter on shows A's notes (failed on the old code), and the list follows a later switch to A's split.
