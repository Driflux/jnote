# 0117. Remember where focus was across a null focus owner

- Status: Proposed
- Date: 2026-11-27
- Issue: #117
- PR: linked from issue #117

## Context
The Tool Window rereads the notes when focus enters JNote from outside (ADR 0020). `FocusEntry.entered(panel, old, new)` compared only the two owners of one `permanentFocusOwner` change. A move inside the panel can be reported as two changes, `list -> null` and then `null -> searchField`. The second one looked like an entry from nowhere, so the panel read the database again although focus never left. The cost is one extra summary query, with no wrong result.

## Decision
`FocusEntry` becomes a small class per panel that remembers whether the last non-null owner was inside the panel. When the old owner is null, that remembered value stands in for it. A change to a null owner does not update the memory. With a non-null old owner the result is the same as before.

## Alternatives considered
- **Compare the focus event's opposite component.** The panel listens to the `permanentFocusOwner` property, which gives no transfer source, and adding a `FocusEvent` listener on every component costs more. Rejected.
- **Track only the new owner and ignore the old one.** Simpler state, but it changes the result for every sequence the panel sees, and the existing tests that start with focus already inside would need a different setup. Rejected for the smaller change.

## Consequences
- Pros
  - An in-panel focus move no longer reloads, whatever path AWT reports.
- Cons and what we accept
  - The panel keeps one boolean of focus state. It starts as "outside", so the first entry after the panel opens still reloads.
- Tests
  - `FocusEntryTest`: an inside move through a null owner is not an entry; coming in from outside through a null owner is an entry; the existing cases are unchanged.
  - `FocusRefreshTest.testAnInPanelMoveReportedThroughNoOwnerDoesNotReload`: `list -> null -> searchField` starts no reload.
