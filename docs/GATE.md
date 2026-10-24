# The bulk gate: `scripts/agenthq-gate.sh`

One script runs the whole QA gate against a commit or branch. QA and the PM run the same thing.

```sh
scripts/agenthq-gate.sh staging-agenthq          # batch gate: tests forced, compilation may come from the build cache
scripts/agenthq-gate.sh --full staging-agenthq   # promotion gate (staging -> feature): nothing from the build cache
```

Run it from Git Bash inside any AgentHQ checkout. Long run (tens of minutes): start it as
`agenthq bg run --name gate -- sh scripts/agenthq-gate.sh <ref>` so the session shows Running.

## What it does

1. Takes a machine-wide lock (`$TEMP/agenthq-gate.lock`). A second run is refused with exit 3 and a message naming the holder's PID. A lock whose PID is dead is removed automatically.
2. Aborts (exit 4) if `$TEMP` has less than 5 GB free (`GATE_MIN_FREE_GB` to change). The floor is rechecked before every stage.
3. Creates a detached temp worktree of `<ref>` under `$TEMP`, so the gate never touches your checkout or its build dirs.
4. Runs four stages one at a time, each `<task> --rerun --no-daemon`:
   `agentHq:test`, `agenthqUiCore:jvmTest`, `agentHqUi:test`, `agentHqUi:ownerSmoke`.
   `--rerun` forces the named test task to execute (never up-to-date, never from the build cache), so every test
   really runs. Compile tasks are NOT forced: they come from the build cache or are up-to-date, so the modules are
   compiled once per gate, not once per stage. (Before TEST-SPEED-2 each stage used `--rerun-tasks`, which
   recompiled everything four times, about 2-3 minutes of pure recompilation.)
   `--full` (use it for the promotion run, staging -> feature) adds `--no-build-cache` to every stage: nothing is
   taken from the cache, so every module compiles fresh in the new worktree, once (later stages see it up-to-date).
   The verdict line then says `(full)`.
   Test tasks run several forks in parallel (`maxParallelForks`; `-Pagenthq.testForks=N`, `-Pagenthq.uiTestForks=N`).
   The gate always runs the full `test` tasks. For a quick inner loop, `agentHq` and `agentHqUi` also have
   `testFast` and `testSlow` (TEST-SPEED-3): two tiers split by class name (`slowTestPatterns` /
   `slowUiTestPatterns` in each build file). Each class is in exactly one tier, so `testFast` + `testSlow` = `test`.
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
