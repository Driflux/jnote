# 0010. Tag UI and dictionary management

- Status: Proposed
- Date: 2026-11-26
- Issue: #10
- PR: (to be filled)

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

## Consequences
- Pros
  - Auto registration is atomic and idempotent; two IDE processes saving the same new tag do not fail.
  - The dialog needs no DB access on the EDT.
- Cons and what we accept
  - A dictionary with hundreds of tags fills the dialog with chips. A personal dictionary is expected to stay small; a search field over the chips is follow-up work if it does not.
  - Saving a note and its tags is not one transaction (see above).
