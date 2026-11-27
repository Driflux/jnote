# 0062. Load a summary projection for the Tool Window list

- Status: Proposed
- Date: 2026-11-27
- Issue: #62
- PR: #68

## Context
The Tool Window list shows a title, a subtitle, the project name, tags, and an AI badge. `JNotePanel.load` used `NoteRepository.list`, which selects all 17 columns, including `snapshot` (up to 65,536 characters), `body`, and `prompt`. Every reload (first open, every `NotesChanged`, every all-projects toggle) read and allocated that text for every row only to show a few short strings. ADR 0003 accepts "no paging" but not the cost of the text columns. The top principle is that the plugin never slows the IDE (intent.md section 2), and a list that grows with the note count must stay cheap.

## Decision
- **`NoteSummary`.** A small data class with id, uid, kind, project, relPath, className, startLine, endLine, status, title, aiSource, and updatedAt. These are exactly the fields the row (subtitle, project label, AI badge, kind filter) and the selection logic need.
- **`NoteRepository.listSummaries(project, allProjects)`.** Same rows and same order as `list`, but selects only those columns. `list` stays for callers that need whole notes. The panel no longer calls it.
- **Rows carry the summary.** `NoteRow.summary` replaces `NoteRow.note`. `NoteFilter.accepts` takes a `NoteKind` because the kind is all it needs.
- **The detail reads one full note.** Selecting a row calls `NoteRepository.get(id)` through the database executor and shows the result with `invokeLater`. A generation counter drops a note that arrives after the selection changed, so a slow read never overwrites a newer one.
- **Switching clears, and reads are checked.** Selecting a different note hides the old detail at once, so its buttons cannot act on the wrong note while the new one loads. A read result is shown only when its uid equals the selected row's uid, and the detail is kept across a reload only when the selected row has the same uid as the note on screen. The list can be older than the database (a path switch, or an INTEGER PRIMARY KEY id reused after a delete), and a bare id would then show another note under the selected row.
- **Reloads refresh the detail.** After a reload, the selected row is kept (matched by uid, not by id, so a reused id never inherits the old selection or its buttons) and its full note is read again, so the detail shows the latest text (for example after an edit or an AI import). While `render()` swaps the list model the selection listener is muted, so the detail does not blink off and on during a reload. If the selected note disappeared, the detail is cleared.
- **Concurrency.** Both reads use the single-thread executor. The detail read is one statement, so it needs no transaction. Results are applied on the EDT behind the same disposed and generation checks as the list load. In-flight detail reads count toward `isIdle`, which tests use to drain reads before switching the database file.

- **Reasons kept from the removed code comments.**
  - `NoteSummary` leaves out snapshot, body, and prompt on purpose, so the full note is read separately with `get`.
  - The generation counter exists so that a late read from an earlier selection cannot overwrite a newer detail.
  - The detail is cleared before the new read finishes so the previous note's buttons cannot delete the wrong note.
  - `renderingSelection` and `shownDetailUid` are touched on the EDT only.
  - The selection listener is muted inside `render()` because swapping the list model shakes the selection.
  - A kept detail is re-read after a reload so it shows the latest stored text.
  - A request to select a note that is not in the list (another project) leaves the selection and the pending request alone.
  - The project key is resolved on a pool thread because it reads git config files.
  - Tests block the database executor to hold reads back, and close their panels before changing the database path so late reads do not open the wrong database.

## Alternatives considered
- Keep `list` and drop the big columns from the row type only: the SQL would still read and allocate the text, so it saves nothing. Rejected.
- Add a LIMIT or paging: the issue is the per-row cost, not the row count, and paging changes search and filter behavior. Rejected.
- Cache full notes in the panel: the cache would go stale against other IDE processes that share the database file, and it needs invalidation rules. One indexed read per selection is cheap. Rejected.
- Make `Note` fields lazy: it hides I/O inside a data class and breaks the plain value semantics the tests and the gutter rely on. Rejected.

## Consequences
- Pros
  - The list reload no longer scales with snapshot, body, and prompt size. Measured (3,000 notes, median of 7 runs after warmup, `NoteListBenchmarkTest`, run with `JNOTE_PERF=1`): 60 ms to 16 ms at 2,000-character snapshots, 143 ms to 18 ms at 8,000, and 514 ms to 49 ms at 65,536.
  - The detail always shows the stored text, not the text from the last list load.
  - After a database switch or a delete-and-recreate that reuses an id, a reload clears the old detail at once (`testReloadClearsTheDetailWhenTheSelectedIdWasReusedByAnotherNote`).
- Cons and what we accept
  - Selecting a row costs one extra read, so the detail appears a few milliseconds after the click.
  - Two near-identical column lists (`COLUMNS` and `SUMMARY_COLUMNS`) must be kept in step with the schema by hand. A repository test compares the summary with the full note.
- Follow-up work
  - Issue #65 neutralizes HTML in the labels that show note text. The convenience features in issue #20 build on the summary rows.
