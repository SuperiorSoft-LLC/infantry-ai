# The bulk gate: `scripts/infantry-gate.sh`

One script runs the whole QA gate against a commit or branch. QA and the PM run the same thing.

```sh
scripts/infantry-gate.sh staging-agenthq          # batch gate: tests forced, compilation may come from the build cache
scripts/infantry-gate.sh --full staging-agenthq   # promotion gate (staging -> feature): nothing from the build cache
```

A failing test that is on the [known-failure list](KNOWN-FAILURES.md) fails on dev too; check the list before you chase a failure.

Run it from Git Bash inside any Infantry checkout. Long run (tens of minutes): start it as
`infantry bg run --name gate -- sh scripts/infantry-gate.sh <ref>` so the session shows Running.

## What it does

1. Takes a machine-wide lock (`$TEMP/infantry-gate.lock`). A second run is refused with exit 3 and a message naming the holder's PID. A lock whose PID is dead is removed automatically. The lock dir also holds `owner.txt` (`ticket=<id> session=<id> pid=<n>`): when that session closes (or is seen ended later), the daemon releases the lock if its holder is gone (never a lock owner.txt does not name for it).
2. Aborts (exit 4) if `$TEMP` has less than 5 GB free (`GATE_MIN_FREE_GB` to change). The floor is rechecked before every stage.
3. Creates a detached temp worktree of `<ref>` under `$TEMP`, so the gate never touches your checkout or its build dirs.
4. Runs four stages one at a time, each `<task> --rerun --no-daemon`:
   `infantry:test`, `infantryUiCore:jvmTest`, `infantryUi:test`, `infantryUi:ownerSmoke`.
   `--rerun` forces the named test task to execute (never up-to-date, never from the build cache), so every test
   really runs. Compile tasks are NOT forced: they come from the build cache or are up-to-date, so the modules are
   compiled once per gate, not once per stage. (Before TEST-SPEED-2 each stage used `--rerun-tasks`, which
   recompiled everything four times, about 2-3 minutes of pure recompilation.)
   `--full` (use it for the promotion run, staging -> feature) adds `--no-build-cache` to every stage: nothing is
   taken from the cache, so every module compiles fresh in the new worktree, once (later stages see it up-to-date).
   The verdict line then says `(full)`.
   Test tasks run several forks in parallel (`maxParallelForks`; `-Pinfantry.testForks=N`, `-Pinfantry.uiTestForks=N`).
   The gate always runs the full `test` tasks. For a quick inner loop, `infantry` and `infantryUi` also have
   `testFast` and `testSlow` (TEST-SPEED-3): two tiers split by class name (`slowTestPatterns` /
   `slowUiTestPatterns` in each build file). Each class is in exactly one tier, so `testFast` + `testSlow` = `test`.
5. Removes the worktree and the lock on exit (also on Ctrl-C). `GATE_KEEP=1` keeps the worktree for inspection.

Logs are in `$TEMP/infantry-gate-<sha8>-<pid>/`: `status.log` (one line per stage) and `<stage>.log` (full Gradle output).

## Reading the result

The last line is the verdict, for example:

```
GATE PASS <sha> infantry=ok(812 tests,0 fail,0 err) uiCore=ok(...) infantryUi=ok(...) ownerSmoke=ok(...) logs=...
GATE FAIL <sha> ... infantryUi=FAIL(300 tests,2 fail,0 err) failing: [infantryUi: pkg.Class.testName,...] logs=...
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

- Take `$TEMP/infantry-gate.lock` first (the same `mkdir` lock the gate uses, PID in `pid`), check free RAM > 4 GB,
  and release the lock when done.
- One test run per build dir. `:infantry` and `:infantryUi` hold an exclusive file lock on `build/test-run.lock` for
  the whole build whenever a test task is in the graph (`gradle/test-run-lock.gradle.kts`). A second build in the
  same dir fails at once with `TEST RUN REFUSED: another Gradle build is running ... tests in <dir> (pid N, since T)`.
  Without it, the second run wipes `build/test-results/test/binary` under the first, which then fails with
  `NoSuchFileException ... in-progress-results-generic*.bin`. Wait for the holder, or use your own worktree.
- `cleanTest` is not needed: `--rerun-tasks` (or `--rerun`) on a single run is reliable.
- Do not edit tests to make the gate pass.

## CPU priority and worker cap (ticket 66153313)

Every Gradle stage runs under `nice` (below normal; `idle` = 19) with `--max-workers`. Inside an Infantry AI worker session the
daemon has already lowered the whole session tree (Windows Job object / POSIX nice) and exported `INFANTRY_BUILD_PRIORITY`,
`INFANTRY_BUILD_MAX_WORKERS` and a capped `GRADLE_OPTS` from the settings `limits.build.priority` / `limits.build.maxWorkers` /
`limits.build.maxParallelForks`; the script follows them (`INFANTRY_BUILD_PRIORITY=normal` turns its `nice` off, no
`INFANTRY_BUILD_MAX_WORKERS` = unlimited). Run outside a session, it defaults to below normal and logical cores - 2 workers.
