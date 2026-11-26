# DEVELOPMENT.md

## Tech stack
- Kotlin, Gradle (IntelliJ Platform Gradle Plugin 2.x), JDK 21 for Gradle and the toolchain
- Builds against `intellijIdeaCommunity("2024.2")`; the first build downloads that IDE
- Target: IntelliJ IDEA 2024.2 (`sinceBuild = 242`) and later, `untilBuild` unset
- DB: SQLite (depends only on `sqlite-jdbc`)
- Uses only common platform APIs (no Java plugin dependency).

## Common commands
```bash
./gradlew runIde          # run the plugin in a sandbox IDE
./gradlew test            # unit / integration tests
./gradlew verifyPluginProjectConfiguration  # validate the plugin project configuration
./gradlew verifyPlugin    # compatibility check with Plugin Verifier (needs pluginVerification IDEs, not configured yet)
./gradlew buildPlugin     # build the distribution zip (build/distributions)
```

## Package layout (planned)
```
src/main/kotlin/io/github/driflux/jnote
  db/          SQLite access, schema, migrations (single-thread executor)
  model/       Note, NewNote, NoteKind, NoteStatus, Tag
  anchor/      code anchor (CodeAnchor, line range, relative path, snapshot, class name resolver)
  relocate/    relocation (exact match, similarity, file path recovery)
  project/     project key computation (git origin, commondir)
  ui/          Tool Window, dialogs, gutter icon
  action/      actions such as "add note"
  settings/    DB path setting
  ai/          JSON format v1, import (upsert, preview), inbox check, copy for AI
  JNoteBundle.kt
src/main/resources/messages/JNoteBundle.properties, JNoteBundle_ko.properties
src/test/kotlin/...
docs/adr/      design decision records
docs/ai-format.md      JSON format spec (single source for AI integration)
claude-plugin/         Claude Code plugin (skills/jnote/SKILL.md)
.claude-plugin/marketplace.json   Claude Code marketplace manifest for this repo
mockup/ui.html UI mockup
```

## Development principles
- Follow the performance principles in [intent.md](intent.md) section 2, especially no startup work and a non-blocking EDT.
- Access the DB only through the single-thread executor in `db/`. Update the UI with `invokeLater`.
- When changing the schema, add a migration SQL (bump `user_version`) and write a migration test with it.
- UI strings use `JNoteBundle` keys only; update the English and Korean files together.
- Language-specific code (class-name extraction, etc.) sits behind an interface; 1.0 ships only the common PSI implementation.

## Testing
- Every feature comes with tests.
- Prefer pure unit tests that run without the IDE for `db/`, `relocate/`, and `project/` (using SQLite files in a temp directory).
- Verify UI and editor integration with the IntelliJ test framework (`BasePlatformTestCase`), and note in the PR what you checked by running (`runIde`).

## Performance measurement
- Relocation benchmark: put 20 displaced notes in a 5,000-line file and measure background processing time (step 5).
- Check that opening a file with no notes costs no more than an in-memory cache lookup.
- If the target is missed, reduce the feature or make it manual (intent.md section 6).

## Local data
- Default DB: `~/.jnote/jnote.db`. During development, change the DB path in the sandbox IDE settings to a temporary location so real data is untouched.
- Keep only one pre-migration backup, `jnote.db.bak`.
