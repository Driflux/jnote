# 0004. Project key

- Status: Proposed
- Date: 2026-11-26
- Issue: #4
- PR: #30, #31

## Context
Notes belong to a project. The same repository can be open as several clones and as linked worktrees, and all of them should show the same notes (intent.md section 4). The key therefore cannot be the project path. It must come from the git `origin` URL, which every clone and worktree shares.

Constraints from intent.md section 2: no startup work, no process spawned, no file I/O on the EDT, no new dependency.

## Decision
- **Key.** The normalized `origin` URL, in the shape `host/path`. `ProjectKey(key, displayName)`, where `displayName` is the last path segment (`repo`).
- **Normalization (`RepoUrl.normalize`).** These all become `github.com/org/repo`: `git@github.com:org/repo.git`, `ssh://git@github.com:2222/org/repo.git`, `https://user:token@github.com/org/repo.git`, `https://github.com/org/repo/`, `git://github.com/org/repo`.
  - Strip the scheme, credentials, and port: the same repository is reached over ssh and https with different users and ports, and credentials must never end up in the DB.
  - Lowercase the host: host names are case-insensitive.
  - Keep the path case: some servers treat the path as case-sensitive, and merging two repositories is worse than splitting one.
  - Strip a trailing slash and the `.git` suffix: clients accept both spellings.
  - Return `null` for input without a host or path (local paths, `file://`, empty text). A one-letter host before `:` is not an scp-style URL, so a Windows path like `C:\work` is not mistaken for one.
- **Reading git state without a process.** `GitConfigReader` only reads files.
  1. `findGitDir` walks up from the project path to the first `.git`. A directory is the git dir. A file holds `gitdir: <path>` (relative to the file or absolute) in a linked worktree. The walk stops at the user home directory and never accepts `~/.git`: with dotfiles kept in a git repo at home, every non-git project below it would otherwise take that repo's origin. A repository nested inside home is still found.
  2. `resolveCommonDir` reads `commondir` in that git dir (relative to it, or absolute) to reach the main `.git`. Without the file, the git dir is its own common dir.
  3. `readOriginUrl` parses `config` in the common dir: sections, a quoted subsection, comments (`#`, `;`, outside quotes), and surrounding whitespace. Only `[remote "origin"]` counts, and the first `url` wins. The section name is case-insensitive and the subsection name is case-sensitive, as in git.
- **Fallback.** With no git dir, no `origin`, an unnormalizable URL, or an unreadable config, the key is the project base path and the display name is its last segment. A path key starts with `/` or a drive letter and a URL key starts with a host, so they do not collide in practice.
- **Failure handling.** Every read error becomes "no value" and leads to the fallback. A broken config must never break the plugin. This includes a corrupt `commondir` or `gitdir:` value that is not a valid path (for example with a NUL character).
- **Untrusted input.** The config, `commondir`, and `.git` files come from the opened project. Each must be a regular file and at most 64 KB, otherwise it counts as no value. `RepoUrl` strips credentials at the last `@` of the authority only, and returns `null` when what remains has a non-numeric port, so a password containing `/` cannot leak into the key.
- **When it is computed.** Lazily, on the first request, then cached (second PR). Computing it when the project opens would need a startup hook, which the no-startup-work rule forbids. Lazy on first use keeps the "compute once, cache" intent and costs nothing for a project that never touches notes. The service is a light `@Service(Service.Level.PROJECT)` class with no `plugin.xml` entry.
- **Threading.** `resolve()` does file reads, so callers must be off the EDT. The service fails fast if called on the EDT in unit-test mode.

## Alternatives considered
- Run `git config --get remote.origin.url`: simplest, but spawns a process, which is slow and needs git on the PATH. Rejected by the no-process rule.
- Use the IDE's git4idea API: it needs the Git plugin as a dependency, so the plugin would not load in IDEs without it. Rejected (platform-only modules, ADR 0001).
- Key on the project path: worktrees and clones would not share notes. Used only as the fallback.
- Compute on project open: needs `postStartupActivity` or a listener. Rejected, see Decision.
- A general git-config parser library: a new dependency for one value. Rejected.

## Consequences
- Pros
  - Worktrees and clones share notes. No process, no dependency, no startup cost.
  - Normalization and parsing are pure functions, tested on temp directories.
- Cons and what we accept
  - The parser handles only what `origin.url` needs. It ignores `include` directives, `url.<base>.insteadOf` rewriting, line continuations, and the old `[remote.origin]` syntax. An `insteadOf` user may get a different key than the plain URL, which only splits notes and loses none.
  - Changing the origin URL changes the key. Existing notes keep the old key; re-keying is not part of this issue.
  - Hosts that serve one repository under different names (aliases, mirrors) get different keys.
- Follow-up work
  - Notes stored under a changed key may need a migration or rebind action (not planned).