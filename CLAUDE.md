# CLAUDE.md

JNote: a personal code-note plugin for IntelliJ (Kotlin, SQLite). Read [intent.md](intent.md) for background and agreed design, [DEVELOPMENT.md](DEVELOPMENT.md) for the dev environment, and [CONTRIBUTE.md](CONTRIBUTE.md) for collaboration rules first.

## Language
- Markdown documents (`*.md`, including ADRs): English
- Replies to the user, code comments, commit messages: Korean
- Identifiers (variables, functions): English
- UI strings are not written directly in code; they go through `JNoteBundle` (English default, Korean bundle).

## Top principle: lightweight
Never make the IDE slower. Do not make changes that break the following (details: intent.md section 2).
- No startup work (`postStartupActivity`, app listeners). Load the DB lazily.
- DB, relocation, and file I/O run in the background, never on the EDT.
- No new dependencies besides `sqlite-jdbc`. Ask first if one seems necessary.
- A feature that can affect performance carries measurement evidence in its PR.

## How we work
- Work is done **per issue**, and one issue is split into one or more PRs. Keep each PR to the smallest feature unit.
- Every feature comes **with test code**. Do not open a feature PR without tests.
- Every feature implementation comes with an **ADR** in `docs/adr/`, linked from the PR body.
- Do not change a decision agreed in intent.md on your own; ask first. When it changes, update intent.md and the ADR together.
- Make only the requested change. No cleanup of adjacent code, no out-of-scope features, no error handling for impossible situations.
- Commit and push only when the user asks.

## Core design summary
- DB: `~/.jnote/jnote.db` (path changeable), WAL, single-thread executor, `PRAGMA user_version` migrations.
- Project key: git origin URL (worktrees via `commondir`, fallback to path).
- Search: FTS5 trigram + `LIKE '%q%'`, minimum 2 characters.
- Relocation: exact match, then similarity (about 80%), then ORPHAN. Automatic only up to 5,000 lines.
- Delete is immediate with an undo notification.
