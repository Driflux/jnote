# 0010. Tag UI and dictionary management

- Status: Proposed
- Date: 2026-11-26
- Issue: #10
- PR: #55

## Context
Tags are a registered dictionary (intent.md section 5). The repository from #3 already stores them (`tag`, `note_tag`, `TagRepository`). #10 adds the UI: pick and type tags in the note dialog, filter the Tool Window by tag, and rename or delete tags. intent.md section 2 applies in full: no startup work, no DB access on the EDT, no new dependency.

## Decision
### Dialog chips and auto registration (PR 1)
- **The draft carries the whole tag set.** `NoteDraft.tags` is the list of tag names the note should have. Saving makes the note's tags exactly that list, so removing a chip in the edit dialog removes the tag from the note. A tag removed from a note stays in the dictionary; only the delete action in PR 3 removes it from the dictionary.
- **One repository call per save.** `TagRepository.replaceTags(noteId, names)` trims the names, drops blanks and duplicates, creates the missing tags, and sets the note's links in one transaction. A failure (comma in a name, missing note) rolls back, so no half-registered dictionary entry is left behind. Names stay case-sensitive and unique, as in #3.
- **Create then tag.** `NoteSaver.save` and `saveFree` create the note first and then call `replaceTags` (skipped when the draft has no tags). `update` always calls it, because an empty list means "remove all". These are two transactions, so if the tag step fails the note exists without its tags and the saver reports the failure. We accept this because a single shared transaction would make `NoteRepository.create` take tag arguments for one caller.
- **Input rules.** `TagSelection` is a plain class that owns the rules: commas separate tags, names are trimmed, and duplicates collapse. Typing `a, b` and pressing Enter adds two chips. Text left in the field when the dialog is confirmed is committed as a tag, so it is not lost silently.
- **Chips.** `TagChipsPanel` shows the dictionary plus the already chosen tags as toggle chips on a `WrapLayout`, and a text field for new names. A chip of a new name that is toggled off disappears, because it is not in the dictionary. The chip is a plain `JToggleButton`, which keeps keyboard focus and screen-reader behavior for free.
- **Loading.** The dictionary is read through `TagRepository.list()` on the DB thread when the dialog opens, and chips are added on the UI thread when it arrives. A failed read only means no suggestions; typing still works. The tags of a note being edited are read by `JNotePanel.edit` off the EDT before the dialog opens. The dialog constructor takes the dictionary as a future that defaults to an empty one, so tests and previews never touch the real database.
- **Alternatives rejected.**
  - A combo box with free text and a tag list below: two controls for one idea, and no at-a-glance view of the dictionary.
  - Registering the tag when the user presses Enter: leaves a dictionary entry behind when the dialog is then cancelled. Registration happens at save only.

### Tag filter chips (PR 2)
- **Where.** A chip bar under the Tool Window header lists the whole dictionary, including tags no note uses yet, so a new tag can be seen before it is applied. The bar is hidden while the dictionary is empty.
- **One read.** The list load now also reads `TagRepository.list()` and `TagRepository.assignments()` (every note-to-tag link, grouped by note) next to `NoteRepository.list`, all on the DB thread, in the same load that already runs for a reload. Each `NoteRow` carries its tags, so toggling a chip filters rows already in memory and does not touch the DB. The row also shows its tags in a small line.
- **AND semantics.** With several chips on, a note must have every chosen tag. Chips narrow the list, the same way the kind filter does, and the two filters combine. OR was rejected because it makes the list grow when the user adds a chip, which reads as the opposite of "filter".
- **State.** `ToolWindowState` holds the dictionary and the chosen tag ids, and `visibleNotes` applies both filters, so the rules are unit-tested without Swing. Chosen ids survive a reload. A tag that no longer exists in the freshly read dictionary (deleted elsewhere) is dropped from the chosen set, so a stale chip cannot hide every note.
- **Empty list.** When the tag filter hides everything, the empty text says that no note has the selected tags and tells the user to turn a chip off, instead of the "no notes yet" guidance.
- **Cost.** `assignments()` reads all links, not only those of listed notes. For a personal note count this is one small query; scoping it to the project is not worth a second query shape.

### Rename and delete (PR 3)
- **Entry point.** Right-clicking a tag chip in the Tool Window opens a menu with "Rename Tag..." and "Delete Tag...". The click does not toggle the chip. This follows intent.md section 5.
- **Rename.** A text input dialog asks for the new name. `TagNameRules.check` is the pure rule set, the same as the repository's (trimmed, no comma, unique and case-sensitive), and the dialog blocks a bad name with an error under the field. Keeping the current name is allowed. The write is `TagRepository.rename` off the EDT. A `Conflict` result that still comes back (another process created the name in between) is shown as a warning notification, and nothing changes. Because notes link to tags by id, one row update renames the tag on every note.
- **Delete.** A confirmation dialog states that the tag is removed from every note and the notes are kept. Confirming calls `TagRepository.delete`, which removes the tag and its links through the foreign-key cascade, never a note. There is no undo, unlike deleting a note, because the confirmation already covers it and a tag carries no content.
- **Refresh.** Both operations publish `NotesChanged` on success, so every open panel and the gutter cache re-read the new names. A chosen filter chip keeps following a renamed tag, because the filter is held by id. A deleted tag's chip is dropped by the rule from PR 2.
- **Testability.** The two dialogs are constructor parameters of `JNotePanel` with real dialogs as defaults, so tests answer them without a UI. The menu is built by `tagMenu(tag)`, which the tests use directly.
- **Rejected.** A delete notification with undo: it would need to snapshot every link of the tag, and the confirmation makes it redundant.

## Consequences
- Pros
  - Auto registration is atomic and idempotent; two IDE processes saving the same new tag do not fail.
  - The dialog needs no DB access on the EDT.
- Cons and what we accept
  - A dictionary with hundreds of tags fills the dialog with chips. A personal dictionary is expected to stay small; a search field over the chips is follow-up work if it does not.
  - Saving a note and its tags is not one transaction (see above).
