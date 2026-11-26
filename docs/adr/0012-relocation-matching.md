# 0012. Relocation matching algorithm and threshold

- Status: Proposed
- Date: 2026-11-26
- Issue: #12
- PR: #32, #33

## Context
Notes store a code snapshot. When the file changes (other branch, worktree, edits), the note must find its code again (intent.md section 6). The order is fixed: exact match after whitespace normalization, then similarity, then none. The core must stay cheap (intent.md section 2): it runs in the background on files that can be large, the caller limits automatic similarity runs to 5,000 lines, and manual runs have no limit. So the core must not assume that limit and must be cancellable. The core is pure Kotlin in `relocate/` with no IntelliJ API, so it is tested with plain JUnit.

## Decision
- **API.** `Relocator.locate(documentLines, snapshotLines, originalStartLine, options)` returns `RelocationResult`: `Match(startLine, endLine)`, `Approx(startLine, endLine, similarity)`, or `None`. Lines are 1-based and the end line is inclusive. `options` holds `similarityThreshold = 0.8` and `isCancelled`.
- **Normalization.** Trim each line, collapse every whitespace run (tabs, spaces) inside it to one space. Blank lines are dropped from both the document and the snapshot before comparing, and the matcher keeps a map back to the original line numbers. Reason: adding or removing a blank line is the most common harmless edit and should not push a note to "approximate". A match starts at the first non-blank line and ends at the last non-blank line of the window. A snapshot with no non-blank line returns `None`.
- **Exact match.** Compare normalized lines window by window. If several windows match, take the one whose start line is closest to the original start line; on a tie, take the earlier line.
- **Similarity.** Only if no exact match exists. Windows have the same number of non-blank lines as the snapshot (no inserted or deleted lines inside a window). Per-line similarity is `1 - distance / max(length)` using Levenshtein on normalized lines. A window matches when the mean line similarity is at least the threshold (0.8) **and** no line is below a floor of 0.5. The best window (highest mean) wins; ties go to the start line closest to the original, then the earlier line.
- **Per-line floor 0.5.** The mean alone lets many identical trivial lines (`}`, blank-like lines) carry a window whose real content is different. A floor of 0.5 requires every line to be at least half recognizable, while still allowing one heavily edited line. It is a constant, not an option, because the issue asks for no extra configuration; it is easy to change after the real benchmark (issue 14).
- **Cheap search.** Every window must reach a needed mean: the threshold, or the best mean so far once a candidate exists. Each window is scored in two passes.
  - First pass, no DP. For each line pair, the length difference and a character-histogram distance give a lower bound on the edit distance, so an upper bound on the line similarity. One edit lowers by at most one both the number of characters only the first line has and the number only the second line has, so the larger of the two counts is a lower bound. The window is abandoned as soon as a line is below the floor, or the bounds so far plus 1.0 for each remaining line cannot reach the needed mean.
  - Second pass, exact. Lines are compared in order. Each line gets the smallest similarity it still needs: the largest of the floor, the need with every remaining line at 1.0, and the need with every remaining line at its upper bound. From it we derive a maximum edit distance. A lower bound above that distance rejects the pair without any DP. Otherwise a banded Levenshtein (width = maximum distance) runs with early exit when a whole row exceeds the bound, and reuses its two DP rows. Identical lines skip the DP.
  - Distances for line contents that occur more than once (in the document or the snapshot) are memoized for one search, capped at 65,536 entries. A position pair (document line, snapshot line) belongs to exactly one window, so only repeated contents can repeat a pair.
  - The bounds only reject windows that the full computation also rejects, with a margin of 1e-6 on the window sum, so results are identical to computing every pair in full. A randomized test checks this against a straightforward reference implementation.
- **Cancellation.** `isCancelled` is checked once per window in both matchers, and every 16 lines inside a similarity window. A cancelled run returns `None`, never a partial guess.
- **Edge cases.** One-line snapshots work. An empty snapshot, or one longer than the document (after dropping blank lines), returns `None`.
- **No threads, no I/O, no new dependency.** Callers decide the thread and the 5,000-line limit.

