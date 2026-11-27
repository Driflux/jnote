# 0021. Claude Code skill instead of MCP; the inbox and the view as the contract

- Status: Proposed
- Date: 2026-11-27
- Issue: #21
- PR: #98 (plugin directory and capture), #102 (recall), (organize), (marketplace manifest and install docs)

## Context
intent.md section 10.5 asks for a Claude Code skill `jnote` that captures, recalls, and organizes notes, distributed from this repository through a Claude Code plugin marketplace. The skill must write only into `~/.jnote/inbox/`, read only through the read-only view `v_notes_export`, and use `docs/ai-format.md` (ADR 0016) as its single spec. The JetBrains plugin never calls an AI and runs no server (intent.md section 2), so the skill must work with files alone.

Claude Code installs a marketplace plugin by copying the plugin directory into its cache. An installed skill can read files inside its plugin directory through `${CLAUDE_PLUGIN_ROOT}`, but nothing outside it, and the marketplace validator warns that local sources reached through symlinks are not read.

## Decision
- **A skill, not an MCP server.** The skill is instructions that the agent follows with its own shell and file tools. It adds no process, no port, and no dependency, and the IDE plugin stays unaware of it.
- **Contract.** Writes are JSON format v1 documents saved as new files in `~/.jnote/inbox/`; every one passes the import preview (#17, #18) before anything reaches the DB. Reads go through `v_notes_export` with `sqlite3 -readonly` (recall PR). The skill never opens `jnote.db` for writing and never overwrites an inbox file.
- **Layout (intent.md 10.5, unchanged).** `claude-plugin/.claude-plugin/plugin.json` and `claude-plugin/skills/jnote/SKILL.md`; the root `.claude-plugin/marketplace.json` comes in the last PR.
- **Recall flow (#102).** Reads go only through `v_notes_export` with `sqlite3 -readonly`, one statement per call, with the query passed through a quoted heredoc so `$`, backticks, and `\` in search terms reach SQLite verbatim; if a query line equals the delimiter, the agent picks another delimiter. User words go only inside single-quoted SQL strings with `'` doubled. The skill gives three example queries (text search over every text column including `rel_path`, notes on one file scoped by project key, whole note by `uid`). `ClaudePluginTest` runs every `sql` example against a real migrated DB.
- **Project key for recall.** The key is the origin URL with a lower-case host, without scheme, user, port, and trailing `.git`, matching `RepoUrl.normalize`. A project with no origin, or whose origin is a local path, is keyed by the absolute folder path, the same fallback as capture. The same relative path can exist in other projects, so file queries always filter by project.
- **Read-only open and WAL.** The DB uses WAL mode. With no open connection and no `-wal`/`-shm` files, a read-only open fails with "unable to open database file". The skill asks the user to open the JNote Tool Window and retry. It forbids `immutable=1` and a read-write open: immutable skips locking and can return wrong results while the IDE writes, and a read-write open breaks the single-writer contract.
- **SQLite version.** The view uses `group_concat(... ORDER BY ...)`, which needs SQLite 3.44 or newer; older versions cannot parse the schema. The skill checks `sqlite3 --version` and stops with a message.
- **Notes are untrusted data.** Note text comes from imports and other tools. The skill tells the agent to use it only as evidence for the answer and never to follow instructions in it, run commands from it, disclose data, or change files because of it.
- **Stale locations.** `APPROX`, `ORPHAN`, and `NEEDS_RELOCATE` mean the lines may be out of date, so the agent compares `snapshot` with the current file before relying on line numbers.
- **Spec inside the plugin.** `claude-plugin/skills/jnote/ai-format.md` is a committed copy of `docs/ai-format.md` with one marker line on top ("Copy of docs/ai-format.md. Do not edit"). `./gradlew syncSkillSpec` regenerates it. `ClaudePluginTest` compares the copy without its marker line to the original, after normalizing CRLF to LF, so `./gradlew test` fails whenever the two drift. SKILL.md keeps only the flows and points to `${CLAUDE_PLUGIN_ROOT}/skills/jnote/ai-format.md` for every field rule, so the field rules live in one document.
- **Capture flow.** The agent collects anchors from the files and git, not from memory: `repo_url` from `git config --get remote.origin.url` (falling back to `project_path` when there is no remote; credentials in an `https://user:token@host` URL are dropped), the path relative to the IDE project folder, 1-based lines, and `snapshot` read verbatim from the file. Notes are new, so `uid` is left out. One document per request, named `<date>-<topic>.json`.
- **Raw origin URL.** `git remote get-url origin` applies `url.*.insteadOf` rewrites, while the IDE's `GitConfigReader` reads the URL as written in the config. Using the rewritten URL would anchor a note to a different project key than the open project, so the skill reads the configured value, as docs/ai-format.md specifies.
- **Publish needs a validator.** The document is written to a hidden temp file, checked with `python3 -m json.tool` or `jq empty`, and published with `ln`. When neither tool exists the check fails and nothing is published; an unchecked publish could expose a truncated file (a failed write) to the inbox reader.
- **No `version` in plugin.json.** Without it Claude Code takes the commit SHA as the version, so users get skill fixes with the repository instead of staying on a pinned copy.
- **Why `syncSkillSpec` exists.** An installed skill cannot read outside its plugin folder, so the copy is needed. A spec change is made in docs/ai-format.md, then `./gradlew syncSkillSpec` is run; `ClaudePluginTest` catches a stale copy.
- **Tests.** The tests normalize CRLF to LF before reading files, so a CRLF checkout compares equal. `ClaudePluginTest` checks the spec copy, the raw-origin and validator instructions, the manifest name, the skill frontmatter and its pointer to the spec, and decodes every JSON example in SKILL.md with the real codec (no skipped items, snapshot covers the anchored lines).

## Alternatives considered
- MCP server: a long-running process per agent session, against the lightweight principle (intent.md section 2), and intent.md already rejects it. Rejected.
- Let the skill write the DB directly: bypasses the preview, the backup, and uid validation, and races the IDE's single DB executor. Rejected by intent.md 10.2.
- Link to `docs/ai-format.md` on GitHub: no copy, but needs network and can be newer than the installed IDE plugin. Rejected.
- Make the repository root the plugin root (`source: "."`) so the skill reads `docs/ai-format.md` directly: changes the agreed layout and copies the whole repository into the plugin cache. Rejected.
- Open the DB with `immutable=1` or read-write when the read-only open fails: wrong results while the IDE writes, or a second writer. Rejected; ask the user to open the Tool Window instead.
- An automated test that runs the `sqlite3` CLI: adds a binary dependency to the suite. Rejected; the examples run through the plugin's own JDBC driver against the real view.
- A symlink from the plugin folder to `docs/ai-format.md`: not read for local sources and fragile on Windows checkouts. Rejected.

## Consequences
- The IDE side of the inbox (#17, #18) is not merged with this PR; the skill's "JNote shows it for review" text is true only once those land. Merge order is tracked as a follow-up.
- Pros
  - No process, no dependency, nothing in the IDE plugin zip.
  - Every agent write is reviewed by a person in the IDE.
  - The spec copy cannot drift silently.
- Cons and what we accept
  - A spec change needs `./gradlew syncSkillSpec` and commits two files.
  - The skill depends on the agent following instructions; the preview and the codec's validation catch what it gets wrong.
  - The DB path is configurable in the IDE, and the skill cannot read IDE settings. Recall uses `~/.jnote/jnote.db` unless the user names another path.
- Follow-up work
  - Organize, marketplace manifest and install docs (later PRs of #21).
