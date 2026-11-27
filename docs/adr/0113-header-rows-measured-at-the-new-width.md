# 0113. Measure the wrapping header rows at the width they will get

- Status: Proposed
- Date: 2026-11-27
- Issue: #113
- PR: linked from issue #113

## Context
The Tool Window header has two `WrapLayout` rows: the filter row (All projects, Current file, AI notes only, kind filter) beside the spinner, and the tag chip bar. `WrapLayout` computes its preferred height from the container's current width. When the Tool Window narrows, the outer `BorderLayout` asks for the header height before the rows receive the new width, so the height is measured at the old, wider width. The rows then lay out at the new width, wrap to more lines than the height allows, and the extra lines are cut off. They stay cut off until something else triggers a layout.

A headless test that lays out the panel once from the top, as one Swing validation does, reproduced it for both rows: after narrowing from 2000 to 160 pixels the header area got a negative height for its content.

## Decision
`JNotePanel.doLayout()` gives both rows the width they will receive before `BorderLayout` asks for preferred sizes: the panel width minus the insets of the panel and the filter bar, and for the filter row also minus the header insets and the spinner width when the spinner is visible. One layout pass then measures and places the rows at the same width.

## Alternatives considered
- **Revalidate on resize** (a `ComponentListener` that calls `revalidate()` when a row's preferred height differs from its height). It needs a second layout pass, so the clipped frame is still painted once, and it cannot be checked headlessly because `revalidate` does nothing for a component that is not showing. Rejected.
- **Camick's `WrapLayout` trick** of validating the top-level container from `layoutContainer`. Revalidates the whole window from inside a layout. Rejected.
- **Make the header a single non-wrapping row with a horizontal scroll.** Changes the agreed header design. Rejected.

## Consequences
- Pros
  - The rows are no longer cut off at the bottom because their height was measured at the old width, also on the first frame after a resize.
- Cons and what we accept
  - Only the vertical clipping from the stale width is fixed. A single control wider than the whole row is still cut off horizontally, as before.
  - `doLayout` knows the header structure (filter bar, header, spinner). A change to that structure must update the width calculation. The test fails when they disagree.
- Tests
  - `HeaderLayoutTest`: narrowing from 2000 to 160 pixels wraps the filter row and the tag bar, and every component in the filter bar fits vertically inside its parent after one layout pass. Both failed before the change. A sweep from 120 to 700 pixels with the spinner shown and then hidden checks the spinner width; it fails when the spinner is not subtracted.
