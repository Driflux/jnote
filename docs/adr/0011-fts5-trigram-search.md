# 0011. FTS5 trigram search and unifying on LIKE

- Status: Proposed
- Date: 2026-11-26
- Issue: #11
- PR: (to be filled)

## Context
Users search note text, code snippets, class names, and tags from one box (intent.md section 5). Notes are short, mixed Korean and English, and Korean is agglutinative (`취소건을` must be found by `취소`), so word tokenizers do not fit. Search must be cheap (intent.md section 2): it runs on the DB thread, never on the EDT, and adds no dependency. The bundled `sqlite-jdbc` 3.54.1.0 ships SQLite with FTS5 and the `trigram` tokenizer; migration v3 and its tests prove it.

## Decision
- **Schema v3** (appended to `Migrations.ALL`; v1 and v2 untouched). `note_fts` is an FTS5 external-content table over `title, body, snapshot, class_name, prompt` with `content='note', content_rowid='id', tokenize='trigram'`. `note` stays the single source of truth; the index stores no second copy of the text.
- **Sync.** `AFTER INSERT`, `AFTER UPDATE`, and `AFTER DELETE` triggers on `note` keep the index current (the update trigger issues the FTS5 `'delete'` command with the old values, then inserts the new ones). Existing rows are indexed in the same migration transaction with `INSERT INTO note_fts(note_fts) VALUES ('rebuild')`, so upgrading a populated v2 database needs no extra step. The pre-migration `jnote.db.bak` from ADR 0002 still applies. The export view `v_notes_export` does not depend on `note_fts` and is not touched.
- **One query path.** `SearchRepository.search(query, project, allProjects, limit = 200)` runs `LIKE '%q%'` on the indexed columns of `note_fts`. With 3 or more characters SQLite hands the LIKE to the trigram index; with 2 characters FTS5 cannot use the index and SQLite scans the table. The caller sees no difference. Queries shorter than 2 characters return an empty list without touching the DB (the DB is not even opened).
- **Tags.** Tag names are not in the FTS table (they live in `tag`/`note_tag` and change without touching `note`, so a trigger-fed index would need extra triggers on two more tables). A note matches when an indexed column OR a tag name contains the query: `n.id IN (SELECT rowid FROM note_fts WHERE <columns LIKE>) OR n.id IN (SELECT note_id FROM note_tag JOIN tag WHERE tag.name LIKE ?)`. It is still LIKE only, with the same pattern. The tag side is a scan of `note_tag`, which is small for a personal note store.
- **Escaping.** `%`, `_`, and `\` in the user query are escaped with `\` and `ESCAPE '\'` is added. Measured with `EXPLAIN QUERY PLAN`: FTS5 uses its trigram LIKE index (`INDEX 0:L0`) only when the LIKE has no `ESCAPE` clause; with `ESCAPE` it falls back to a full scan. So the clause is added only when the query contains one of those three characters. Queries without them (the common case) use the index; queries with them are still correct but scan. The number of columns is five, so SQLite plans the OR as a multi-index OR with one trigram lookup per column.
- **Case.** The trigram tokenizer is case-insensitive by default, and SQLite `LIKE` is case-insensitive for ASCII, so `handler` finds `Handler`. Korean has no case, so matching is exact on syllables. No normalization (Hangul jamo decomposition, NFC/NFD) is done; text typed in the IDE is NFC.
- **Scope rule.** Same as the note list: project notes plus global notes (`project IS NULL`). `allProjects = true` ignores the filter. A `null` project with `allProjects = false` returns global notes only.
- **Order and limit.** `updated_at DESC, id DESC`, then `LIMIT` (default 200). Only note ids are returned; loading notes belongs to the note repository (issue #3).
- **No threading changes.** `search` goes through `Database.submit` and returns a `CompletableFuture`.

## Alternatives considered
- FTS5 `MATCH` with a phrase query: needs query quoting rules, treats 1 to 2 character queries differently, and gives a second path next to LIKE. Rejected for the single-path rule in intent.md section 5.
- Default `unicode61` tokenizer: splits on word boundaries, so `취소` does not match `취소건을`. Rejected.
- Plain `LIKE` on `note` with no FTS table: always a full scan; fine at hundreds of rows but not the stated goal of thousands. Rejected.
- Putting tags into the FTS table (a denormalized `tags` column kept by triggers on `note_tag` and `tag`): faster tag search but more triggers and a rebuild on tag rename. Rejected for now; revisit if a tag scan ever shows up in measurements.
- Always `ESCAPE`, accepting a scan: simpler but loses the index for every query. Rejected given the measured plan difference.
- Contentful FTS table (copy of the text): doubles storage and a second place to drift. Rejected.

## Consequences
- Pros
  - Korean particle cases work without a morphological analyzer; one query shape for 2 and 3+ character searches.
  - Index maintenance is automatic and tested (insert, update, delete, backfill).
- Cons and what we accept
  - The trigram index adds roughly three entries per character of indexed text; acceptable for notes of this size.
  - Queries with `%`, `_`, or `\` and all 2-character queries scan; tag matching always scans `note_tag`.
  - One-character queries are not supported by design.
- Measurement (unit test `thousandsOfRowsSearchQuickly`, 5,000 notes with title, Korean and English body, snapshot, and class name, temp-dir SQLite file, warm connection, one run on the author's machine)
  - 3+ characters (`unique-needle`, project filter, 1 hit): 1 ms
  - 2 characters (`x9`, project filter, 111 hits): 4 ms
  - The test only asserts under 2 seconds; the numbers above are informational.
- Follow-up work
  - Search box UI and no-result guidance (second PR of issue #11, after the Tool Window).
  - Load notes by id in the note repository (issue #3).
