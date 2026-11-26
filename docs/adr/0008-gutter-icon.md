# 0008. Gutter icon and per-file note cache

- Status: Proposed
- Date: 2026-11-26
- Issue: #8
- PR: (to be filled)

## Context
Lines that have notes should be marked in the editor gutter (intent.md section 7). The icon sits on the start line of a note. APPROX notes use a different icon, and ORPHAN and NEEDS_RELOCATE notes draw none. The tooltip shows the title. A click selects the note in the Tool Window, and several notes on one line open a chooser popup.

The gutter runs for every file the user opens, so it is the feature most likely to make the IDE feel slower. intent.md section 2 applies in full. There is no startup work and no DB or file I/O on the EDT, and a file with no notes costs only an in-memory cache lookup. Issue #23 (GitHub-style "+" button and hover preview) reads its preview from the same cache, so the cache must hold everything the preview shows.

## Decision
- **One provider for all languages.** `NoteLineMarkerProvider` is registered once as `codeInsight.lineMarkerProvider` with `language=""` (`Language.ANY`). It is `DumbAware`, because it needs no index. It returns nothing from `getLineMarkerInfo` and does its work in `collectSlowLineMarkers`, which runs once per element list on the daemon's background thread. Injected fragments are skipped. In a file with several PSI roots (template languages), only the base-language root draws, so no marker appears twice. No `EditorFactoryListener` and no startup activity.
- **Placement.** A marker is attached to the leaf element that holds the first non-blank character of the start line, with its range starting there. A leaf that spans many lines, such as the single leaf of a plain-text file, still gets the marker on the right line. The daemon calls the provider twice per root, for the visible range and for the rest. Each call handles only the targets inside its own leaves, so every target gets exactly one marker. Targets are sorted, so each leaf costs one binary search.
- **Cache shape.** `GutterNoteService` is a project-level light service (not in `plugin.xml`), created on the first marker pass. It holds a `FileNoteCache` keyed by the path relative to `project.basePath`. The service exists once per project, and the project key never changes for a project, so an entry stands for (project key, relative path). Each entry is a list of `CachedNote` with id, title, body, tag names, AI source, status, line range, and the first 5 lines of the snippet (at most 600 characters). FREE notes are not cached. At most 1,000 files are kept. Past that the cache starts over.
- **Loading.** The first lookup of a file returns "not loaded yet" and starts one load off the EDT. The project key is resolved on a pooled thread, then `NoteRepository.listByPath` and `TagRepository.tagsByPath` run on the DB thread. Both use the `(project, rel_path)` index. If no database has been opened and no file exists at the configured path, the result is empty and no database file is created. When a load finishes, the daemon is restarted for that one file, and only if the notes differ from what was cached. A file with no notes is therefore never re-highlighted, and later passes on it cost one hash lookup. The restart is posted to the EDT, because the daemon cannot be restarted from inside a highlighting pass.
- **Staleness.** `NotesChanged` marks every entry stale. Stale entries keep serving their old value while one reload runs. Open files that are already cached are reloaded right away. A reload that returns the same notes does not restart the daemon. Closed files reload the next time they are highlighted. A result that was made stale while it loaded is dropped and loaded again.
- **Database switch.** The cache remembers the `DatabaseService.current()` it was filled from. If the path differs, or the `Database` instance differs, the whole cache is dropped before the lookup, so notes from another database are never drawn. A database that was not opened yet and then opens at the same path is not a switch. A load that fails with `DatabaseClosedException` (a switch during the load) restarts the daemon for its file, and the next pass sees the new database.
- **Failures.** A failed load is reported in one error notification. The notification names the file and the reason, both HTML-escaped. Further failures stay silent until the next `NotesChanged` or database switch. A failed entry is not retried until then, so a broken database does not cost a query on every keystroke.
- **Click and tooltip.** The tooltip is the escaped title. Several notes on one line list all titles, one per line. A click calls `NoteSelection.request(project, id)`. It stores the id in `PendingNoteSelection`, opens the Tool Window, and publishes `NoteSelectRequested`. An open panel selects the note at once. A panel created by that click selects it after its first load.
- **Disposal.** The service's message bus connection is tied to the service. After dispose, lookups return nothing, callbacks do nothing, and the cache is cleared. EDT hops expire with the project.
- **Chooser.** When several notes share a start line, a click opens a list popup at the click point. Each row shows the note's title and icon, and choosing a row requests that note's selection the same way. A line with one note opens no popup.
- **Icons.** Two 16x16 SVG files in `src/main/resources/icons/`. `gutterNote.svg` is a filled page. `gutterNoteApprox.svg` is a dashed page outline in a different color, so the two differ in shape and not only in color. Each has a `_dark` variant, which the platform picks under dark themes. The colors come from the New UI palette, and the same files serve the classic UI. The first PR draws platform icons, and the second PR adds the SVG files and the chooser.

