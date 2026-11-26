# DEVELOPMENT.md

> Once the project skeleton (step 1) exists, verify the commands below against the real state and update this file.

## Tech stack
- Kotlin, Gradle (IntelliJ Platform Gradle Plugin 2.x)
- Target: IntelliJ IDEA 2024.2 (`sinceBuild = 242`) and later, `untilBuild` unset
- DB: SQLite (depends only on `sqlite-jdbc`)
- Uses only common platform APIs (no Java plugin dependency).

## Common commands
```bash
./gradlew runIde          # run the plugin in a sandbox IDE
./gradlew test            # unit / integration tests
./gradlew verifyPlugin    # compatibility check with Plugin Verifier
./gradlew buildPlugin     # build the distribution zip (build/distributions)
```

## Package layout (planned)
```
src/main/kotlin/.../jnote
  db/          SQLite access, schema, migrations (single-thread executor)
  model/       Note, Tag, NoteStatus
  relocate/    relocation (exact match, similarity, file path recovery)
  project/     project key computation (git origin, commondir)
  ui/          Tool Window, dialogs, gutter icon
  action/      actions such as "add note"
  settings/    DB path setting
  JNoteBundle.kt
src/main/resources/messages/JNoteBundle.properties, JNoteBundle_ko.properties
src/test/kotlin/...
docs/adr/      design decision records
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
