# agentHqUi — AgentHQ desktop client (Phase 8)

Compose Desktop (Material 3, dark theme, amber accent). Talks to a daemon **only through the published
protocol** (`Envelope` + the payload records in `agentHq/CONTRACT.md`); it depends on `:agentHq` solely
to reuse those wire types. It never names a provider CLI and renders no PTY frames.

```
./gradlew :superiorSoftLibrary:agentHqUi:runUi        # the app; AGENTHQ_WS_URL, AGENTHQ_TOKEN(_FILE) as the CLI
./gradlew :superiorSoftLibrary:agentHqUi:runSmoke     # the app against an in-process STUB daemon (temp state)
./gradlew :superiorSoftLibrary:agentHqUi:test --no-build-cache
```

## Layout
Left: counters (needs input / to review / working), search, role filter + group, agent list.
Centre: agent header, Output | Pods tabs, prompt box with Spawn / Send / Resume / Kill / Force.
Right: Worktree | Review. Bottom: status bar (connection, contract, last-frame age).

## Keyboard
`^K` search · `Alt+↑/↓` agents · `^J` next agent needing input · `^N` spawn mode · `^R` resume ·
`^⇧K` kill · `^⌥K` force kill · `^1/^2` centre tab · `^3/^4` right tab · `^L` connection · `^/` prompt ·
Enter sends, Shift+Enter newline.

## Honesty rules
* `connected` only after the daemon's `ready` frame; loss → `reconnecting` with the reason; a 401/403 → `refused`, not retried.
* A command on a dead link is refused with a visible notice, never reported as sent.
* Needs-input is shown only when the daemon says so (`WAITING_INPUT` state, or `mail_waiting`). No text heuristics.
* Resume sends `resume "<id>\n<prompt>"` naming the session; refused up front with the reason when `resumable` is false.

## Proving pixel-identical
A ticket that must not change the existing UI (a refactor, a plugin seam, a new module that adds a tab) proves it with
`diffTabShots`. It renders every tab of every tab strip plus Settings > Plugins (the `runTabShots` renderer: 1400x860
window, stub daemon, fixed seed data and session ids, no modal blur), compares each PNG pixel by pixel with the
committed baseline in `tab-shots-baseline/`, and fails if any shot differs:

```
./gradlew :superiorSoftLibrary:agentHqUi:diffTabShots --max-workers=2
center-tab-PODS: 0 of 1176380 pixels differ
...
diffTabShots: 27 of 27 shots pass (tolerance 0 px, mask .../tab-shots-baseline/mask.txt)
```

Paste that summary into the ticket's report: "27 of 27 shots pass, tolerance 0" is the proof. A failing shot prints
`FAIL`, and `build/tab-shots-diff/diff/<shot>.diff.png` shows the differing pixels in red (masked regions in blue)
over the baseline in grey. A new or missing shot fails as well.

* **Mask.** `tab-shots-baseline/mask.txt` lists the only regions not compared: clocks and live readings (status bar
  ages and free RAM, the agent row's age, chat times, Usage's "updated Ns ago"). Add a region only for text that
  changes on its own between two renders of the same commit, never to hide a change you made. `-PtabShotsMask=<file>`
  uses another file.
* **Tolerance.** `-PtabShotsTolerance=<n>` allows n differing pixels per shot. The default is 0; a pixel-identical
  claim is only made at 0.
* **An intended UI change.** Run `updateTabShotBaseline`, look at the new PNGs, and commit them on their own
  ("ui: refresh tab-shots baseline for <change>"), so the change is a conscious baseline update a reviewer can see.
* **Against another ref.** The baseline was rendered on the owner's Windows host; fonts rasterise differently on
  another OS. To compare against the base branch on your own machine, render it in a worktree of that ref (the ref
  must include this task) and point the diff at it:
  ```
  git worktree add ../shots-base <base-ref>
  (cd ../shots-base && ./gradlew :superiorSoftLibrary:agentHqUi:renderTabShotsForDiff --max-workers=2)
  ./gradlew :superiorSoftLibrary:agentHqUi:diffTabShots -PtabShotsBaseline=../shots-base/superiorSoftLibrary/agentHqUi/build/tab-shots-diff/current
  ```
  `-PtabShotsCurrent=<folder>` compares an existing folder instead of rendering. Paths are relative to the
  repository root.

## Architecture
`model/Model.kt` is a pure `reduce(AppState, Envelope)`; `net/DaemonClient.kt` is the link; `net/Controller.kt`
turns intent into frames; `App.kt` is presentation only. Tests: reducer unit tests plus an integration suite
against the REAL daemon on the stub provider.

## Known protocol gaps (reported, not worked around)
No `role` on the session record (read if present, else neutral badge) · no diff frame (a copyable `git diff`
command is shown) · no pod-list frame (pods are those submitted/queried by id) · no dependency edges in
`NodeRecord` · no approve/reject frame · no output replay on reconnect · no token/model fields.