### Measurements
Measured with `JNOTE_PERF=1 ./gradlew test --tests '*NoteLineMarkerPassCostTest*' -i` on an Apple M2 Pro. Each value is the median of 9 rounds. A round is 2,000 passes for a file without notes and 50 passes for a file with 20 notes.

| File (5,000 lines) | PSI elements | No notes | 20 notes | Leaf scan alone |
|---|---|---|---|---|
| XML | 104,970 | 0.003 ms | 3.7 ms | 3.2 ms |
| Kotlin | 139,905 | 0.003 ms | 4.3 ms | 3.6 ms |

A file without notes costs one synchronized read of the current database and one hash lookup. For a file with notes, the scan over the leaves dominates. Reading a leaf's text range walks up the tree. The markers themselves add about 0.6 ms. The pass runs on the daemon's background thread, not on the EDT.

## Alternatives considered
- `EditorFactoryListener` or a startup activity that loads notes when an editor opens: it adds work for every editor, including editors that never show a note, and it is startup work. Rejected.
- Per-element `getLineMarkerInfo`: it is called for every PSI element of every file, so even a file without notes would pay for each element. Rejected in favor of one call per element list.
- Reading the DB inside the marker pass: it blocks highlighting on the single DB thread and on disk. Rejected. The pass only reads the cache.
- `DaemonCodeAnalyzer.restart()` for the whole project on every change: it re-highlights every open editor after any note save. Rejected for a per-file restart that runs only when the markers' input changed.
- Keying the cache on the path only and clearing it from the settings page: a switch from another place, or a switch during a load, would leave notes from the old database. Rejected for comparing the current database on each lookup.
- Retrying a failed load on the next pass: a broken database would fail and notify on every keystroke in every open file. Rejected.
- A separate New UI icon set registered through an `iconMapper` file: an extra mapping file for two icons whose New UI style already reads well in the classic UI. Rejected.
- One query with tags flattened by `GROUP_CONCAT`: one statement fewer, but it duplicates the note mapping of `listByPath`. Both statements run back to back on the DB thread and use the index. Rejected.

## Consequences
- Pros
  - Files without notes cost a hash lookup per pass after their first load, and are never re-highlighted because of JNote.
  - No startup work, no EDT I/O, no new dependency. The service and its subscription exist only after the first marker pass.
  - The hover preview of issue #23 can read title, body, tags, AI source, status, line range, and snippet from the cache with no query.
- Cons and what we accept
  - Icons appear after the first load of a file. Opening a file with notes costs one extra highlighting pass of that file.
  - The cache trusts line numbers from the DB. Until position tracking (#9) and relocation (#13) exist, an icon can sit on a line that moved.
  - Kotlin and other languages do not highlight files outside source roots, so such files show no icons.
  - After a failed load, icons stay hidden until the next note change or database switch. The notification says so.
- Follow-up work
  - Position tracking with RangeMarkers (#9) and relocation (#13) update the lines this cache draws.
  - The "+" button, drag range, range bar, and hover preview (#23) build on `GutterNoteService` and `CachedNote`.
