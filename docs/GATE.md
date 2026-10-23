# The bulk gate: `scripts/agenthq-gate.sh`

One script runs the whole QA gate against a commit or branch. QA and the PM run the same thing.

```sh
scripts/agenthq-gate.sh staging-agenthq     # or any commit sha / branch
```

Run it from Git Bash inside any AgentHQ checkout. Long run (tens of minutes): start it as
`agenthq bg run --name gate -- sh scripts/agenthq-gate.sh <ref>` so the session shows Running.

## What it does

1. Takes a machine-wide lock (`$TEMP/agenthq-gate.lock`). A second run is refused with exit 3 and a message naming the holder's PID. A lock whose PID is dead is removed automatically.
2. Aborts (exit 4) if `$TEMP` has less than 5 GB free (`GATE_MIN_FREE_GB` to change). The floor is rechecked before every stage.
3. Creates a detached temp worktree of `<ref>` under `$TEMP`, so the gate never touches your checkout or its build dirs.
4. Runs four stages one at a time, each `--rerun-tasks --no-daemon` (never cached):
   `agentHq:test`, `agenthqUiCore:jvmTest`, `agentHqUi:test`, `agentHqUi:ownerSmoke`.
5. Removes the worktree and the lock on exit (also on Ctrl-C). `GATE_KEEP=1` keeps the worktree for inspection.

Logs are in `$TEMP/agenthq-gate-<sha8>-<pid>/`: `status.log` (one line per stage) and `<stage>.log` (full Gradle output).

## Reading the result

The last line is the verdict, for example:

```
GATE PASS <sha> agentHq=ok(812 tests,0 fail,0 err) uiCore=ok(...) agentHqUi=ok(...) ownerSmoke=ok(...) logs=...
GATE FAIL <sha> ... agentHqUi=FAIL(300 tests,2 fail,0 err) failing: [agentHqUi: pkg.Class.testName,...] logs=...
```

Exit code is 0 only on PASS. A stage with zero tests counts as FAIL. Failing stages do not stop later stages, so one run reports everything.

## Rules

- One Gradle at a time, machine-wide: do not start other Gradle builds while the gate runs, and check nobody else is mid-build before you start.
- Do not run two gates; the lock refuses it.
- Do not edit tests to make the gate pass.
