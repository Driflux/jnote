# 0022. Release procedure and versioning policy

- Status: Proposed
- Date: 2026-11-27
- Issue: #22
- PR: #106 (docs and resources), (verifier cleanup and release config: not opened yet)

## Context
Registering on JetBrains Marketplace (intent.md section 3) needs a description, change notes, an icon, a license, a verified build, and a signed zip uploaded by a person. The plugin states "local only, no network, no telemetry" and must explain how to install the Claude Code skill, which the Marketplace cannot distribute (intent.md section 10.5). Constraints: no new dependency (only the IntelliJ Platform Gradle Plugin tasks already in the build), no startup work, and nothing that needs secrets stored in the repository.

## Decision
- **Description in `plugin.xml`.** The Marketplace reads the description from the plugin descriptor, and `verifyPluginProjectConfiguration` checks it. It is English first, then a Korean section after a rule, as the issue asks. It states "Local only", "no telemetry", the supported range, and the two commands to install the skill (`/plugin marketplace add Driflux/jnote`, `/plugin install jnote@jnote`), with a sentence that the skill is not part of the plugin.
- **Change notes in `plugin.xml`.** One `<change-notes>` block in English and Korean, edited by hand for each release. A generated changelog would add a build step for a one-line list.
- **Icon.** `META-INF/pluginIcon.svg` and `pluginIcon_dark.svg`, 40x40, loaded by the Marketplace and the plugin manager with no code.
- **Metadata test.** `MarketplaceMetadataTest` is a plain unit test: description starts in English and has Korean, both languages say local only and no telemetry, the install commands match the names in `.claude-plugin/marketplace.json`, change notes exist, the vendor has a URL, both icons are 40x40, and `LICENSE` is MIT. It reads the source files, so the build declares them as test inputs.
- **Description layout.** The English block comes first, then a single `<hr/>`, then the Korean block. `MarketplaceMetadataTest` splits on that rule and expects exactly two parts, so a second `<hr/>` breaks it.
- **Gutter provider language.** `codeInsight.lineMarkerProvider` in `plugin.xml` uses `language=""`, which means every language (`Language.ANY`), so notes show in any file type.
- **README.** A short `README.md` for the GitHub page repeats the local-only statement and the skill install steps.
- **Versioning** (second PR). Semantic versions `MAJOR.MINOR.PATCH`; the first Marketplace release is `1.0.0`; `pluginVersion` in `gradle.properties` is the only place the number is set, with `-SNAPSHOT` on development builds. `publishPlugin` refuses a `-SNAPSHOT` version, and `MarketplaceMetadataTest` requires a release version to have its own `<h4>` heading in the change notes. Major means an incompatible change to the DB or to the JSON format.
- **Verifier** (second PR). `pluginVerification` in `build.gradle.kts` checks the zip against IntelliJ IDEA 2024.2 (the lower bound), 2025.2, and 2026.1 (the newest at the time). The result is compatible on all three, with deprecation notes only. They stay: each replacement API is missing in 242 (checked by compiling against 242), so removing them would raise `sinceBuild`. `docs/release.md` lists them to revisit then.
- **Tooling, not product.** `pluginVerifier()` is the Plugin Verifier tool that the IntelliJ Platform Gradle Plugin resolves for `verifyPlugin`. It is not in the plugin zip and not a runtime dependency; the shipped dependency stays `sqlite-jdbc` only.
- **Publishing.** The Marketplace token comes from the `PUBLISH_TOKEN` environment variable, never from a file in the repository. Upload is manual for 1.0; the steps and the checklist are in `docs/release.md`. Plugin signing is left out of 1.0: it needs a certificate and the signer tool, and nothing here requires it.
- **Release preconditions.** The description and change notes describe the finished 1.0. The zip is uploaded only from a build that contains every feature they name: JSON import with preview and the inbox check (#17, #18), the gutter "+" button and hover preview (#23), and the rest of the Step 1 to 6 issues. `docs/release.md` (second PR) turns this into a checklist that compares the description with the build before upload.
- **Not done by an agent.** Screenshots, the final check that the name `JNote` is free, and the first upload need a person. They are a checklist in the PR body, not invented content.

## Review decisions (#106)
- Codex flagged that the description names JSON import, the inbox check and gutter features that this snapshot does not wire in. Kept on purpose: the text describes the finished 1.0, and the release precondition above blocks the upload until the build has those features. A snapshot built from this branch is not a release.

## Alternatives considered
- Description from `README.md` through a Gradle task that extracts a section: the usual template, but it adds build logic and a fragile marker. The text lives in `plugin.xml`, where the verifier reads it.
- Separate Korean listing: the Marketplace has one description per plugin, so both languages share it.
- `CHANGELOG.md` plus the Gradle changelog plugin: a new plugin dependency, which CLAUDE.md forbids without asking.
- Publish from CI on tag: needs secrets in CI and adds a way to ship by accident; for a first release a person uploads.
- Verify only against 242: misses breakage in newer IDEs, which the "242 and later" range promises. The cost is a one-time 800 MB download.
- Replace the deprecated calls now: each replacement is missing in 242, so it would break the supported range.

## Consequences
- Pros
  - Everything the Marketplace shows is in the repository and covered by tests that fail when the install commands or the local-only statement drift.
  - No new dependency, no runtime change.
- Cons and what we accept
  - Change notes are edited by hand each release.
  - The description lists features of the finished 1.0; the release checklist compares it with the build, and the upload waits for those issues.
- Follow-up work
  - Screenshots and the name check (person); upload (person).
