# JNote: Intent and Agreed Design

> This document records what we are building, why, and the design decisions agreed so far. When a decision changes, update this document and the related ADR (`docs/adr`) together.

## 1. Purpose
A personal note-taking plugin for IntelliJ: select code, attach a note, and keep it **locally only**.

- Previous workflow: in another text editor, the class name, line number, what was done, and what the code is were kept in two separate note files. That made notes hard to manage and hard to find, and queries had to be written down separately as well.
- Solution: store notes anchored to code and free notes without an anchor (queries, etc.) in one place, and find them from a single search box.
- Notes are not managed as code on GitHub. Data lives only in a local SQLite file.

## 2. Top priority: lightweight
The plugin must never make the IDE feel slower. It should be a quiet helper, not the main act.

Performance principles (non-negotiable):
1. No work at startup (no `postStartupActivity`, no app listeners). The DB is loaded lazily on first use.
2. DB access, relocation, and git config reading all run in the background. Never block the EDT.
3. A file with no notes costs only an in-memory cache lookup.
4. One dependency: `sqlite-jdbc`.
5. Dynamic plugin: installs and uninstalls without an IDE restart.
6. Measure with a 5,000-line file benchmark; a feature that misses the target is reduced or made manual.

Stay with Kotlin/JVM. A plugin must run in the IDE's own JVM, and a separate native process would be heavier because of IPC cost.

## 3. Distribution and supported range
- Free on JetBrains Marketplace, MIT license, plugin source public on GitHub (unrelated to note data).
- Officially supported: IntelliJ IDEA (Community, Ultimate) 2024.2 (`242`) and later, `untilBuild` unset.
- No dependency on the Java plugin; only common platform APIs. Class-name extraction sits behind an interface so language-specific implementations can be added later. Other JetBrains IDEs are "not verified".
- No network calls and no telemetry. This is stated in the Marketplace description.
- All UI strings go through `MessageBundle`. English is the default, with a Korean bundle.
- Re-check that the name `JNote` is free at Marketplace registration (a similar plugin exists: Code Notes).

## 4. Data
- DB: a single `~/.jnote/jnote.db`. SQLite WAL mode, `busy_timeout` about 5 s. One connection, accessed only from a single-thread executor.
- Schema:
  ```
  note(id, kind['CODE'|'FREE'], project, rel_path, class_name, start_line, end_line,
       snapshot, status['OK'|'APPROX'|'ORPHAN'|'NEEDS_RELOCATE'], title, body,
       created_at, updated_at)
  tag(id, name UNIQUE) / note_tag(note_id, tag_id)
  note_fts: FTS5 trigram (title, body, snapshot, class_name), synced by triggers
  INDEX note(project, rel_path)
  ```
- Migrations: `PRAGMA user_version` plus sequential SQL. Right before bumping the version, create one `jnote.db.bak` and overwrite the previous backup.
- Project key: the normalized git `origin` URL. For worktrees, follow `commondir` and read config files only (no process is spawned). If there is no `origin`, fall back to the project path. The UI shows the repository name. The key is computed once when the project opens and cached.
- Free notes (queries, etc.) are global notes with `project = NULL`. Anchor columns are NULL when `kind = FREE`.
- The settings page (Settings > Tools > JNote) has a single item: the DB path.
  - If no file exists at the new path, copy the current DB there and switch; the old file is left in place.
  - If a file already exists, switch to it after a confirmation dialog (no merging). Switching needs no restart.
- Several IDE windows share the DB, so the Tool Window refreshes once when it gains focus (no polling).
- JSON export/import lives under Tools > JNote. Import merges and skips duplicates.

## 5. Search and list
- Search uses a single path: `LIKE '%q%'` on the FTS5 trigram table. Queries of 3+ characters use the index; 2 characters fall back to a full scan automatically. The minimum query length is 2 characters.
- Default view: current project, most recently modified first. A toggle at the top widens it to all projects. There is no sort option.
- Tags are a registered dictionary: pick chips in the dialog, and typing a new tag registers it automatically.
- Tag rename and delete happen from the tag filter chips in the Tool Window (right-click). Deleting removes only that tag from notes, after a confirmation dialog.

## 6. Relocation
- Stored: path relative to the project root, class name, line range, code snapshot.
- When: validate only that file's notes when a file opens. While open, a RangeMarker follows the position, and changed line numbers are written to the DB on close or save. Navigating from the list validates too.
- Matching order:
  1. **Exact match** after whitespace normalization. If there are several, take the one closest to the original line.
  2. Otherwise **similarity** per line (about 80% or more, Levenshtein ratio), taking the most similar location.
  3. Otherwise **ORPHAN**. The snapshot and note text are preserved.
- If the file path is broken, search once more by class or file name using `FilenameIndex`.
- Applying results: update automatically. A similarity match is marked APPROX ("approximate location"); once the user confirms or edits the note, the mark disappears and the snapshot is refreshed.
- Auto vs manual: exact match is always automatic (background). Similarity is automatic only for files of 5,000 lines or fewer; larger files become NEEDS_RELOCATE. Manual relocation has no size limit and runs with a progress indicator. A "disable validation on open" setting is added only if measurements show it is needed.
- This solves line numbers differing across worktrees and branches.

## 7. UI and usability
- Add a note: select code in the editor, then use the context menu or `Ctrl+Option+N` (changeable in Keymap).
- Tool Window: search box, filter (all / code / free), list, detail area. A "+ New note" button creates free notes. Mockup: `mockup/ui.html`.
- Gutter icon: shown on the start line; APPROX uses a different icon. ORPHAN and NEEDS_RELOCATE notes draw no icon. Tooltip shows the title, a click selects the note in the Tool Window, and several notes on one line open a chooser popup.
- Note text is plain text. Free notes use a monospace font. No Markdown rendering.
- Delete: delete immediately instead of confirming, and show an "Undo" notification for a few seconds.
- Progress:
  - Manual relocation, import/export, and DB move run as cancellable Background Tasks.
  - Lists and search show a spinner only when they take more than about 300 ms.
  - Empty state and no-result state show guidance text.
  - Errors show a notification with the reason and a fix.
- 1.0 convenience features: copy snippet, keyboard control (focus search, Enter to navigate, Delete to remove), "current file notes only" toggle, snippet syntax highlighting (read-only, created only on selection), SVG icons for light, dark, and New UI.
- Excluded (later versions): Markdown rendering, Search Everywhere integration, Alt+Enter intention, links between notes, range background highlight, Markdown export.

## 8. Implementation order
1. Skeleton, schema, migrations, CRUD (unit tests)
2. "Add note" action and Tool Window list, detail, edit, delete (undo)
3. Gutter icon, RangeMarker position tracking, write-back on close
4. Tags and FTS5 trigram search
5. Relocation (exact match, similarity, manual) and the 5,000-line benchmark
6. Release preparation: Plugin Verifier, description, screenshots, icon, Marketplace registration

The message bundle and the performance principles apply from the start.

## 9. Decisions that changed during design
| Item | Initially | Final |
|---|---|---|
| Distribution | Local install only | Marketplace release, IDEA 2024.2 and later |
| Delete | Confirmation dialog | Immediate delete + undo notification |
| Settings page | None | Settings page with the DB path only |
| DB path | Fixed | Changeable in settings |
| Project key | Project name | git origin URL (fallback: path) |
| Relocation | Excluded from the first version | Included in the first version |
| Tags | Comma-separated string | Registered dictionary + add from the UI |
