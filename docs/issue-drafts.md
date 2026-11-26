# Issue Drafts

> Drafts not yet registered on GitHub. Numbers are provisional; replace them with the real issue numbers once registered.
> Each issue is split into one or more PRs, and the first PR includes the ADR (`docs/adr`). Every feature PR includes tests (CONTRIBUTE.md).
> Steps follow the implementation order in intent.md section 8. Dependencies mostly follow the numbering.

## 1. Project skeleton and CI  `Step 1`

**Goal**: Build an empty plugin that builds, runs, and tests, plus the message bundle foundation.

**Done criteria**
- [ ] `./gradlew runIde` loads the empty plugin in the sandbox IDE
- [ ] `sinceBuild=242`, `untilBuild` unset, configured as a dynamic plugin
- [ ] `JNoteBundle` (English default, Korean) and a sample test pass
- [ ] `test` and `verifyPlugin` run in CI

**PR split**
1. Gradle skeleton + plugin.xml + empty plugin runs
2. JNoteBundle + CI

**ADR topic**: Plugin skeleton, supported range, dynamic plugin conditions

**Related**: intent.md section 3, 7

## 2. SQLite connection and schema migrations  `Step 1`

**Goal**: Load the DB lazily, access it from a single thread, and add versioning and backup.

**Done criteria**
- [ ] The DB opens only on first use (no startup work)
- [ ] WAL, `busy_timeout` 5 s, single-thread executor
- [ ] Sequential `user_version` migrations; create `jnote.db.bak` right before a version bump
- [ ] Apply schema v1 (`note`, `tag`, `note_tag`, indexes); FTS is added as v2 in the search issue
- [ ] Migration tests using a SQLite file in a temp directory

**PR split**
1. DB connection, executor, lazy loading
2. Migration runner + schema v1 + backup

**ADR topic**: DB access model and migration approach

**Related**: intent.md section 4

## 3. Note and tag repository (CRUD)  `Step 1`

**Goal**: Build the repository layer that stores and reads notes and tags.

**Done criteria**
- [ ] Create, read, update, delete notes (both CODE and FREE)
- [ ] Create tags, link/unlink them to notes, and test the policy for unused tags
- [ ] List by project, most recently modified first
- [ ] Pure unit tests (no IDE)

**PR split**
1. `Note` model + note CRUD
2. `Tag` + `note_tag` links

**ADR topic**: Repository layer structure and status model

**Related**: intent.md section 4, 5

## 4. Project key computation  `Step 1`

**Goal**: Derive the project key from the git origin URL so worktrees and clones share notes.

**Done criteria**
- [ ] Tests for URL normalization rules (https/ssh, `.git` suffix, case)
- [ ] Follow `commondir` from a worktree `.git` file and read config files (no process spawned)
- [ ] Fall back to the project path when there is no `origin` or no git
- [ ] Compute once in the background when the project opens and cache it

**PR split**
1. Normalization and config parsing (pure logic)
2. Project-open integration and cache

**ADR topic**: Project identification (origin URL)

**Related**: intent.md section 4

## 5. "Add note" action  `Step 2`

**Goal**: Save a note from code selected in the editor.

**Done criteria**
- [ ] Context menu and `Ctrl+Option+N` (changeable in Keymap)
- [ ] Collect selection range, relative path, snapshot, and class name (class-name extraction behind an interface, common PSI implementation)
- [ ] Dialog for title and note text (tags are added in the tags issue)
- [ ] Save in the background; UI strings go through the bundle

**PR split**
1. Anchor collection (relative path, snapshot, class-name interface)
2. Action + dialog + save

**ADR topic**: Anchor extraction interface and common PSI implementation

**Related**: intent.md section 7

## 6. Tool Window: list and detail  `Step 2`

**Goal**: View saved notes in a list and inspect details.

**Done criteria**
- [ ] Show current project notes most recent first, with an all-projects toggle
- [ ] Detail area (title, class, line, snippet, note text) and "Go to code"
- [ ] Edit and delete (delete without confirmation + undo notification)
- [ ] Empty-state guidance; spinner only after 300 ms
- [ ] Create the Tool Window lazily

**PR split**
1. Tool Window skeleton + list
2. Detail area + go to code
3. Edit
4. Delete + undo
5. Project toggle, empty state, delayed spinner

**ADR topic**: Tool Window layout and delete/undo approach

**Related**: intent.md section 5, 7

## 7. Free notes (queries, etc.)  `Step 2`

**Goal**: Create global notes not tied to code.

**Done criteria**
- [ ] "+ New note" button creates a `kind=FREE`, `project=NULL` note
- [ ] Visible in the list from any project
- [ ] Shown in monospace; filter (all / code / free)

**PR split**
1. Create free note
2. Filter (all / code / free)

**ADR topic**: Global note model

**Related**: intent.md section 4, 7

## 8. Gutter icon  `Step 3`

**Goal**: Mark lines that have notes in the editor.

**Done criteria**
- [ ] On file open, check via the in-memory cache; a file with no notes costs almost nothing
- [ ] Icon on the start line; APPROX uses a different icon; ORPHAN/NEEDS_RELOCATE draw none
- [ ] Tooltip with the title; click selects it in the Tool Window; chooser popup when several notes share a line
- [ ] SVG icons for light, dark, and New UI

**PR split**
1. Per-file note cache + icon + tooltip/click
2. Chooser popup for several notes + SVG themes

**ADR topic**: Gutter implementation and cache strategy

**Related**: intent.md section 7

## 9. RangeMarker position tracking  `Step 3`

