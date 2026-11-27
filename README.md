# JNote

A lightweight personal code-note plugin for IntelliJ IDEA. Select code, attach a note, and find it again from one search box. Notes stay in a local SQLite file: no network calls, no telemetry.

- Notes follow the code when lines move (exact match, then approximate match, otherwise kept as orphans).
- Free notes (queries, memos) live next to code notes; tags are a registered dictionary.
- Supports IntelliJ IDEA 2024.2 (build 242) and later. MIT license.

Design and rules: [intent.md](intent.md), [DEVELOPMENT.md](DEVELOPMENT.md), [CONTRIBUTE.md](CONTRIBUTE.md). Release steps: [docs/release.md](docs/release.md).
## Use with Claude Code (optional)

The JetBrains plugin never calls an AI. A separate Claude Code skill, `jnote`, saves, recalls, and organizes notes through the import preview. It is distributed from this repository, not in the plugin zip:

```
/plugin marketplace add Driflux/jnote
/plugin install jnote@jnote
```

Details: [claude-plugin/README.md](claude-plugin/README.md). The JSON format is specified in [docs/ai-format.md](docs/ai-format.md).
