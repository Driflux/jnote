# 0020. Convenience features and concurrent access

- Status: Proposed
- Date: 2026-11-27
- Issue: #20
- PRs: #79 copy snippet and keyboard control, then current file toggle, syntax highlighting, refresh on focus, AI notes filter (stacked)

## Context
The Tool Window lists, filters, searches, and shows notes (ADR 0006, 0010, 0011, 0062). The 1.0 usability list in intent.md section 7 still lacks copying a snippet, keyboard control, a current-file view, highlighted snippets, a refresh for multi-window use, and an AI filter. Every one of them must keep the top principle: no startup work, no DB or file I/O on the EDT, no new dependency.

## Decision
- **Copy snippet.** A "Copy Snippet" button in the detail panel, shown when the note has a snapshot. It puts the stored snapshot on the clipboard through `CopyPasteManager`. It reads nothing, since the detail already holds the full note (ADR 0062).
- **Keyboard control.** Three bindings, all active only while the panel has focus, so they never collide with the editor.
  - Find (the platform's find shortcut) focuses the search box and selects its text. It is a `DumbAwareAction` registered on the panel itself, not in plugin.xml, so it only listens while focus is inside the panel and never collides with the editor's find.
  - Enter on the list presses the detail's "Go to Code" button. Delete presses its "Delete" button.
  - Pressing the detail's own buttons keeps one code path (navigation with recovery, delete with undo). The detail is cleared the moment the selection changes, so a key pressed while the next note loads does nothing instead of acting on the previous note. Hidden buttons are skipped, so Enter on a free note does nothing.
  - While a mouse drag is still adjusting the selection, the list ignores the change and the previous detail stays visible. A key pressed then must not act on it, so a key acts only when the selected row's uid equals the uid of the detail on screen (`shownDetailUid`). Found in the review of PR #79 and covered by a test that sets `valueIsAdjusting`.
- **Current file only.** A check box that filters the already-loaded rows in memory: CODE notes of the current project whose `rel_path` equals the file selected in the editor. It needs no DB read. The panel listens to editor selection changes only while it exists. With nothing open, no note matches and the empty state says so.
- **Syntax highlighting.** The snippet is shown in a read-only editor that is created when a note with a snippet is selected, gets the highlighter of the note's file type (from the extension), and is released when the selection changes or the panel is disposed. No editor exists while nothing is selected, so the list costs nothing. The file type is looked up by file name only, never by reading the file.
- **Refresh on focus.** One reload when keyboard focus moves into the panel from outside it, to see changes other IDE windows wrote. A focus listener lives only as long as the panel and compares the old and new focus owner. Moving focus between the list and the search box does not reload. There is no timer and no polling.
- **AI notes only.** A check box that keeps rows with an AI source, filtered in memory like the kind filter. It combines with the kind, tag, search, and current-file filters.

## Alternatives considered
- Copy from the DB at click time: the detail already holds the note, so a read adds a thread hop for nothing. Rejected.
- Bind Enter and Delete to new code paths in the panel: it duplicates navigation and deletion and lets a key act on a stale selection. Rejected.
- Rely only on the detail being cleared when the selection changes: it is not cleared while a drag is adjusting the selection, so the uid comparison is also needed. Rejected as insufficient.
- A `ToolWindowManagerListener` or `ApplicationActivationListener` for refresh: the first misses focus changes inside the window, and the second would have to be registered in plugin.xml, which is startup work. A listener created with the panel is not. Rejected.
- Polling or a file watcher on the DB: violates "no polling" and costs idle CPU. Rejected.
- Always keep one editor for the snippet: an editor is expensive and the intent is "created only on selection". Rejected.
- Load notes of the current file with a SQL filter: the list is already in memory and small, and a second query path would diverge from search and tag filtering. Rejected.

## Consequences
- Pros
  - All five features reuse loaded data or existing paths. None adds startup work, EDT I/O, or a dependency.
  - Several IDE windows see each other's notes after one focus.
- Cons and what we accept
  - A key pressed before the new detail has loaded, or during a mouse drag on the list, is ignored.
  - Focusing the panel from the editor reloads each time. The reload is a summary query (ADR 0062), so the cost is small, and it is measured in the refresh PR.
  - Highlighting needs the editor machinery, so selecting a note with a snippet does a little more EDT work than a text area. It is measured in the highlighting PR.
- Follow-up work
  - Copying selected notes as Markdown or JSON belongs to the import and export issues.
