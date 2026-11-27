# Release procedure

How a JNote version gets to JetBrains Marketplace. Decisions and alternatives: [ADR 0022](adr/0022-release-procedure-and-versioning.md). Upload is manual for 1.0.

## Versioning
- Semantic versions: `MAJOR.MINOR.PATCH`. The first Marketplace release is `1.0.0`.
- `pluginVersion` in `gradle.properties` is the only place the number is set. Development builds keep `-SNAPSHOT`; `publishPlugin` refuses a `-SNAPSHOT` version.
- Compatibility range: `sinceBuild = 242`, `untilBuild` unset (intent.md section 3).
- Patch: bug fixes. Minor: new features, DB migrations that keep older data readable. Major: an incompatible change to the DB or to the JSON format (`docs/ai-format.md`).

## Before release (checklist)
1. Every issue of the release is merged, including JSON import with preview and the inbox check (#17, #18) and the gutter "+" button and hover preview (#23). Read the description in `plugin.xml` against the build in `runIde`; remove any claim the build does not meet.
2. Set `pluginVersion` to the release number (no `-SNAPSHOT`) and add a `<h4>x.y.z</h4>` entry to `<change-notes>` in `plugin.xml`, in English and Korean. `MarketplaceMetadataTest` fails if the heading is missing.
3. `./gradlew test verifyPluginProjectConfiguration verifyPlugin`. `verifyPlugin` checks the zip against IntelliJ IDEA 2024.2, 2025.2, and 2026.1 (declared in `build.gradle.kts`); the first run downloads about 800 MB of IDEs. Known deprecation notes are listed below; anything new is a finding.
4. Do the manual checks of the performance principles (intent.md section 2): no startup activity, no EDT I/O, the 5,000-line benchmark in DEVELOPMENT.md.
5. `./gradlew buildPlugin` and install `build/distributions/*.zip` into a clean IDE (Settings | Plugins | Install Plugin from Disk) to see the listing text, icon, and the plugin working.

## Things a person does
- Take screenshots (tool window with notes, the add-note dialog, gutter icon, search) in `runIde` and add them in the Marketplace listing form.
- Check that the name `JNote` is free on Marketplace (a similar plugin, Code Notes, exists) and register the plugin.
- Upload the first version through the Marketplace web form with `build/distributions/*.zip`.
- Later versions can use `./gradlew publishPlugin` with the token in the `PUBLISH_TOKEN` environment variable. Never put a token in the repository.
- Tag the release `vX.Y.Z` on the commit that was uploaded.

## Plugin Verifier notes
Run on the 0.1.0-SNAPSHOT build: compatible with 242, 252, and 261, with no errors, no internal or experimental API, and deprecation notes only. They stay because the replacements do not exist in 242:
- `JsonReader.setLenient` (Gson, deprecated for `setStrictness`, which needs Gson 2.11).
- `Row.textFieldWithBrowseButton(String)` in the settings page (the replacement takes a `FileChooserDescriptor` overload that 242 lacks).
- `ReadAction.compute` (5 places, deprecated for `computeBlocking`, missing in 242).
- `DaemonCodeAnalyzer.restart(PsiFile)` (261 only; the reason-taking overload is not in 242).
Revisit them when `sinceBuild` is raised.
