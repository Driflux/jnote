# 0116. Decide the activation refresh by the focus owner the window will restore

- Status: Proposed
- Date: 2026-11-27
- Issue: #116
- PR: linked from issue #116

## Context
The Tool Window rereads the notes when the IDE is activated and focus is inside JNote (ADR 0020), so notes written by another IDE process show up. `applicationActivated` can arrive before AWT restores the focus owner. Then `KeyboardFocusManager.focusOwner` is null, the panel decides focus is not inside it, and nothing reloads. The list stays stale until focus leaves and re-enters the panel.

## Decision
`onApplicationActivated` decides with the first non-null of three owners:
1. the current focus owner;
2. the activated window's `mostRecentFocusOwner`, the component AWT gives focus to when the window is focused again (the window is the frame component itself when it is a `Window`, or its window ancestor);
3. `KeyboardFocusManager.permanentFocusOwner`.

AWT (`DefaultKeyboardFocusManager`) sets the focused window first and then asks the most recent owner to take focus. Between the two steps the window is focused, so `Window.mostRecentFocusOwner` returns the window's focus owner, which is still null. A switch to another application is a temporary focus loss, which does not clear the permanent focus owner, so step 3 still names the component that is about to get focus back. A non-null owner outside the panel at an earlier step wins, so returning to the editor does not reload.

## Alternatives considered
- **Re-check the focus owner later** with `invokeLater` or `IdeFocusManager.doWhenFocusSettlesDown` (the issue's suggestion). One `invokeLater` may still run before the native focus events arrive, and `doWhenFocusSettlesDown` runs at once in the headless test focus manager, so neither is testable without depending on timing. `mostRecentFocusOwner` answers the question directly, without waiting. Rejected.
- **Only `permanentFocusOwner`.** It is the last permanent focus owner of the whole application, which can belong to another IDE frame than the one activated. It is kept as the last fallback, after the activated window's own most recent owner. Rejected as the only source.

## Consequences
- Pros
  - The refresh no longer depends on whether the activation callback runs before or after AWT restores the component focus.
- Cons and what we accept
  - If the restored component differs from `mostRecentFocusOwner` (for example a focus request queued during activation), the decision follows `mostRecentFocusOwner`. The next focus entry into the panel reloads anyway.
  - With two project frames, if the user last focused frame B and then activates frame A in the gap described above, step 3 names a component of frame B and frame A's JNote does not reload. The next focus entry into the panel reloads.
  - The window lookup (root pane to window to `mostRecentFocusOwner`) is not covered by tests, because the tests run headless and cannot create an AWT window. Not checked in a running IDE by switching applications.
- Tests
  - `FocusRefreshTest`: activation with no focus owner reloads when the restored owner is in the panel and does not reload when it is outside; with neither, the permanent owner decides; an earlier owner outside the panel wins over a later one inside it.
