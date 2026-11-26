# Issue Drafts

> Drafts not yet registered on GitHub. Numbers are provisional; replace them with the real issue numbers once registered.
> Each issue is split into one or more PRs, and the first PR includes the ADR (`docs/adr`). Every feature PR includes tests (CONTRIBUTE.md).
> Steps follow the implementation order in intent.md section 8. The "AI" group follows intent.md section 10 and depends on issues 2, 3, and 6. Otherwise dependencies mostly follow the numbering.

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
- [ ] Apply schema v1 (`note`, `tag`, `note_tag`, indexes) including `uid` (unique), `ai_source`, `source_url`, `prompt`; the `v_notes_export` view is migration v2, and FTS is added as v3 in the search issue
- [ ] Migration tests using a SQLite file in a temp directory
- [ ] Read-only SQL view `v_notes_export` (notes with tags flattened, fixed column names) that every later migration keeps working, with a contract test

**PR split**
1. DB connection, executor, lazy loading
2. Migration runner + schema v1 (uid and AI fields) + backup
3. `v_notes_export` view + contract test

**ADR topic**: DB access model, migration approach, and the view as an external contract

**Related**: intent.md section 4, 10

## 3. Note and tag repository (CRUD)  `Step 1`

**Goal**: Build the repository layer that stores and reads notes and tags.

**Done criteria**
- [ ] Create, read, update, delete notes (both CODE and FREE)
- [ ] Create tags, link/unlink them to notes, and test the policy for unused tags
- [ ] List by project, most recently modified first
- [ ] Pure unit tests (no IDE)
- [ ] New notes get a generated `uid`; AI fields round-trip through the repository

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
- [ ] Compute lazily on first use, off the EDT, and cache it

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
- [ ] Dialog for title and note text, plus a collapsible "AI source" section (default collapsed): AI name (free text with presets), link, prompt (tags are added in the tags issue)
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
- [ ] Small badge on AI-sourced notes in the list

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
- [ ] Schema v3: `note_fts` (trigram) over title, body, snapshot, class name, and prompt + sync-trigger migration
- [ ] Single `LIKE '%q%'` path, minimum 2 characters, tests for Korean particle cases
- [ ] Keep most-recent-first order while searching; no-result guidance
- [ ] Check response time with thousands of rows

**PR split**
1. Schema v3 + search query
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

## 16. JSON format v1 and export  `AI`

**Goal**: Define a versioned JSON format that humans and AI can both produce, and export notes in it.

**Done criteria**
- [ ] Versioned format (`format_version`; notes with `uid`, tags, anchors using the raw git `repo_url`, AI source fields)
- [ ] Export all or selected notes from Tools > JNote as a cancellable Background Task
- [ ] Format spec written in `docs/ai-format.md` (fields, examples, prompt examples)
- [ ] Serializer/deserializer round-trip tests (pure logic)

**PR split**
1. Format model + serializer/deserializer
2. Export action
3. `docs/ai-format.md`

**ADR topic**: JSON format, versioning, and `uid` semantics

**Related**: intent.md section 4, 10

## 17. JSON import: upsert, preview, backup  `AI`

**Goal**: Let AI-written or exported JSON update notes safely, with a human confirmation step.

**Done criteria**
- [ ] Import from a file or from the clipboard
- [ ] Upsert by `uid` (update if present, create otherwise); normalize `repo_url` into the project key on import; unknown tags register automatically
- [ ] Preview dialog: new N, updated M, titles per note, Apply / Cancel
- [ ] Create `jnote.pre-import.bak` right before applying (overwrites the previous one)
- [ ] Import never deletes notes
- [ ] Invalid items are skipped; reasons are collected into one notification
- [ ] Cancellable Background Task

**PR split**
1. Import engine: validate + upsert (pure logic)
2. Preview dialog + apply
3. Clipboard import + pre-import backup

**ADR topic**: Upsert semantics, preview, backup, and the no-delete rule

**Related**: intent.md section 4, 10

## 18. Inbox check on focus  `AI`

**Goal**: Pick up JSON files that an AI agent dropped into the inbox folder.

**Done criteria**
- [ ] When the Tool Window gains focus, check `~/.jnote/inbox/*.json` once (no watching, no polling)
- [ ] Notification "N AI notes waiting · Review" opens the import preview
- [ ] Processed files move to `inbox/done/`

**PR split**
1. Inbox scan on focus + notification
2. Hook into the preview + move to done

**ADR topic**: Inbox contract

**Related**: intent.md section 4, 10

## 19. Copy for AI  `AI`

**Goal**: Make notes easy to paste into an AI chat, and tell web AIs the format.

