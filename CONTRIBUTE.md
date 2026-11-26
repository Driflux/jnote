# CONTRIBUTE.md

## Workflow
1. **Create an issue.** The issue is the unit of work (the ticket). Describe the goal, the done criteria, and the related intent.md sections.
2. Work on one issue through **one or more PRs**.
3. Keep each PR to the **smallest feature unit**. Keep it a size a reviewer can understand at once; split the PR when it grows.
4. Every feature addition comes with **test code**.
5. Every feature implementation comes with an **ADR**, linked from the PR body.

## PR rules
- Each PR links to one issue (`Closes #number` or `Refs #number`). Use `Closes` only on the last PR of an issue.
- Examples of how to split: schema and migration / CRUD / action / Tool Window / gutter / search, each understandable and testable on its own.
- Do not mix refactoring and features in one PR.
- Include in the PR body:
  - what changed and why
  - the related issue and the **ADR link** (`docs/adr/NNNN-title.md`)
  - test coverage and what was checked by running the plugin
  - measurement results if performance is affected (whether the intent.md section 2 principles hold)
- Discuss adding a dependency, adding startup work, or changing an agreed point in intent.md on the issue before opening a PR.

## Testing rules
- A feature PR must include tests for that feature. A bug fix starts with a reproducing test.
- If there are no tests, state the reason in the PR and get the reviewer's agreement (for example, docs-only or config-only PRs).
- `./gradlew test` must pass before opening a PR.

## ADR rules
- Location: `docs/adr/`. File name `NNNN-title.md`, where `NNNN` is the 4-digit number of the issue (lowercase title with hyphens). One ADR per issue, shared by all of its PRs. Copy [0000-template.md](docs/adr/0000-template.md) as the format.
- Write an ADR for every feature implementation. Include the ADR in the issue's first PR; later PRs of the same issue link to it.
- Content: context, decision, alternatives considered and why they were rejected, consequences (pros, cons, follow-ups). The goal is to record "why we did it this way".
- Do not edit ADRs; accumulate them. When a decision changes, write a new ADR and set the old one's status to `Superseded (NNNN)`.
- An ADR that changes an agreed point in intent.md also updates intent.md.

## Commit messages
- Written in Korean. The subject is one line summarizing the change; add the reason in the body if needed.
- Reference the issue number. Example: `노트 CRUD 추가 (#12)`

## Code rules
- Comments in Korean; variable and function names in English.
- Put UI strings in `JNoteBundle` and update the English and Korean files together.
- Keep to the performance principles: no startup work, no DB or file I/O on the EDT, minimal dependencies.
- Make only the requested change. Do unrelated cleanup or formatting changes in a separate PR.