## Alternatives considered
- Keep blank lines significant: simpler, but a blank-line edit would turn an exact match into an approximate one.
- Length bound plus banded Levenshtein only (the first version): on templated code most pairs pass the length bound, and a window ran tens to hundreds of DP calls before it was abandoned (see Measurements). The histogram bound was added for that.
- Memoizing by line position: a position pair occurs in one window only, so it never hits. The memo is keyed by line content instead.
- Full (unbounded) Levenshtein: allocates and costs O(n*m) per pair on every window; too slow on big files.
- Windows of variable size (alignment with inserted or deleted lines): finds more edits but costs far more and is hard to bound. Out of scope; those notes become orphan or are relocated manually.
- Threshold as the only rule (no floor): accepts windows carried by trivial identical lines.

## Consequences
- Pros
  - Pure logic with plain unit tests and no startup cost.
  - DP runs only for windows whose bounds can still reach the needed mean, and the caller can cancel within 16 lines.
- Cons and what we accept
  - Edits that add or remove a non-blank line inside the snippet are not found by similarity.
  - 0.8 and 0.5 are judgment values until measured with real files.
  - The worst case is still one bound check per (window, line) pair, O(n * m) for n document lines and an m-line snapshot. After the change this bound check, not the DP, limits the run time.
- Follow-up work
  - Issue 14 benchmark (20 displaced notes in 5,000 lines) may justify different constants.
  - Callers (background validation, manual relocation) apply the 5,000-line rule and a progress indicator.

## Measurements
`SimilarityMatcher.find` alone on JVM 21, threshold 0.8, on the development machine (10 cores, load average 4 to 5 from other jobs). Each cell is the median of 5 timed runs after 2 warm-up runs; ranges span 2 or 3 separate JVM runs. "Before" is the first version (length bound and banded Levenshtein only), "after" is the design above. Both sides returned identical results in every case. `Relocator.locate` adds the exact scan, about 1 to 2 ms.

Templated document: 5,000 lines `val valueN = compute(N)` for N = 0..4999. Repeated document: 5,000 lines that cycle through 10 lines `val valueN = compute(N) + offset`. No snapshot occurs in its document exactly.

| Document | Snapshot (k = 0..m-1) | Result | m | DP calls before | DP calls after | ms before | ms after |
|---|---|---|---|---|---|---|---|
| Templated | `val valueXk = compute(k, k * 5)` | Approx | 10 | 34,080 | 268 | 41 to 43 | 3.5 |
| Templated | same | Approx | 100 | 309,405 | 1,048 | 455 to 535 | 23 to 24 |
| Templated | same | Approx | 500 | 1,397,584 | 820 | 2,352 to 2,491 | 100 to 108 |
| Templated | `var resultk = computeAll(k)` | None | 10 | 24,965 | 0 | 26 to 30 | 2.9 |
| Templated | same | None | 100 | 106,368 | 0 | 141 to 143 | 15 to 16 |
| Templated | same | None | 500 | 325,994 | 53 | 458 to 472 | 69 to 79 |
| Repeated | the same 10 lines with `offsetX` | Approx | 10 | 22,964 | 10 | 17 to 18 | 2.4 to 2.5 |
| Repeated | same | Approx | 100 | 199,040 | 10 | 327 to 346 | 8.8 |
| Repeated | same | Approx | 500 | 901,850 | 10 | 1,556 to 1,666 | 34 to 36 |

- Before, the DP limits the time: about 1.7 µs per call in the 500-line templated case (310 calls per window).
- After, the first-pass bound limits it: about 66 ns per check, about 360 checks per window, 1.6 million checks in the 500-line templated case.
- On the repeated document without the memo, the bounds alone still ran 225,500 DP calls (87 ms) at m = 500. The memo brings that to 10.
