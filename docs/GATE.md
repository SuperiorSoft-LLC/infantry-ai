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

Exit code is 0 only on PASS. A stage with zero tests counts as FAIL. Two stage verdicts are not test failures:
`EXTERNAL_STOP` (someone ran `gradlew --stop` and killed the stage's Gradle; re-run the gate) and `REFUSED_BUSY`
(another build holds the stage's test lock, see below). Failing stages do not stop later stages, so one run reports everything.

## Rules

- One Gradle at a time, machine-wide: do not start other Gradle builds while the gate runs, and check nobody else is mid-build before you start.
- Do not run two gates; the lock refuses it.
- Never run `gradlew --stop`. It stops every Gradle 9 daemon on the machine, including the single-use one of a
  `--no-daemon` build in another worktree, so it kills whatever gate or test run is in flight
  ("Gradle build daemon has been stopped: stop command received"). Use `--no-daemon` instead, or stop only your own
  daemon by PID.

## Running a test suite safely outside the gate

- Take `$TEMP/agenthq-gate.lock` first (the same `mkdir` lock the gate uses, PID in `pid`), check free RAM > 4 GB,
  and release the lock when done.
- One test run per build dir. `:agentHq` and `:agentHqUi` hold an exclusive file lock on `build/test-run.lock` for
  the whole build whenever a test task is in the graph (`gradle/test-run-lock.gradle.kts`). A second build in the
  same dir fails at once with `TEST RUN REFUSED: another Gradle build is running ... tests in <dir> (pid N, since T)`.
  Without it, the second run wipes `build/test-results/test/binary` under the first, which then fails with
  `NoSuchFileException ... in-progress-results-generic*.bin`. Wait for the holder, or use your own worktree.
- `cleanTest` is not needed: `--rerun-tasks` (or `--rerun`) on a single run is reliable.
- Do not edit tests to make the gate pass.
