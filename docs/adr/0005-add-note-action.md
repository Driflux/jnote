# 0005. "Add note" action and anchor extraction

- Status: Proposed
- Date: 2026-11-26
- Issue: #5
- PR: #39, #40

## Context
Saving a note from the editor needs the code position (the anchor): the file, the line range, a snapshot of the code, and the enclosing class name. Relocation (intent.md section 6) reads exactly these values later. The gutter issue (23) must create the same anchor from an explicit line range, so the collection cannot be tied to the "Add note" action.

Constraints from intent.md section 2: no startup work, no DB or file I/O on the EDT, no new dependency, and no Java plugin API (the plugin runs in any IntelliJ-based IDE; DEVELOPMENT.md says language-specific code sits behind an interface and 1.0 ships only the common PSI implementation).

## Decision
This ADR covers both PRs of issue 5. The first PR adds anchor collection. The second adds the action, the dialog, and saving.

### Anchor (first PR)
- **Value.** `CodeAnchor(relPath, startLine, endLine, snapshot, className)`. Lines are 1-based and the end line is inclusive, the same as `Note`.
- **Lines from a selection.** The primary caret's selection is used. A selection that ends at column 0 of a later line does not include that line, because the user did not select any of its text (this is what triple-click and shift-down produce). A reversed selection is put in order. With no selection, the caret line alone is used. The math is a pure function (`AnchorLines.ofSelection`) with no editor dependency.
- **Two entry points** in `CodeAnchors`: `fromEditor(project, editor)` and `fromLines(project, file, document, startLine, endLine)`. The second takes a 1-based inclusive range, puts a reversed range in order (a gutter drag upward), and returns null outside the document.
- **relPath.** The file path relative to `project.basePath`, always with `/`. A file outside the base path, or a project without one, is not eligible and gives null. A sibling folder that only shares a name prefix (`/work/project` versus `/work/proj`) is outside.
- **Snapshot.** The full text of the selected lines, from the start of the first line to the end of the last, not just the selected characters. Relocation compares whole lines, so the snapshot stays the same whether the selection starts at column 0 or in the middle of a line. It is capped at 65,536 characters, cut at the last line boundary, so a huge selection cannot bloat the database and the FTS index. A capped snapshot is a prefix of the real code; relocation only needs enough lines to match.
- **Threading.** `fromEditor` and `fromLines` read only the document, so the EDT stays cheap. They return an `AnchorDraft` without the class name. `CodeAnchors.resolve` needs a read action (it asserts this) and fills in the class name, so the caller runs it with `ReadAction.nonBlocking` and shows the dialog on the UI thread.
- **Class name.** `AnchorNameResolver.resolve(file, offset): String?` is the interface. `PsiAnchorNameResolver` is the one common implementation. It starts at the element at the offset and walks up, collecting the names of enclosing `PsiNameIdentifierOwner` elements (class, function, and so on), takes at most 3 levels counted from the innermost, and joins them from the outermost with `.` (for example `Outer.Inner.method`). Without a named container it returns null. The offset is the first non-blank character of the start line, so a selection that begins in indentation still resolves to the declaration on that line.
- **Dumb mode.** The walk only reads the syntax tree and `getName()`; it needs no index, so it works while the IDE is indexing. If a language's name lookup does need an index and throws `IndexNotReadyException`, the resolver returns null and the note is saved without a class name instead of blocking or failing. The action does not wait for smart mode.

### Action, dialog, save (second PR)
To be added with that PR.

## Alternatives considered
- Use `PsiClass` from the Java plugin: gives exact class names for Java and Kotlin, but needs a plugin dependency and does not exist in other IDEs. Rejected (ADR 0001, platform-only).
- Run name resolution on the EDT: PSI can be slow on a file that is not parsed yet. Rejected; only the document is read on the EDT.
- Wait for smart mode before saving: would block the action during indexing for a value that is optional. Rejected.
- Store only the selected characters as the snapshot: depends on where the selection starts, so the same code gives different snapshots. Rejected.
- Truncate nothing: simplest, but one huge selection would make every later search and relocation slower. Rejected in favor of a cap.

## Consequences
- Pros
  - The gutter issue (23) reuses `fromLines` and `resolve` unchanged. A language-specific resolver can replace the common one without touching callers.
  - Line math and path handling are pure functions with unit tests.
- Cons and what we accept
  - The common PSI cannot tell a field or a local variable from a container, so every named declaration counts. A note on the line `val x = 1` inside `A.b` gets `A.b.x`. Lines without a declaration at their start (calls, `if`, and so on) give `A.b`. A language-specific resolver can fix this later.
  - A language whose PSI has no `PsiNameIdentifierOwner` (plain text, for example) always gets no class name.
  - A selection over the snapshot cap loses the end of the snapshot; the line range is still exact.
  - A project opened on a folder different from where its files live (files outside `basePath`) cannot get notes.
- Follow-up work
  - A language-specific resolver that skips variables, if the common result proves too noisy.
