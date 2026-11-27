# 0023. Gutter interaction model and its per-editor listener cost

- Status: Proposed
- Date: 2026-11-27
- Issue: #23
- PR: #TBD

## Context
Issue #23 makes adding and previewing notes as direct as GitHub review comments (intent.md section 7). This ADR starts with the first part: a "+" button that follows the mouse in the gutter, a click that adds a note for that line, and a drag that selects a line range and opens the add dialog with it. The range bar (PR 2) and the hover preview (PR 3) add their own sections to this ADR.

The only new always-on cost is a mouse listener per editor (intent.md section 2). ADR 0009 already ruled out an `EditorFactoryListener` and anything declared in `plugin.xml` for tracking, because both run for every editor from startup. The add dialog and the saving path already exist (ADR 0005), and `CodeAnchors.fromLines` already builds an anchor from a line range.

## Decision
- **Where the listener attaches.** The marker pass of `NoteLineMarkerProvider` calls `GutterNoteService.attachGutterInteraction(document)` for project files, before the cache lookup, so a file with no notes gets the "+" too. `GutterAddNoteAttacher` finds the document's editors with `EditorFactory.editors(document, project)` and takes only `MAIN_EDITOR` and `UNTYPED` kinds (the test fixture creates `UNTYPED` editors; diff, console and preview editors are skipped). Each editor is claimed once with `putUserDataIfAbsent`, so two passes at the same time cannot attach twice, and later passes cost one user-data lookup. The listener itself is added on the EDT. Files outside the project (relative path is null) never reach the call, so those editors get no listener.
- **Lifetime.** The per-editor disposable is registered with the editor (`EditorUtil.disposeWithEditor`), and `GutterNoteService` holds one callback that disposes it. Disposing a child removes it from the editor's tree, so unloading the plugin removes every listener and leaves no plugin class registered on a live editor. Disposing removes the listeners and the "+".
- **Gutter width.** In the New UI the icon column of a file with no icons has width 0 (measured in the 2024.2 test environment: line numbers at 16, icon column at 32, width 0, growing to 18 once a renderer exists and not shrinking back). So that the first "+" does not push the code right, the attacher calls `setInitialIconAreaWidth` with the width of the "+" column. The column is then always reserved in editors that have the listener, until the editor is reopened after the plugin is unloaded.
- **The "+" icon.** One line highlighter per editor carries a `GutterIconRenderer` (`AddNoteGutterRenderer`, left aligned, so it sits beside the note icon, which is right aligned). `mouseMoved` over the line numbers and the icon column (from the end of the annotation area to `iconAreaOffset` plus the larger of `iconsAreaWidth` and the "+" column width) moves it to the hovered line; it is only re-created when the line changes. Leaving the gutter, leaving the icon area, or hovering below the last line removes it. The line comes from `EditorUtil.yPositionToLogicalLine`, the same conversion the gutter uses for its own clicks. A click is handled by the gutter through the renderer's click action, which reads the line from the highlighter at click time, so edits above the line cannot leave the click on a stale line.
- **Drag.** A press on the "+" (hit-tested with `getGutterRenderer(point)`) starts a drag, so pressing on a note icon or on the line numbers never does. While dragging, the editor selection follows the lines from the press line to the pointer line (clamped to the document), which is the visual feedback. Releasing on another line opens the add dialog for that inclusive range; releasing on the press line is a click and adds nothing here. The state is a small pure class (`GutterDrag`) with 0-based lines. The "+" does not move during a drag. Only the left button counts. Reading the 2024.2 gutter showed that on release it runs the click action of the icon under the pointer without comparing it with the press position, so a range drag released over another line's note icon would also open that note. The listener therefore marks the gutter component while a drag from the "+" is active, and the note icon's navigation handler ignores clicks while the mark is set. The gutter's release handler runs before ours, so the mark is still set when it matters and is cleared at the end of our release handler. Dragging back to the press line removes the selection the drag made.
- **Adding.** `AddNoteAction.addNote(project, draft)` is the existing flow (non-blocking read action for the class name, then the dialog, then `NoteSaver`). The listener builds the draft on the EDT with `CodeAnchors.fromLines`, which reads only the document. The context menu and the shortcut are untouched.
- **No I/O on the EDT.** The listener reads the document and the editor only. No DB or file access happens in any mouse handler.
- **Cost.** Moving the "+" replaces a highlighter, which makes the platform rebuild its gutter renderer cache over the document's highlighters on the EDT. The measurements, including that update with 500 other highlighters, are in the PR body (`GutterHoverCostTest`, `JNOTE_PERF=1`).

## Alternatives considered
- `EditorFactoryListener` registered in `plugin.xml`: attaches in every editor at creation, which is startup work and is what ADR 0009 rejected. Rejected.
- Attaching from the marker pass only for files that have notes: the "+" would not appear where a first note is added. Rejected.
- `EditorMouseListener` and `EditorMouseMotionListener` on the editor: whether gutter events reach them depends on the area, and the gutter's own renderer handling then has to be duplicated for clicks. Listening on the gutter component and letting it run the renderer's click action avoids both. Rejected.
- Handling the click ourselves on release: a press on a note icon would then also add a note, because we cannot tell which icon was hit without the same hit test. Rejected for the renderer's click action.
- `EditorGutterComponentEx.isInsideMarkerArea`: it is internal and measures the free-painter area in the new UI, not the icon column. Rejected for `iconAreaOffset` and `iconsAreaWidth`.
- Moving the "+" while dragging: the click and the drag start would then refer to different lines. Rejected.

## Consequences
- Pros
  - No startup work, no new dependency, no EDT I/O. Per event the listener converts one y coordinate and compares a line.
  - Files with no notes pay one user-data lookup per marker pass after the first.
- Cons and what we accept
  - The icon column is reserved (about 18 px in the New UI) in every project editor once the first marker pass has run, which shifts the code once after opening a file that had no icons.
  - The "+" appears only after the daemon has run a marker pass on the file, so an editor with highlighting turned off for that file has no "+" (the context menu and the shortcut still work).
  - `getIconAreaOffset`, `getIconsAreaWidth` and `getGutterRenderer(Point)` are public platform API, but the layout they describe differs between the classic and the new UI. The gutter geometry could not be checked in a headless test, so it is covered by the `runIde` check in the PR.
  - A drag selects text in the editor, and the selection stays after the dialog closes.
- Follow-up work
  - PR 2 adds the range bar and PR 3 adds the hover preview to this ADR.
