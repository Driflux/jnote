---
name: stack-pr
description: Review, fix, commit, push, sync, merge, and clean up one stacked PR in its own worktree, then repeat for the next. Use as /stack-pr <PR number>
disable-model-invocation: true
---

# stack-pr

Process pull requests stacked with `gh stack` from the bottom up, one at a time, and **repeat until no open PR is left**. With a PR number, start at that PR; without one, start at the lowest open PR whose base is `main`. Finish steps 0-9 for one PR, then step 10 moves to the next.
Sources of truth: the main repository is the first line of `git worktree list`, PR facts come from `gh pr view`, and test commands are in DEVELOPMENT.md. Look up anything not written here.
Requires the `pstack` plugin (`pstack:poteto-agent`, `poteto-mode`).

## Language
Follow CLAUDE.md. Reports to the user, code comments, and commit messages are Korean. Issues, PR bodies, and ADRs are English.

## Stop conditions
Invoking `/stack-pr` authorizes commit, push, `gh stack sync` (rebases and force-pushes every branch above), merge, and cleanup. **Stop, show the situation, and ask the user** when any of these happens:
- A row to fix in this PR is `ask` (the fix direction needs a decision).
- A subagent reports that it is blocked, or its report differs from the tests the main agent ran itself.
- A fix collides with a design decision or `intent.md`.
- CI fails or the PR is `BLOCKED`; step 6 finds an unfamiliar commit or a count mismatch; a rebase conflict is not trivial.
- A worktree has uncommitted changes or unpushed commits.
If the user narrows the scope ("review only", "in the worktree only"), stop at that scope.

## 0. Un-draft
- If `gh pr view <n> --json isDraft` is `true`, run `gh pr ready <n>` and then work.
- **Done when:** `isDraft` is `false`.

## 1. Prepare the worktree
- Path: `~/worktrees/<repo>/<type>/<issue>`. The type is the branch prefix (`feat`, `fix`) and the issue is the number in the branch name. `feat/12-2` also uses `feat/12`.
- After `git fetch origin`, if `git diff` between the local branch and `origin/<branch>` is 0 lines, run `git branch -f <branch> origin/<branch>` and `git worktree add`. If it is not 0 lines, stop and report.
- **Done when:** `git log origin/main..HEAD` shows only the PR's commits and the worktree is clean.

## 2. Review
- Read the PR body (`gh pr view <n> --json title,body`), `git diff --stat origin/main...HEAD`, and every changed file. Then run the full test suite.
- Confirm each suspicion with a scratch test and delete that test afterwards. Mark what you could not confirm as `suspected`.
- Report format (Korean): **Key changes** (3-7, with `file:line` evidence), then a **Findings** table (ID, location, severity, finding, evidence `proven|traced|suspected`, suggestion, Action `fix|ask`), then **Repro**, then **Notes** (say that `runIde` and CI were not checked).
- A security, data, migration, or concurrency row with more than one possible fix has Action `ask`.
- **Classify** each row as one of two:
  - **Fix in this PR:** critical (severity high or above, or a security issue, data loss, or wrong result) or a small change (a few lines that read at once: docs, tests, comments, names).
  - **Make an issue:** everything else (large, design-dependent, or outside this PR). When unsure, make an issue.
- **Create issues.** Search with `gh issue list --search` for a duplicate, then `gh issue create`. Write title and body in English. The body has the location (`file:line`), the finding, the evidence, the suggestion, and the original PR number. Do not pick labels or assignees. Write the issue number into the row.
- **Done when:** every changed file was read, the test result is in the report, and every row is either "fixed in this PR" or an issue number. If a row to fix is `ask`, stop and ask; otherwise go to step 3. With nothing to fix, go to step 4.

