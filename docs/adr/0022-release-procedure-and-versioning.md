# 0022. Release procedure and versioning policy

- Status: Proposed
- Date: 2026-11-27
- Issue: #22
- PR: (docs and resources), (verifier cleanup and release config)

## Context
Registering on JetBrains Marketplace (intent.md section 3) needs a description, change notes, an icon, a license, a verified build, and a signed zip uploaded by a person. The plugin states "local only, no network, no telemetry" and must explain how to install the Claude Code skill, which the Marketplace cannot distribute (intent.md section 10.5). Constraints: no new dependency (only the IntelliJ Platform Gradle Plugin tasks already in the build), no startup work, and nothing that needs secrets stored in the repository.

## Decision
- **Description in `plugin.xml`.** The Marketplace reads the description from the plugin descriptor, and `verifyPluginProjectConfiguration` checks it. It is English first, then a Korean section after a rule, as the issue asks. It states "Local only", "no telemetry", the supported range, and the two commands to install the skill (`/plugin marketplace add Driflux/jnote`, `/plugin install jnote@jnote`), with a sentence that the skill is not part of the plugin.
- **Change notes in `plugin.xml`.** One `<change-notes>` block in English and Korean, edited by hand for each release. A generated changelog would add a build step for a one-line list.
- **Icon.** `META-INF/pluginIcon.svg` and `pluginIcon_dark.svg`, 40x40, loaded by the Marketplace and the plugin manager with no code.
- **Metadata test.** `MarketplaceMetadataTest` is a plain unit test: description starts in English and has Korean, both languages say local only and no telemetry, the install commands match the names in `.claude-plugin/marketplace.json`, change notes exist, the vendor has a URL, both icons are 40x40, and `LICENSE` is MIT. It reads the source files, so the build declares them as test inputs.
- **README.** A short `README.md` for the GitHub page repeats the local-only statement and the skill install steps.
- **Release procedure and versioning** (second PR). Semantic versions `MAJOR.MINOR.PATCH`; the first Marketplace release is `1.0.0`; `pluginVersion` in `gradle.properties` carries the number and `-SNAPSHOT` only on development builds. The signing certificate and the Marketplace token come from environment variables, never from files in the repository. Upload is manual for 1.0; the steps are in `docs/release.md`.
- **Release preconditions.** The description and change notes describe the finished 1.0. The zip is uploaded only from a build that contains every feature they name: JSON import with preview and the inbox check (#17, #18), the gutter "+" button and hover preview (#23), and the rest of the Step 1 to 6 issues. `docs/release.md` (second PR) turns this into a checklist that compares the description with the build before upload.
- **Not done by an agent.** Screenshots, the final check that the name `JNote` is free, and the first upload need a person. They are a checklist in the PR body, not invented content.

## Alternatives considered
- Description from `README.md` through a Gradle task that extracts a section: the usual template, but it adds build logic and a fragile marker. The text lives in `plugin.xml`, where the verifier reads it.
- Separate Korean listing: the Marketplace has one description per plugin, so both languages share it.
- `CHANGELOG.md` plus the Gradle changelog plugin: a new plugin dependency, which CLAUDE.md forbids without asking.
- Publish from CI on tag: needs secrets in CI and adds a way to ship by accident; for a first release a person uploads.

## Consequences
- Pros
  - Everything the Marketplace shows is in the repository and covered by tests that fail when the install commands or the local-only statement drift.
  - No new dependency, no runtime change.
- Cons and what we accept
  - Change notes are edited by hand each release.
  - The description lists features of the finished 1.0; the release checklist compares it with the build, and the upload waits for those issues.
- Follow-up work
  - Screenshots and the name check (person); upload (person).
