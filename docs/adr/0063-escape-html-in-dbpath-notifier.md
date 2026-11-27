# 0063. Escape HTML in DbPathNotifier notification content

- Status: Proposed
- Date: 2026-11-27
- Issue: #63
- PR: #83

## Context
Notification content is rendered as HTML. `AddNoteNotifier` and `ToolWindowNotifier` already escape user data and exception messages. `DbPathNotifier` put file paths (the existing target, the unusable target, the new path in the close-failed message) and raw exception messages into the content as they were. The user can choose any DB path in the settings page, so `<` and `&` are possible in real paths and messages, and would be shown wrongly or read as markup.

## Decision
- **Escape at the bundle call.** Every value passed to `JNoteBundle.message` is escaped with `StringUtil.escapeXmlEntities`: the path of `FileAlreadyExistsException`, the unusable target path, the new path and the cause in the close-failed message, and every exception message (`reason`).
- **A null file name still shows.** `FileAlreadyExistsException` can carry no file name. The path goes through `value.toString()`, so it shows "null" as before instead of failing.
- **Tests per message.** `DbPathNotifierTest` checks the generic failure, the existing target, the unusable target, and the close failure (path and cause), like `AddNoteNotifierTest`. Test paths built with `Path` avoid `<` and `>` so they also run on Windows. `DbPathChangerTest` expects escaped paths in its target-exists and close-failed assertions, so it passes when the temp directory contains `&` or `'`.
- **Reasons kept from removed source comments.**
  - A target file that already exists is never overwritten, so the message names that file as the cause and does not point at write permission.
  - The detailed reason SQLite gives for an unusable target is not something the user can fix, so it goes to the log only.
  - When the close fails the path has already changed, so the message says the new location is in use instead of the failure text "the old database stays in use".
  - Notification content is drawn as HTML, so `<` and `&` in paths and exception messages must not be read as tags.

## Alternatives considered
- Escape inside `show`: the bundle text itself contains no markup today, but a later message with markup would be broken by it, and escaping must apply to the values only. Rejected.
- Strip markup characters from paths: it changes what the user sees and hides the real path. Rejected.

## Consequences
- Pros
  - A path or message with `<` or `&` is shown as typed.
  - The three notifiers now follow one rule.
- Cons and what we accept
  - A new message in `DbPathNotifier` must call `escaped` on each value by hand. Reviewers check this.
