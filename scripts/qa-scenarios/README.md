# QA frontend scenarios (ticket 0c0c0cc0)

In-window scenarios driven by the windrive agent in `C:\t83\wd` (ticket 691a3a9e). They post events to the app's own
components only: no desktop input. Each prints PASS / FAIL / BLOCKED; screenshots land in `scratch/qa/`.

Run: start the window detached (it dies with the session's process tree otherwise), then run the scenarios.

    powershell -c "Invoke-CimMethod Win32_Process Create @{CommandLine='\"C:\Program Files\Git\bin\bash.exe\" scripts/qa-scenarios/launch.sh qa'; CurrentDirectory=(Get-Location).Path}"
    bash scripts/qa-scenarios/run-all.sh        # or one file: bash scripts/qa-scenarios/s2-project-ticket.sh

`launch.sh` uses a fresh state dir (`C:\t83\qa-state`), port 18841, scrubbed env. `pid.sh` finds the window by that port.
Kill the window by PID only. s1 expects a fresh state (no project yet): run it first, after a fresh `launch.sh`.

| File | Scenario | Known limits |
|---|---|---|
| s1-first-run.sh | empty shell, Plugins tab, Browse | needs fresh state |
| s2-project-ticket.sh | create project + ticket | moves the card with `drag` (windrive11) |
| s3-plugins.sh | disable/enable a plugin | install/remove/refresh not driven; list scroll not driven |
| s4-mascot.sh | Mascot style + Motion present | Motion is far below the fold: `scrollto` brings it on screen |
| s5-runners.sh | Runners card present | `scrollto` reaches the card; add-runner still needs a reachable endpoint |
| s6-assistant.sh | open Assistant, type a question | no provider in the test daemon, so no answer is checked |
| s7-resize.sh | maximize/restore x4, UI thread answers | |

Scenarios share window state (open tabs, the Assistant popup), so a later one can fail after an earlier one; `reset`
sends Escape twice but does not close the Assistant. Rerun a single file after `launch.sh` when in doubt.

## Driver: windrive11 (ticket bf14807e)

`windrive/` holds `WinDrive11.java`, windrive10 plus three commands (built by `windrive/build.sh` into `windrive11.jar`;
`lib.sh` uses `windrive/wd.sh`). Run through `wd <cmd> <arg>` or the `lib.sh` helpers:

| Command | Helper | What it does |
|---|---|---|
| `drag "FROM=>TO"` | `drag FROM TO` | press on a node (label as `click`, or `x,y`), 14 drag steps, release on the target |
| `scrollto "LABEL"` | `scrollto LABEL` | wheel the window until the node is on screen, learns the pixels per notch, prints `OK x,y` |
| `typein "LABEL[@dx,dy]=>TEXT"` | `typein LABEL TEXT` | click the node (plus offset) to focus a field, then type; fields are not in the accessibility tree, so anchor on a label near the field |

Other commands pass through to windrive10 unchanged. The three commands are written against the AWT event path windrive10
uses; they were compiled but not yet exercised against a live window.