## 3. Fix (subagent)
A subagent implements; the main agent reviews, runs `git`/`gh`, and verifies.
- Always use `pstack:poteto-agent`, a fresh one per task, never `general-purpose`. Put the absolute path of poteto-mode's `SKILL.md` in the brief (find it with `ls -d ~/.claude/plugins/cache/*/pstack/*/skills/poteto-mode/SKILL.md`).
- The brief contains: the absolute worktree path (work only inside it), the rows to fix (ID, location, finding, suggestion), the rules (write the test first and watch it fail, then fix; test-only rows are exempt; docs such as ADRs in English; the comment rules of step 4; edit files and run tests only), and what to return (changed files, test results before and after the fix, blockers).
- Do not trust the agent's report as is. Read `git diff` and run the full test suite yourself.
- A fix that depends on threads or timing can pass or fail from run to run. Revert to the old code, confirm the test fails, restore the fix, and confirm it passes, repeating with `./gradlew test --rerun --tests '<test>'`. `cleanTest test` can be served from the build cache without running, so it is no evidence of repeated passes. `thenCompose` on an already completed future runs on the calling thread; pin the thread with `thenComposeAsync(..., executor)`.
- **Done when:** in the tests the main agent ran, the new test fails before the fix (confirming the agent's report) and passes after it, and the full suite passes. A blocker report from the agent is a stop condition.

## 4. Comment cleanup
Put this step's rules into the step 3 brief so the same agent continues.
- In the files the PR changed, delete comments that only say what the code does. Keep comments that give a reason, a constraint, a calling condition (for example "call off the EDT"), or a unit rule. If the user said "in the worktree only", do not commit.
- **Done when:** tests pass, and the report gives the number of deleted lines and the kinds of comments kept.

## 5. Commit and push
- Commit messages are Korean and end with `(#issue)`. Split by logical unit (code plus tests, docs).
- `git push origin <branch>` must be a fast-forward. If it is rejected, stop and report.

## 6. Sync
Run in the main repository. After a merge, GitHub realigns the rest of the stack, so local branches may hold the old history, and syncing that would overwrite new remote commits.
1. After `git fetch origin`, compare local and `origin/<branch>` for each stack branch. For branches that differ, count the `+` lines of `git cherry origin/<b> <b>`.
2. If the `+` count is **the same on every branch** and the commit titles belong to an already merged PR, the branches hold no work of their own: run `git branch -f <b> origin/<b>`. If the counts differ or a title is unfamiliar, stop and report.
3. Run `gh stack sync`. On `could not determine the previous base of X`, move only X with `git rebase --onto <new parent> <old parent tip> X` and sync again.
4. Resolve only trivial rebase conflicts (a comment line, a bundle key) and report what was resolved and how. For a logic conflict, show it and ask.
5. If a push is rejected, use `gh stack push` (`--force-with-lease`), only inside the approved scope.
- **Done when:** every stack branch has local == `origin`, and the full suite passes at the top of the stack.

## 7. Merge
- Wait for CI **bound to the SHA you just pushed**. Right after a push, the previous commit's `CLEAN` can linger, so `mergeStateStatus` alone passes the PR before CI for the new commit has started. Wait until `gh run list --branch <branch> --json headSha,status,conclusion` shows runs for that SHA and all are `completed success`, then check `CLEAN`. On failure or `BLOCKED`, stop and report.
- `gh stack merge <n> --squash --yes`. `gh pr merge` refuses stack PRs.
- **Done when:** the PR is `MERGED`, the next PR's base is `main`, and the `Closes` issue is closed. Also report whether the next PR is `MERGEABLE`.

## PRs not registered in the stack
A branch chain missing from `gh stack view` (a PR whose base is another PR's branch) cannot use `gh stack sync` or `gh stack merge`. The links inside the chain hold, but the bottom PR sits on old history that is already merged.
- Move the bottom branch with `git rebase --onto origin/main <old base tip> <branch>`. Find the old base tip in `git log` as the **pre-rebase** hash of the merged PR's last commit. Move each upper branch with `--onto`, one at a time, from the old tip of the branch below it.
- Push with `--force-with-lease=<branch>:<expected remote tip>`. Change the PR base with `gh pr edit <n> --base main` and merge with `gh pr merge <n> --squash`.
- After the merge, move the next PR onto `origin/main` the same way and set its base to `main`. A code conflict is a stop condition.
- A branch that conflicts on any base waits until the earlier PR is merged and is resolved separately. When resolving, check with the relevant test that an earlier fix (for example `thenComposeAsync`) was not reverted.

## 8. Clean up
- Only when the worktree is clean and local == `origin`, run `git worktree remove <path>` and `git branch -D <branch>` (a squash merge makes `-d` fail). Leave the remote branch.
- A worktree with uncommitted changes or unpushed commits is not removed; report it.
- If the session's working directory is the worktree to remove, add a `/cd` hint.

## 9. Update main
- In the main repository, run `git pull --ff-only` and report how many files came in.

## 10. Next PR
- Run `gh stack view` to see whether open PRs remain. If so, start again at step 0 with the lowest PR, after checking that GitHub changed that PR's base to `main` after the merge.
- With no open PR, end the loop and summarize once: the PRs handled, the issue numbers created, and the CI and `runIde` results that were not checked.
- **Done when:** there are 0 open stack PRs, or the reason for a stop condition was reported.
