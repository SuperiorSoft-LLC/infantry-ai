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
| s2-project-ticket.sh | create project + ticket | moving a card is drag-only: BLOCKED (driver has no drag) |
| s3-plugins.sh | disable/enable a plugin | install/remove/refresh not driven; list scroll not driven |
| s4-mascot.sh | Mascot style + Motion present | Motion lives far below the fold, no scroll-into-view: BLOCKED |
| s5-runners.sh | Runners card present | add-runner needs an endpoint and scroll: BLOCKED |
| s6-assistant.sh | open Assistant, type a question | no provider in the test daemon, so no answer is checked |
| s7-resize.sh | maximize/restore x4, UI thread answers | |

Scenarios share window state (open tabs, the Assistant popup), so a later one can fail after an earlier one; `reset`
sends Escape twice but does not close the Assistant. Rerun a single file after `launch.sh` when in doubt.
