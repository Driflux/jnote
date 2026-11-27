# 0118. Remove range bars when the note gutter icon option is off

- Status: Proposed
- Date: 2026-11-27
- Issue: #118
- PR: TBD

## Context
ADR 0023 (Range bar, PR 2) draws a bar for each multi-line note as a `RangeHighlighter` that `RangeBars` adds to the document markup model. Only `NoteLineMarkerProvider` updates the bars, from its marker pass. When the user turns off the "JNote notes" option in Settings | Editor | General | Gutter Icons, the daemon stops calling the provider, so nothing removes the bars and they stay until the file is closed. ADR 0023 recorded this as a known gap because a fix needs a settings change hook, and the plugin must not do startup work (intent.md section 2).

What the platform does when the option changes (read with `javap` from `app-client.jar` of IC-242.20224.300, the build this plugin compiles against; a review also found the same flow in IC-252.23892.409):
- The Apply path is Settings | Editor | General | Gutter Icons, then Apply. `GutterIconsConfigurable.apply` calls `LineMarkerSettings.setEnabled` for each descriptor, then `DaemonCodeAnalyzer.restart()` for every open project, then publishes `EditorOptionsListener.GUTTER_ICONS_CONFIGURABLE_TOPIC`.
- `LineMarkerSettingsImpl.setEnabled` and `loadState` only update the settings map. They do not restart the daemon. A caller other than the Gutter Icons page (Settings Sync, for example) restarts the daemon only if it does so itself. What Settings Sync does here was not checked.
- `LineMarkerSettingsImpl.isEnabled` is one map lookup by `GutterIconDescriptor.getId()`, which is the class name, falling back to `isEnabledByDefault`. A new `NoteLineMarkerProvider` instance therefore answers for the registered one.
- `DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC` is a public project-level topic. `DaemonListener.daemonFinished(Collection<FileEditor>)` is called when a daemon run completes.

## Decision
- `GutterNoteService` subscribes to `DAEMON_EVENT_TOPIC` on the project bus in its `init`, with the service as the parent disposable. In `daemonFinished` it calls `RangeBars.clearIfIconsOff()`.
- `RangeBars` owns the one check of the option, `iconsOn()`, which asks `LineMarkerSettings` whether `NoteLineMarkerProvider` is enabled. Both the daemon listener and `apply` go through it, so the two cannot disagree about what "off" means.
- `clearIfIconsOff()` reads the option on the calling thread and, only when it is off, schedules one EDT task. That task checks `iconsOn()` again and, if the option is still off, runs the private `clear()`, which empties the request records and removes every applied bar highlighter. The check and both removals happen in that one EDT task, so a cleanup that was queued while the option was off and runs after it is back on does nothing. It cannot erase a request recorded, or a bar drawn, after the option came back on. `dispose()` sets `disposed` and runs `clear()` in an EDT task the same way, without the option check.
- `apply`, which runs on the EDT, checks `iconsOn()` before it adds the bars of a non-empty request. When the option is off, it removes that request from the records (`requested.remove(document, bars)`, the same drop it already does for a stale document stamp) and adds nothing.
- Turning the option off is treated as an empty request for every document. No new state flag is added. Because `clear()` also empties the request records, the first marker pass after the option is turned back on is not mistaken for "no change", and the bars are drawn again through the normal `update` path.
- The subscription exists only after `GutterNoteService` has been created, which happens in the first marker pass over a project file. A project where the service was never created has no listener and no bars.
- A bar request from a pass that was already running when the option changed can reach `update` after the clear and record itself again. The check in `apply` drops it on the EDT, so no bar appears and no later daemon completion is needed. If the option is turned off after `apply` added a bar, the Apply path restarts the daemon and the next completion clears it. The two checks together converge on "no bars while off".

