# 0122. Gate the capture skill on the inbox import

- Status: Proposed
- Date: 2026-11-27
- Issue: #122
- PR: TBD

## Context
The `jnote` Claude Code skill (ADR 0021, intent.md section 10.5) writes JSON documents into `~/.jnote/inbox/`. Its introduction and Capture step 5 told the user that JNote shows the file for review when the Tool Window gains focus. The IDE side of that promise (intent.md section 10.3) is file and clipboard import (#17) and the inbox check (#18). Both are still in open PRs, so the plugin built from `main` never reads the inbox. A user who installs the skill today is told a review will appear, and none does. The plugin version is `0.1.0-SNAPSHOT` with no release, so no version number can name the build that has the import.

## Decision
The skill states the gate in words. Capture step 5 tells the user the file name and what happens next in each case. In a JNote build with the inbox import, the review opens when the Tool Window gains focus and nothing is saved until Apply. In a build without it, JNote does not read the inbox, and the file stays there until a build with the import processes it. The introduction says the same in one sentence. The feature is named, not a version. The rule that the skill never writes the database, and the agreed design in intent.md section 10.3, do not change.

## Alternatives considered
- **Wait for the import PRs to merge.** The skill would then be correct without edits, but it ships wrong until #17 and #18 land, and their timing is not tied to this track. Rejected.
- **Remove the capture flow until the import lands.** The skill would only claim what works. But files written now are valid format v1 and will import once the feature exists, and intent.md section 10.5 lists capture as one of the skill's flows. Removing it and adding it back is two changes for no gain over a correct sentence. Rejected.
- **Name a version.** No released version has the import, so any number would be invented. Rejected.

## Consequences
- Pros
  - The skill no longer promises a review the installed plugin cannot show.
  - Capture keeps working as a way to queue notes for a later build.
- Cons and what we accept
  - The user reads two cases and has to know which build they run.
- Follow-up work
  - When #18 merges, rewrite these sentences to describe only the working flow.
  - The organize flow PR (#104) also writes into the inbox and may need the same gate.
