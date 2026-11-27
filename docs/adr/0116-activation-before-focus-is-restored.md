# 0116. Decide the activation refresh by the focus owner the window will restore

- Status: Proposed
- Date: 2026-11-27
- Issue: #116
- PR: linked from issue #116

## Context
The Tool Window rereads the notes when the IDE is activated and focus is inside JNote (ADR 0020), so notes written by another IDE process show up. `applicationActivated` can arrive before AWT restores the focus owner. Then `KeyboardFocusManager.focusOwner` is null, the panel decides focus is not inside it, and nothing reloads. The list stays stale until focus leaves and re-enters the panel.

## Decision
`onApplicationActivated` uses the current focus owner when there is one, and otherwise the activated window's `mostRecentFocusOwner`, the component AWT gives focus to when the window is focused again. The window is the frame component itself when it is a `Window`, or its window ancestor. A non-null focus owner outside the panel still wins, so returning to the editor does not reload.

## Alternatives considered
- **Re-check the focus owner later** with `invokeLater` or `IdeFocusManager.doWhenFocusSettlesDown` (the issue's suggestion). One `invokeLater` may still run before the native focus events arrive, and `doWhenFocusSettlesDown` runs at once in the headless test focus manager, so neither is testable without depending on timing. `mostRecentFocusOwner` answers the question directly, without waiting. Rejected.
- **Fall back to `permanentFocusOwner`.** It is the last permanent focus owner of the whole application, which can belong to another IDE frame. The activated window's own most recent focus owner is more precise. Rejected.

## Consequences
- Pros
  - The refresh no longer depends on the order of the activation and focus events.
- Cons and what we accept
  - If the restored component differs from `mostRecentFocusOwner` (for example a focus request queued during activation), the decision follows `mostRecentFocusOwner`. The next focus entry into the panel reloads anyway.
  - Not checked in a running IDE by switching applications.
- Tests
  - `FocusRefreshTest`: activation with no focus owner reloads when the restored owner is in the panel, does not reload when it is outside, and a focus owner outside the panel wins over a restored owner inside it.
