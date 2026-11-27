# 0118. Remove range bars when the note gutter icon option is off

- Status: Proposed
- Date: 2026-11-27
- Issue: #118
- PR: TBD

## Context
ADR 0023 (Range bar, PR 2) draws a bar for each multi-line note as a `RangeHighlighter` that `RangeBars` adds to the document markup model. Only `NoteLineMarkerProvider` updates the bars, from its marker pass. When the user turns off the "JNote notes" option in Settings | Editor | General | Gutter Icons, the daemon stops calling the provider, so nothing removes the bars and they stay until the file is closed. ADR 0023 recorded this as a known gap because a fix needs a settings change hook, and the plugin must not do startup work (intent.md section 2).

What the 2024.2 platform does when the option changes (read from `app-client.jar` of IC-242.20224.300 with `javap`):
- `GutterIconsConfigurable.apply` calls `LineMarkerSettings.setEnabled` for each descriptor, then `DaemonCodeAnalyzer.restart()` for every open project, then publishes `EditorOptionsListener.GUTTER_ICONS_CONFIGURABLE_TOPIC`.
- `LineMarkerSettingsImpl.isEnabled` is one map lookup by `GutterIconDescriptor.getId()`, which is the class name, falling back to `isEnabledByDefault`. A new `NoteLineMarkerProvider` instance therefore answers for the registered one.
- `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` is a public project-level topic. `DaemonListener.daemonFinished(Collection<FileEditor>)` is called when a daemon run completes.

## Decision
- `GutterNoteService` subscribes to `DAEMON_EVENT_TOPIC` on the project bus in its `init`, with the service as the parent disposable. In `daemonFinished` it asks `LineMarkerSettings` whether `NoteLineMarkerProvider` is enabled and, when it is not, calls `RangeBars.clear()`.
- `RangeBars.clear()` empties the request records and removes every applied bar highlighter on the EDT. It is the body that `dispose()` already had, without setting `disposed`. `dispose()` now sets `disposed` and calls `clear()`.
- Turning the option off is treated as an empty request for every document. No new state flag is added. Because `clear()` also empties the request records, the first marker pass after the option is turned back on is not mistaken for "no change", and the bars are drawn again through the normal `update` path.
- The subscription exists only after `GutterNoteService` has been created, which happens in the first marker pass over a project file. A project where the service was never created has no listener and no bars.
- A bar request from a pass that was already running when the option changed, and that reaches `update` after the clear, is removed by the next daemon run. The check runs on every daemon completion, so the state converges instead of depending on the order of one event.

## Alternatives considered
- **Subscribe to `EditorOptionsListener.GUTTER_ICONS_CONFIGURABLE_TOPIC`.** It fires once per Apply in the Gutter Icons page, so it costs nothing between changes. It covers only that settings page, not `LineMarkerSettings` changed by settings sync or another caller. It is also a one-shot event: a cancelled pass that calls `update` after the event leaves its bars in place with nothing to remove them. Rejected for the daemon event, which every path that changes the option and restarts the daemon reaches, and which repeats.
- **Check the option in `RangeBarRenderer.paint` and draw nothing (candidate B).** The highlighters would stay in the markup, and nothing guarantees a gutter repaint when the option changes (`reinitAllEditors` runs only for the global "Show gutter icons" switch). It also adds a settings lookup to every gutter paint of every bar. Rejected.
- **Register a listener in `plugin.xml` (an application listener or `postStartupActivity`).** It runs in every project from startup, including projects that never use JNote. Forbidden by intent.md section 2 and rejected in ADR 0009 and 0023.
- **Call `RangeBars.dispose()` when the option is off.** `dispose` sets a permanent `disposed` flag, so the bars would never come back when the option is turned on again. Rejected for `clear()`.
- **Clear only the documents of the `fileEditors` passed to `daemonFinished`.** The option is global, so every document's bars must go, and `clear()` already holds that loop. Rejected as extra code with no gain.

## Consequences
- Pros
  - Turning the option off removes the bars on the next daemon completion, which the settings page triggers itself. Turning it on brings them back with the icons.
  - No startup work, no new dependency, no I/O. The listener exists only in projects where JNote's gutter service was created.
- Cons and what we accept
  - Every daemon completion in such a project runs one `LineMarkerSettings` map lookup. While the option is off, it also runs `clear()`, which on empty maps is a `ConcurrentHashMap.clear` and a loop over an empty map. This cost is read from the bytecode, not measured.
  - Turning off the global "Show gutter icons" switch is a different setting. It hides icons but the daemon keeps calling the provider, so the bars stay. This ADR does not change that.
- Follow-up work
  - None.