## Alternatives considered
- **Subscribe to `EditorOptionsListener.GUTTER_ICONS_CONFIGURABLE_TOPIC`.** It fires once per Apply in the Gutter Icons page, so it costs nothing between changes. It covers only that settings page. A change to `LineMarkerSettings` from settings sync or another caller would leave the bars until the file is closed, while the daemon listener removes them at the next completion. Rejected for the daemon event, which every path that changes the option and restarts the daemon reaches, and which repeats.
- **Check the option in `RangeBarRenderer.paint` and draw nothing (candidate B).** The highlighters would stay in the markup, and nothing guarantees a gutter repaint when the option changes (`reinitAllEditors` runs only for the global "Show gutter icons" switch). It also adds a settings lookup to every gutter paint of every bar. Rejected.
- **Register a listener in `plugin.xml` (an application listener or `postStartupActivity`).** It runs in every project from startup, including projects that never use JNote. Forbidden by intent.md section 2 and rejected in ADR 0009 and 0023.
- **Call `RangeBars.dispose()` when the option is off.** `dispose` sets a permanent `disposed` flag, so the bars would never come back when the option is turned on again. Rejected for `clear()`.
- **Clear only the documents of the `fileEditors` passed to `daemonFinished`.** The option is global, so every document's bars must go, and `clear()` already holds that loop. Rejected as extra code with no gain.
- **Rely on the daemon listener alone, without the check in `apply`.** A late request from a pass that started before the option was turned off is drawn and stays until some later daemon completion, which nothing guarantees. Rejected.
- **Check the option in `update` instead of `apply`.** `update` runs on the marker pass thread, so the option can change between the check and the `invokeLater` that applies the bars. `apply` runs on the EDT right before the markup changes, which closes that window. Rejected.
- **Tag each request with a generation number that turning the option off advances.** It would also reject a request from before a quick OFF then ON switch. That request holds the bars for the current document stamp, so drawing it once the option is on again is correct. Rejected as state with no user-visible gain.
- **Check the option and empty the request records on the calling thread, and remove only the highlighters on the EDT (the first version of this change).** `daemonFinished` can run off the EDT. If the option is turned back on between the check and the removal, the late `requested.clear()` can erase a request recorded after the switch, and the late highlighter removal can erase bars that `apply` just drew while their request stays recorded. The next identical request is then skipped as "no change" and the bars stay missing. Rejected for the single EDT task.
- **Move the whole check into the EDT task, with no check on the calling thread.** It closes the same window, but every completion while the option is on would post an `invokeLater` when `daemonFinished` runs off the EDT. Rejected, since the calling-thread check keeps the on-state cost at one map lookup.
- **Keep the option check in both `GutterNoteService` and `RangeBars`.** Two copies of the same settings lookup can drift apart (a different descriptor instance, a different reading of the default). Rejected for one private check in `RangeBars`.

## Consequences
- Pros
  - Turning the option off removes the bars on the next daemon completion, which the Apply path triggers itself. A late request from a pass that was already running is not drawn. Turning the option on brings the bars back with the icons.
  - No startup work, no new dependency, no I/O. The listener exists only in projects where JNote's gutter service was created.
- Cons and what we accept
  - Every daemon completion in such a project runs one `LineMarkerSettings` map lookup. While the option is off, every completion also runs one EDT task with a second lookup and `clear()`. That is a `ConcurrentHashMap.clear`, plus a copy of the applied document keys. When `daemonFinished` is not called on the EDT, that EDT step is one `invokeLater` per completion even when there is nothing to remove. The first `clear()` after the option is turned off removes every applied highlighter, so its cost grows with the number of bars in open documents. Each `apply` of a non-empty request adds one more map lookup on the EDT. These costs are read from the code and the bytecode, not measured.
  - Any caller of `setEnabled` or `loadState` other than the Gutter Icons page restarts the daemon only if it does so itself. Settings Sync is such a path, and whether it restarts the daemon was not checked. If a change does not restart the daemon, the existing bars stay until the next daemon completion, such as the next edit or file switch. New bars are not added in the meantime, because `apply` checks the option.
  - The Apply path restarting the daemon was confirmed only in IC-242.20224.300 and IC-252.23892.409. The plugin has no upper build limit, so a later platform that stops restarting the daemon on Apply would delay the removal the same way.
  - Turning off the global "Show gutter icons" switch is a different setting. It hides icons but the daemon keeps calling the provider, so the bars stay. This ADR does not change that.
- Follow-up work
  - None.
