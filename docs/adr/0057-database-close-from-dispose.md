# 0057. Closing the database from dispose

- Status: Proposed
- Date: 2026-11-27
- Issue: #57
- PR: #85

## Context
`Database.close()` submits a close job to the DB thread and waits up to 10 s (`CLOSE_TIMEOUT_MS`) for it. It throws `TimeoutException` when a running job outlives the wait, and `ExecutionException` when `connection.close()` fails. `DatabaseService.dispose()` is called on plugin unload and IDE exit, possibly on the EDT, which must never wait that long and must not throw (intent.md section 2). ADR 0002 (superseded by 0045 for migrations) did not say how close behaves.

## Decision
- **Thread.** `dispose()` does not close on its calling thread. It hands the `Database` it detached to a pooled thread and returns at once, so the EDT never waits for the DB thread. This was already the case; this ADR records why.
- **Wait limit.** `close()` keeps the 10 s limit. In production it only runs on background threads: the pooled thread from `dispose()`, the switch task (`DbPathSwitcher`), and the throwaway probe in `requireUsable`. `DatabaseService.switchTo` also closes synchronously; it has no production caller today and its contract says background only. After the limit the executor is shut down anyway: the queued close job still runs when the running job ends, and new jobs fail as closed. If that late close job fails, `Database` logs it as a warning, because the caller has already stopped waiting.
- **Failures.** On the dispose path a close failure (timeout or a failing `connection.close()`) is caught and logged as a warning by `DatabaseService.closeAndLog`. Nobody can act on it during unload or exit, and an error-level log would show an IDE error to the user. Before this change the failure was kept in an unobserved `Future` and lost silently. If the close was interrupted, the pooled thread keeps its interrupt flag set so the pool can see it.
- **Other callers.** `DbPathSwitcher` still receives the exception from `close()` and reports it as `OldDatabaseNotClosed` (ADR 0015), because there the user is changing the path and can restart the IDE.

## Alternatives considered
- Shorten the wait: a slow job (a large relocation write) can legitimately take seconds, and the wait no longer blocks the EDT, so a shorter limit only adds false timeouts.
- Close asynchronously inside `Database.close()` itself: `DbPathSwitcher` needs to know whether the old file was released to word its notification.
- Swallow without logging: hides a file that stays locked until the process exits.

## Consequences
- Pros
  - `dispose()` never blocks the EDT and never throws because of the database.
  - A close failure during unload leaves a trace in idea.log.
- Cons and what we accept
  - A pooled thread can wait up to 10 s on exit behind a slow job.
- Follow-up work
  - None.
