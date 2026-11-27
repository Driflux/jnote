# 0018. Inbox contract

- Status: Proposed
- Date: 2026-11-27
- Issue: #18
- PR: (scan on focus and notification), (review through the import preview and move to done)

## Context
AI agents, the Claude Code skill (#21) above all, write notes by dropping JSON format v1 files (ADR 0016) into `~/.jnote/inbox/` (intent.md section 10.3). JNote must notice them without watching or polling (intent.md section 2), every write must pass the import preview (ADR 0017), and processed files must move to `inbox/done/`. Several IDE windows, possibly several IDE processes, look at the same inbox.

## Decision
- **Location.** Always `~/.jnote/inbox/`, independent of the DB path setting, so agents need no configuration. Only `*.json` regular files directly inside are pending. Other names (for example a `.json.tmp` an agent is still writing) and subfolders such as `done/` are ignored.
- **When.** Once each time the JNote tool window is activated, including the first open. The factory subscribes to `ToolWindowManagerListener` on the project message bus with the panel as the disposable when it creates the content. Nothing is declared in plugin.xml, so there is no startup work. If the tool window is never opened in a session, the inbox is never checked; that is intended for 1.0 ("when the Tool Window gains focus"). The trigger is tool-window activation, not #20's finer "focus moved into the panel" rule (#91), because a directory listing is cheap and a repeated notification is suppressed anyway. This keeps the change out of `JNotePanel`.
- **Scan.** `InboxCheck` is an application service (one inbox for all windows) created on first request. A scan lists the directory and reads file attributes only (name, size, modification time) on a pooled thread; it never reads contents. One scan runs at a time. Requests that arrive during a scan collapse into one rescan afterwards.
- **Notification.** When files are waiting, one notification in the window that was activated says how many. Each window (project) keeps its own: the same set of files (same names, sizes, and times) is not announced again in that window while its notification is still shown, a changed set replaces it, and an empty inbox expires it. Another window still gets its own notification, so Review is reachable where the person is. A dismissed notification comes back on the next activation if the files are still there. A failure while showing a notification cannot leave the scan state stuck.
- **Review and claim (second PR).** The notification's Review action first claims each listed file by an atomic move into `inbox/processing/` under a unique name. Only one window can claim a file; a file already gone was claimed by another window (or removed) and is skipped silently. The claimed files are then read, planned, and previewed through the import flow (ADR 0017). Claiming before reading means the file that is imported is exactly the file that is later archived, and two windows can never apply the same document, which matters for items without `uid` (each apply would create new notes).
- **Finish (second PR).** The import flow reports exactly one outcome for every ending (`ImportOutcome.Finished` with the decoded sources, or `NotApplied` for decline, cancel, failure, and a closed project). After `Finished`, each claimed file whose document was decoded moves to `inbox/done/`, including one whose invalid items were skipped (the notification lists them and the file stays readable in `done/`). A rejected or unreadable file and every file of a cancelled or declined review move back to the inbox so the person can fix or review them again. Moves never overwrite: the target name is first reserved with an exclusive create, and a taken name gets `-1`, `-2`, and so on, so two windows finishing at once keep both files.
- **Crash.** A file left in `processing/` by a crash is not listed again; the person moves it back by hand. This is rare and loses nothing.
- **Agent guidance.** `docs/ai-format.md` tells agents to write under a temporary name and rename to `.json` when complete, so JNote never reads a half-written file.

## Alternatives considered
- A file watcher (`WatchService` or VFS listener): background work for the whole session even when nobody uses JNote. Rejected by the no-watching rule.
- Polling on a timer: the same, plus idle CPU. Rejected.
- Hook into #91's focus-entry listener in `JNotePanel`: couples two stacks in the same file. Rejected for now (coordinator decision); tool-window activation covers "gains focus" for this purpose.
- Read and count notes during the scan: the scan would read every file on every activation. Rejected; the notification counts files.
- Delete processed files instead of moving them: loses what the agent wrote. Rejected by intent.md section 10.3.
- Overwrite same-named files in `done/`: loses an earlier batch. Rejected.

## Consequences
- Pros
  - Zero cost until the tool window opens, and then a directory listing per activation.
  - Every inbox write still passes the preview and the pre-import backup.
- Cons and what we accept
  - Files that arrive while the tool window stays active are noticed on the next activation, not immediately.
  - A rejected file keeps its place in the inbox and its notification comes back on activation until the person fixes or removes it.
- Follow-up work
  - #21 skill writes into the inbox following `docs/ai-format.md`.