**Goal**: Follow code edits while a file is open, and write back to the DB on close or save.

**Done criteria**
- [ ] Track the line range with a RangeMarker in the open document
- [ ] On close or save, write only changed line numbers to the DB in the background
- [ ] If an edit removes the range, mark the note as a relocation target (NEEDS_RELOCATE)

**PR split**
1. RangeMarker tracking
2. Write-back on close/save

**ADR topic**: Position tracking and persistence timing

**Related**: intent.md section 6

## 10. Tags  `Step 4`

**Goal**: Attach registered-dictionary tags to notes and manage them in the UI.

**Done criteria**
- [ ] Pick chips in the dialog; a newly typed tag is registered automatically
- [ ] Tag filter chips in the Tool Window; right-click to rename/delete (delete asks for confirmation and only detaches the tag from notes)
- [ ] Renaming applies to all notes

**PR split**
1. Dialog tag chips + auto registration
2. Tag filter chips
3. Rename/delete

**ADR topic**: Tag UI and dictionary management

**Related**: intent.md section 5

## 11. FTS5 trigram search  `Step 4`

**Goal**: Find note text, snippets, class names, and tags from one search box.

**Done criteria**
- [ ] Schema v2: `note_fts` (trigram) + sync-trigger migration
- [ ] Single `LIKE '%q%'` path, minimum 2 characters, tests for Korean particle cases
- [ ] Keep most-recent-first order while searching; no-result guidance
- [ ] Check response time with thousands of rows

**PR split**
1. Schema v2 + search query
2. Search box UI integration

**ADR topic**: Trigram search and unifying on LIKE

**Related**: intent.md section 5

## 12. Relocation core (pure logic)  `Step 5`

**Goal**: Build the algorithm that finds a snapshot in code again, independent of the IDE.

**Done criteria**
- [ ] Exact match after whitespace normalization; if several, the one closest to the original line
- [ ] Per-line similarity (about 80%, Levenshtein ratio) with candidate prefiltering (length, first token)
- [ ] Result type: match / approximate / none
- [ ] Unit tests (duplicates, line shift, partial edit, none)

**PR split**
1. Normalization + exact match
2. Similarity matching

**ADR topic**: Matching algorithm and threshold

**Related**: intent.md section 6

## 13. Relocation integration  `Step 5`

**Goal**: Validate notes when a file opens and update their status.

**Done criteria**
- [ ] Validate only that file notes on open, in the background
- [ ] Auto-update on exact match; APPROX on similarity; ORPHAN when not found
- [ ] Files over 5,000 lines skip similarity and become NEEDS_RELOCATE
- [ ] If the path is broken, search by class/file name with `FilenameIndex`
- [ ] APPROX clears and refreshes the snapshot once the user confirms or edits

**PR split**
1. Validate on open + status update
2. File path recovery
3. APPROX confirmation handling

**ADR topic**: When relocation runs and what is automatic

**Related**: intent.md section 6

## 14. Manual relocation and benchmark  `Step 5`

**Goal**: Provide manual relocation with a progress indicator, and measure performance.

**Done criteria**
- [ ] Manual relocation from context menu/button, no size limit
- [ ] Cancellable Background Task progress
- [ ] Benchmark 20 displaced notes in a 5,000-line file; adjust thresholds if the target is missed
- [ ] Attach measurements to the PR

**PR split**
1. Manual relocation + progress
2. Benchmark and threshold tuning

**ADR topic**: Manual relocation policy and measurement results

**Related**: intent.md section 2, 6

## 15. DB path setting  `Other`

**Goal**: Change the DB path in Settings > Tools > JNote.

**Done criteria**
- [ ] If no file exists at the new path, copy then switch; leave the old file
- [ ] If a file exists, switch after a confirmation dialog (no merging)
- [ ] Reopen the connection without a restart
- [ ] Errors show a notification with the reason and a fix

**PR split**
1. Settings UI + path validation
2. Copy/switch logic

**ADR topic**: DB path switching behavior

**Related**: intent.md section 4

## 16. JSON export/import  `Other`

**Goal**: Export notes to JSON and import them for backup and moving machines.

**Done criteria**
- [ ] Tools > JNote menu, cancellable Background Task
- [ ] Import merges and skips duplicates
- [ ] Includes tags and the schema version

**PR split**
1. Export
2. Import (merge)

**ADR topic**: JSON format and duplicate detection criteria

**Related**: intent.md section 4

## 17. Convenience features and concurrent access  `Other`

**Goal**: Polish the 1.0 usability items and multi-window use.

**Done criteria**
- [ ] Copy snippet; keyboard control (focus search, Enter to navigate, Delete to remove)
- [ ] "Current file notes only" toggle
- [ ] Snippet syntax highlighting (read-only, created only on selection)
- [ ] Refresh once when the Tool Window gains focus (no polling)

**PR split**
1. Copy snippet + keyboard control
2. Current file toggle
3. Syntax highlighting
4. Refresh on focus

**ADR topic**: When highlighting is created and how refresh works

**Related**: intent.md section 4, 7

## 18. Release preparation  `Step 6`

**Goal**: Get everything needed for Marketplace registration.

**Done criteria**
- [ ] Plugin Verifier passes (242 and later)
- [ ] Description (English with Korean), screenshots, icon, change notes
- [ ] State "local only, no telemetry"
- [ ] Final Marketplace name check, LICENSE (MIT)

**PR split**
1. Docs and resources
2. Verifier cleanup and release config

**ADR topic**: Release procedure and versioning policy

**Related**: intent.md section 3

