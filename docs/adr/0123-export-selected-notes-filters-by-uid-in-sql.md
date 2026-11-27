# 0123. Export Selected Notes filters by uid in the SQL query

- Status: Accepted
- Date: 2026-11-27
- Issue: #123
- PR: (this PR)

## Context
`NoteExporter.export` read every note, body, snapshot, and tag link of every project in one read transaction on the single DB executor, then kept only the selected `uid`s in memory (ADR 0016 left this as a follow-up from the review of #81). Exporting two selected notes therefore cost as much DB time as exporting everything, and every other DB job (gutter loading, Tool Window, saves) waited behind it.

Measured on this machine (JUnit, temporary database, 2,000-character bodies, 20-line snapshots, 3 tags per note, two selected notes, 4 rounds after JVM start, time from the first to the second `checkCanceled` call, which is the read on the DB executor):

| Notes in the DB | Before (read all, filter in memory) | After (filter in SQL) |
| --- | --- | --- |
| 10,000 | 146 to 177 ms | 0.6 to 8.9 ms (first round 8.9) |
| 50,000 | 884 to 4,418 ms | 0.6 to 2.8 ms |

The full export (no selection) reads the same rows as before; its time did not change beyond run-to-run noise.

## Decision
- `readSnapshot` takes the selection. With a selection, both queries in the same read transaction gain a filter: `note` by `uid IN (SELECT value FROM json_each(?))`, and `note_tag` by `note_id IN (SELECT id FROM note WHERE uid IN (SELECT value FROM json_each(?)))`. The selection is bound as one JSON array string (Gson, already used by the codec).
- One bound parameter means no chunking and no SQLite variable limit (`SQLITE_MAX_VARIABLE_NUMBER`), and `ORDER BY updated_at DESC, id DESC` stays in SQL for the whole result. JSON functions are built into the bundled SQLite (sqlite-jdbc 3.54).
- Without a selection the queries are unchanged.
- The in-memory filter is removed. Filtering by `uid` keeps the behavior that a deleted note whose numeric id was reused is not exported.

## Alternatives considered
- `uid IN (?, ?, ...)` in chunks under the variable limit, as the issue suggested: needs a loop over chunks and a merge sort of the chunk results by `updated_at` to keep the order. More code for the same result.
- A temporary table with the selected uids: an extra write and cleanup inside a read path. Rejected.

## Consequences
- Pros: exporting a selection holds the DB executor for about a millisecond instead of the time it takes to read the whole database.
- Cons and what we accept: the queries depend on SQLite's JSON functions, which have been built in since SQLite 3.38 and are present in the only supported driver.
- Follow-up work: none.
