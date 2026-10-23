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

## Architecture
`model/Model.kt` is a pure `reduce(AppState, Envelope)`; `net/DaemonClient.kt` is the link; `net/Controller.kt`
turns intent into frames; `App.kt` is presentation only. Tests: reducer unit tests plus an integration suite
against the REAL daemon on the stub provider.

## Known protocol gaps (reported, not worked around)
No `role` on the session record (read if present, else neutral badge) · no diff frame (a copyable `git diff`
command is shown) · no pod-list frame (pods are those submitted/queried by id) · no dependency edges in
`NodeRecord` · no approve/reject frame · no output replay on reconnect · no token/model fields.
