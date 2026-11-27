# JNote plugin for Claude Code

This folder is a Claude Code plugin with one skill, `jnote`. The skill reads notes only through the `v_notes_export` view with a read-only `sqlite3` connection, and writes JNote JSON format v1 documents into `~/.jnote/inbox/`, which the JNote IntelliJ plugin shows for review before anything is saved. It never writes the database file and starts no process.

## Install

In Claude Code:

```
/plugin marketplace add Driflux/jnote
/plugin install jnote@jnote
```

The marketplace file is `.claude-plugin/marketplace.json` at the repository root. Updates follow the repository: run `/plugin marketplace update jnote`, or turn on auto-update for the marketplace in `/plugin`. After an update, run `/reload-plugins` or start a new session to use the new version.

The skill needs the JNote IntelliJ plugin, which imports what the skill writes, and `sqlite3` 3.44 or newer for recall and organize. Ask Claude Code things like "save this in JNote", "what do my JNote notes say about this file?", or "clean up the tags of my JNote notes".

This plugin is separate from the JetBrains Marketplace plugin and is not part of its zip.

## Layout

- `.claude-plugin/plugin.json`: the plugin manifest.
- `skills/jnote/SKILL.md`: the skill.
- `skills/jnote/ai-format.md`: a copy of [docs/ai-format.md](../docs/ai-format.md), the format spec. An installed plugin can read only files inside this folder, so the skill reads this copy.

## Updating the spec copy

`docs/ai-format.md` is the only source. Never edit the copy. After changing the original, run:

```
./gradlew syncSkillSpec
```

`SkillSpecCopyTest` fails while the copy differs from the original, so `./gradlew test` catches a forgotten sync.

## Trying it locally

```
claude plugin validate claude-plugin
claude --plugin-dir claude-plugin
```
