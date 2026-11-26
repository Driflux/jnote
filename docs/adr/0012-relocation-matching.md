# 0012. Relocation matching algorithm and threshold

- Status: Proposed
- Date: 2026-11-26
- Issue: #12
- PR: (to be filled)

## Context
Notes store a code snapshot. When the file changes (other branch, worktree, edits), the note must find its code again (intent.md section 6). The order is fixed: exact match after whitespace normalization, then similarity, then none. The core must stay cheap (intent.md section 2): it runs in the background on files that can be large, the caller limits automatic similarity runs to 5,000 lines, and manual runs have no limit. So the core must not assume that limit and must be cancellable. The core is pure Kotlin in `relocate/` with no IntelliJ API, so it is tested with plain JUnit.

## Decision
- **API.** `Relocator.locate(documentLines, snapshotLines, originalStartLine, options)` returns `RelocationResult`: `Match(startLine, endLine)`, `Approx(startLine, endLine, similarity)`, or `None`. Lines are 1-based and the end line is inclusive. `options` holds `similarityThreshold = 0.8` and `isCancelled`.
- **Normalization.** Trim each line, collapse every whitespace run (tabs, spaces) inside it to one space. Blank lines are dropped from both the document and the snapshot before comparing, and the matcher keeps a map back to the original line numbers. Reason: adding or removing a blank line is the most common harmless edit and should not push a note to "approximate". A match starts at the first non-blank line and ends at the last non-blank line of the window. A snapshot with no non-blank line returns `None`.
- **Exact match.** Compare normalized lines window by window. If several windows match, take the one whose start line is closest to the original start line; on a tie, take the earlier line.
- **Similarity.** Only if no exact match exists. Windows have the same number of non-blank lines as the snapshot (no inserted or deleted lines inside a window). Per-line similarity is `1 - distance / max(length)` using Levenshtein on normalized lines. A window matches when the mean line similarity is at least the threshold (0.8) **and** no line is below a floor of 0.5. The best window (highest mean) wins; ties go to the start line closest to the original, then the earlier line.
- **Per-line floor 0.5.** The mean alone lets many identical trivial lines (`}`, blank-like lines) carry a window whose real content is different. A floor of 0.5 requires every line to be at least half recognizable, while still allowing one heavily edited line. It is a constant, not an option, because the issue asks for no extra configuration; it is easy to change after the real benchmark (issue 14).
- **Cheap search.** Each line comparison gets the smallest similarity it still needs: the larger of the floor and what the window sum still requires to reach the best mean so far (or the threshold). From it we derive a maximum edit distance. A length difference above that distance rejects a pair without any DP. Otherwise a banded Levenshtein (width = maximum distance) runs with early exit when a whole row exceeds the bound, and reuses its two DP rows across all comparisons. A window is abandoned as soon as its remaining lines cannot reach the needed mean. Identical lines skip the DP.
- **Cancellation.** `isCancelled` is checked once per window in both matchers. A cancelled run returns `None`, never a partial guess.
- **Edge cases.** One-line snapshots work. An empty snapshot, or one longer than the document (after dropping blank lines), returns `None`.
- **No threads, no I/O, no new dependency.** Callers decide the thread and the 5,000-line limit.

## Alternatives considered
- Keep blank lines significant: simpler, but a blank-line edit would turn an exact match into an approximate one.
- Character-bag or first-token window prefilter: the length bound plus banded Levenshtein with early exit already rejects most pairs in near-constant time, so an extra structure did not earn its place. Revisit if the issue 14 benchmark misses the target.
- Full (unbounded) Levenshtein: allocates and costs O(n*m) per pair on every window; too slow on big files.
- Windows of variable size (alignment with inserted or deleted lines): finds more edits but costs far more and is hard to bound. Out of scope; those notes become orphan or are relocated manually.
- Threshold as the only rule (no floor): accepts windows carried by trivial identical lines.

## Consequences
- Pros
  - Pure logic with plain unit tests and no startup cost.
  - Work per window is bounded and early-exits, and the caller can cancel.
- Cons and what we accept
  - Edits that add or remove a non-blank line inside the snippet are not found by similarity.
  - 0.8 and 0.5 are judgment values until measured with real files.
- Follow-up work
  - Issue 14 benchmark (20 displaced notes in 5,000 lines) may justify a prefilter or different constants.
  - Callers (background validation, manual relocation) apply the 5,000-line rule and a progress indicator.
