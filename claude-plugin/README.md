# JNote plugin for Claude Code

This folder is a Claude Code plugin with one skill, `jnote`. The skill writes JNote JSON format v1 documents into `~/.jnote/inbox/`. A JNote IntelliJ build with the inbox import notifies the user when the Tool Window opens or is re-activated, and saves nothing until they click Apply in its preview. A build without it leaves the files waiting in the inbox. The plugin never touches the database file and starts no process.

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
