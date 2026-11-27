# 0065. Neutralize HTML in labels that show note text

- Status: Proposed
- Date: 2026-11-27
- Issue: #65
- PR: (this PR)

## Context
Swing renders a label or button text that starts with `<html>` as HTML. Note titles, class names, AI sources, and tag names reach the UI from the user and, with the planned JSON import, from web AIs, so they are untrusted. A crafted title would be drawn as markup, and Swing's HTML view can load `<img>` URLs. Tooltips and notifications already escape text. The plain label paths did not.

## Decision
- **`JComponent.plainText()`.** Sets the `html.disable` client property. It is applied to every label or button that shows note text: the row title, subtitle, tags and project, the detail title, class, location, AI source and URL, and the tag chips (dialog and filter bar).
- **Set before the text.** A button built with its text in the constructor installs the HTML view before the property exists. `tagChip` therefore creates the button empty, sets the property, then sets the text.
- **`plainPopupText`.** The gutter chooser is a list popup whose renderer we do not own. Text that Swing would read as an HTML document gets a zero-width space in front. Other titles are unchanged.
- **Tests per surface.** `PlainTextTest` renders each surface with a markup title and asserts that no HTML view (the `html` client property) is installed.

## Alternatives considered
- Escape the text with `StringUtil.escapeXmlEntities`: it only works when the label is HTML, and a label that is not would show the entities literally. Rejected.
- Wrap every text in `<html>` ourselves and escape it: this opts every label into the HTML view and its cost. Rejected.
- Replace the popup renderer: it couples the chooser to the platform's renderer internals. A one-character prefix on the rare unsafe title is cheaper. Rejected.
- Strip or reject titles that start with `<html>`: it changes stored data and hides the note's real text. Rejected.

## Consequences
- Pros
  - A title such as `<html><img src=...>` is shown as the literal text on every surface.
  - New surfaces have one obvious call to make.
- Cons and what we accept
  - A new label that shows note text must remember `plainText()`. Reviewers check this by hand.
  - The popup title of an HTML-looking note carries an invisible leading character.