**Done criteria**
- [ ] Copy selected notes as Markdown (title, file location, snippet, note text)
- [ ] Copy selected notes as JSON (format v1)
- [ ] "Copy format instructions for AI" copies a prompt built from the spec file, which is bundled as a resource at build time
- [ ] Single source for the instruction text (`docs/ai-format.md`)

**PR split**
1. Copy as Markdown / JSON
2. Copy format instructions

**ADR topic**: Copy formats and instruction text source

**Related**: intent.md section 7, 10

## 20. Convenience features and concurrent access  `Other`

**Goal**: Polish the 1.0 usability items and multi-window use.

**Done criteria**
- [ ] Copy snippet; keyboard control (focus search, Enter to navigate, Delete to remove)
- [ ] "Current file notes only" toggle
- [ ] Snippet syntax highlighting (read-only, created only on selection)
- [ ] Refresh once when the Tool Window gains focus (no polling)
- [ ] "AI notes only" filter

**PR split**
1. Copy snippet + keyboard control
2. Current file toggle
3. Syntax highlighting
4. Refresh on focus
5. AI notes filter

**ADR topic**: When highlighting is created and how refresh works

**Related**: intent.md section 4, 7

## 21. Claude Code skill and its marketplace  `AI`

**Goal**: Let Claude Code capture, recall, and organize notes through a skill installed from a Claude Code plugin marketplace.

**Done criteria**
- [ ] Skill `jnote` with three flows: capture, recall (read-only queries on `v_notes_export`), organize (uid upsert JSON)
- [ ] Capture fills file path, line range, snippet, and `repo_url` for code notes
- [ ] Writes only into `~/.jnote/inbox/`; reads only through `v_notes_export`, read-only
- [ ] Root `.claude-plugin/marketplace.json` and a `claude-plugin/` plugin directory (`plugin.json` + `skills/jnote/SKILL.md`)
- [ ] Installable with `/plugin marketplace add Driflux/jnote` then `/plugin install`
- [ ] The skill refers to `docs/ai-format.md` as the single spec
- [ ] Verified by running the skill against a real DB and the plugin preview

**PR split**
1. Plugin directory + SKILL.md (capture)
2. Recall
3. Organize
4. Marketplace manifest + install docs

**ADR topic**: Skill instead of MCP; inbox and view as the contract

**Related**: intent.md section 3, 10

## 22. Release preparation  `Step 6`

**Goal**: Get everything needed for Marketplace registration.

**Done criteria**
- [ ] Plugin Verifier passes (242 and later)
- [ ] Description (English with Korean), screenshots, icon, change notes
- [ ] State "local only, no telemetry"
- [ ] Final Marketplace name check, LICENSE (MIT)
- [ ] Mention the AI skill and its install steps in the description

**PR split**
1. Docs and resources
2. Verifier cleanup and release config

**ADR topic**: Release procedure and versioning policy

**Related**: intent.md section 3, 10

## 23. GitHub-style gutter interaction  `Step 3`

**Goal**: Make adding and previewing notes as direct as GitHub PR review comments.

**Done criteria**
- [ ] Hovering a line in the gutter shows a "+" button; a click adds a note for that line
- [ ] Dragging down the gutter selects a line range and opens the add dialog with that range (context menu and shortcut keep working)
- [ ] A note with a multi-line range draws a thin vertical bar in the gutter over its range (no background highlight in the editor)
- [ ] Hovering a note icon or its bar shows a preview popup after a short delay (title, tags, AI badge, note text, first lines of the snippet) with Edit, Open in Tool Window, and Delete actions; the popup closes when the mouse leaves
- [ ] Hover and preview read only from the in-memory per-file cache (no DB or file I/O on the EDT)
- [ ] The mouse listener is attached per editor lazily, does constant work per event, and is skipped for editors without a project file
- [ ] The PR records the measured cost of hover handling on a 5,000-line file

**PR split**
1. Gutter "+" button with line click and drag range
2. Range bar for multi-line notes
3. Hover preview popup with actions

**ADR topic**: Gutter interaction model and its per-editor listener cost

**Related**: intent.md section 7. Depends on issues 5, 6, and 8. The per-file cache from issue 8 must keep title, body, tags, and snippet so the preview needs no query.

## Later (not scheduled)

- Markdown rendering of note text (needs a decision on the Markdown plugin dependency vs. a custom renderer; see the performance principles)
- Markdown export to a file (the clipboard copy ships in 1.0)
- Instruction files for other agents (`AGENTS.md` style) based on `docs/ai-format.md`
- Verifying other JetBrains IDEs
