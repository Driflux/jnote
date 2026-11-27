# 0117. Remember where focus was across a null focus owner

- Status: Proposed
- Date: 2026-11-27
- Issue: #117
- PR: linked from issue #117

## Context
The Tool Window rereads the notes when focus enters JNote from outside (ADR 0020). `FocusEntry.entered(panel, old, new)` compared only the two owners of one `permanentFocusOwner` change. A move inside the panel can be reported as two changes, `list -> null` and then `null -> searchField`. The second one looked like an entry from nowhere, so the panel read the database again although focus never left. The cost is one extra summary query, with no wrong result.

## Decision
`FocusEntry` becomes a small class per panel that remembers whether focus was last known to be inside the panel. A non-null owner, old or new, sets that memory; when the old owner is null, the memory stands in for it, and a change to a null owner keeps what the old owner said. With a non-null old owner the result is the same as before.

## Alternatives considered
- **Compare the focus event's opposite component.** The panel listens to the `permanentFocusOwner` property, which gives no transfer source, and adding a `FocusEvent` listener on every component costs more. Rejected.
- **Track only the new owner and ignore the old one.** Simpler state, but it changes the result for every sequence the panel sees, and the existing tests that start with focus already inside would need a different setup. Rejected for the smaller change.

## Consequences
- Pros
  - An in-panel focus move reported through a null owner no longer reloads.
- Cons and what we accept
  - The panel keeps one boolean of focus state. It starts as "outside", so the first entry after the panel opens still reloads.
  - A real loss of focus inside the panel (the focused component is removed, or focus is cleared) followed by a click back into the panel looks the same as an in-panel move through null, so it does not reload. Notes written meanwhile by another IDE process show up on the next entry from outside or the next activation.
- Tests
  - `FocusEntryTest`: an inside move through a null owner is not an entry; coming in from outside through a null owner is an entry; a first report from inside or outside to null is remembered; the existing cases are unchanged.
  - `FocusRefreshTest.testAnInPanelMoveReportedThroughNoOwnerDoesNotReload`: `list -> null -> searchField` starts no reload.
