# STALL-1: why a worker is not told its job ended, and the daemon-side watch

Ticket 802c64d5 (owner, 3rd occurrence, 2026-10-29). A worker starts a long job (a Gradle chain), ends its turn or blocks
on it, the job ends, and nothing happens until the owner interrupts. This note is the investigation (item 1) and the
design that shipped. Everything here runs in the daemon at zero model-token cost: timers, file length/mtime reads, pid
probes and mail. A token is spent only by the turn a delivered notice starts.

## 1. Root cause

### Common to every harness (read from the code, not a guess)

1. **The daemon owns turn boundaries.** A session gets a turn only when the daemon resumes it with a prompt
   (`MailRouter`). Mail to a session whose turn is in flight (STARTING / BUSY / WAITING_INPUT) is *queued* and handed over
   when that run ends (`DeliveryOutcome.Retry`). So a turn that never ends never receives anything, and a session at rest
   receives something only if somebody sends it something.
2. **Nobody sent anything when a harness-launched process ended.** Between turns the daemon already read the process tree
   under the kept harness process (`BackgroundWorkScanner`, `SessionRecord.backgroundWork`): it knew a background
   `gradlew` was running and it saw it disappear, but it only used that to show the session as busy. The end produced no
   event. That is the direct cause of "the job finished and the worker was never told" when the job was started with the
   harness's own background feature instead of `agenthq bg run --notify`.
3. **`bg run --notify` was opt-in**, and its wrapper read the command's output **until EOF before waiting on the
   process**. A command that leaves a child holding its stdout (a test JVM, a server, a `start`ed process) never gives
   EOF, so the wrapper never reported `done`: even the opt-in path could stall forever (proved by test (ii) below).
4. **BUSY the whole time**: the status follows the turn. A model that waits inside its turn (a `sleep`, a blocking
   "wait for the background task" call, a foreground command that never returns) keeps the turn in flight, so the session
   reads BUSY and the queued mail is never delivered. Idle detection cannot see this; only "busy, silent, and its job has
   already ended" can.

### Per harness

| harness | how it runs | what it delivers when a background job ends | why BUSY can stay |
|---|---|---|---|
| claude (Claude Code, persistent `--input-format stream-json`) | one process per session, kept between turns; turn end = the `result` record | Claude Code's own background-task completion is surfaced to the model with a later turn (at a turn boundary). In headless stream-json mode nothing the daemon listens to starts a turn for it, so the notice waits for the next prompt, which nobody sends. **Not reproduced live in this ticket** (would need a paid run); consistent with the code path above and with all three occurrences. | the model waits inside its turn (sleep / blocking task-output read / long foreground Bash) |
| opencode (`opencode serve`, HTTP + SSE) | tool shells are children of the serve process | no background-job notion the daemon receives; a `&`/`nohup` job is just an orphaned child of the serve process | a foreground bash tool call blocks until it returns or the tool times out |
| pi | bash tool runs the command in the foreground | none | same as opencode |

The owner's hypothesis (completion events are queued off the main loop and read only at turn boundaries) holds for
claude; for opencode and pi there is no completion event at all. In every case the daemon is the only party that can both
see the end and start a turn, so the fix is daemon-side.

### EOF is not the end: the three cases, proved

`StallRunnerTest` runs each case as a real separate JVM (real pipes, real children, real exit codes):

- (i) a silent command that **exits** closes its pipe: EOF at once, **0 bytes**, exit code 3 reported as
  "ended, no output", not an error;
- (ii) a command that exits while a **child inherited its stdout** and keeps running: **no EOF**; the runner still reports
  `done` with exit 0 about 2 s after the exit (not after the child's 8 s), and names the child;
- (iii) a command that **closes stdout and keeps running**: EOF while the process lives: an `eof` event
  ("output closed, still running"), then `done` only when the process exits.

So the definitive end is the **process wait** of a process the runner launched itself (`Process.waitFor`: the runner is
its parent and gets the real exit code), never the pipe's EOF; EOF is reported as what it is.

## 2. What is forced, per harness, and what is not coverable

| mechanism | claude | opencode | pi |
|---|---|---|---|
| **PATH shims** (`<state>/shims` first on every worker session's PATH): bare `gradle`, `mvn`, `npm`, `yarn`, `pnpm` run through `agenthq bg run --attached --shim` | forced | forced | forced |
| **process-tree watch** (layer 5): any top-level process under the harness that lived 30 s+ and ends is told, exit code unknown | forced | forced | forced |
| **stall detector**: idle with ticket In Progress, or BUSY-silent after a job ended | forced | forced | forced |
| explicit `agenthq run-watched -- <cmd>` (= `bg run --notify`): detached, exit code + tail | worker's choice | worker's choice | worker's choice |
| harness hook that rewrites path-named programs (`./gradlew`, `gradlew.bat`) into the runner | **not done** (Claude PreToolUse `updatedInput` would do it; costs a CLI JVM per shell call) | **not done** (plugin `tool.execute.before`) | **not done** (extension) |

Not coverable by a PATH shim, by construction: a program named by a path (`./gradlew`, `.\gradlew.bat`, and on Windows
`gradlew.bat` from the cwd, which cmd.exe resolves before PATH). Those are still covered by the process-tree watch and the
stall detector (end told, exit code unknown), and by the start prompt that tells workers to use `run-watched`.
`SHELL` / `BASH_ENV` / `ComSpec` wrappers were rejected: `BASH_ENV` cannot rewrite a command line, and replacing
`ComSpec` changes every `cmd /c` the harness and its tools run.

## 3. Design

Layers, strongest first; each event is named by what is known:

1. **process exit + exit code**: the runner (`BgCli.runJob`) is the command's parent and waits on it; it writes
   `<id>.exit` and reports `done` with exit code, byte count, EOF state and surviving children (build daemons named and
   excluded: `GradleDaemon`, `KotlinCompileDaemon`).
2. **tree**: children are snapshotted every second while the command lives; after its exit the survivors are listed.
   A Windows Job Object / Linux process group wait is not used (not done; the snapshot names the survivors instead).
3. **END sentinel** in the log, after the wait returns: `[agenthq:done exit=<code> elapsed=<t> bytes=<n>]`
   (`bytes=0` = nothing was output). A graceful runner stop writes `exit=unknown reason=runner stopped`. Every log also has
   `[agenthq:start pid=.. at=.. cmd=<80 chars>]` and `[agenthq:running elapsed=Xm]` every 60 s, so a silent command
   always has output. Gradle gets `--console=plain`.
4. **pipe EOF**: `[agenthq:eof bytes=N process=running]` + an `eof` event when the output closes while the process lives;
   `[agenthq:note output still open after exit ...]` when a child keeps it open.
5. **pid gone** for processes the daemon did not launch: the process-tree watch (`SessionManager.trackTreeJobs`).
6. **heartbeat** (`BgJobNotifier`): 15 min (`limits.bg.notifyTimeoutMinutes`), then 30, 60 up to
   `limits.watch.heartbeatCapMinutes` (60); independent of the log: elapsed, last lines or "no output since", log gone,
   watcher read error, process alive or gone, "decide: keep waiting, investigate, or report to the PM". The first beat at
   the cap tells the PM once.

Watcher events (`BgJobNotifier`, daemon core, no LLM): new lines (pattern matches), EOF while running, done, vanished
(runner lost: the exit file is read when present, else "runner lost, exit unknown"), QUIET (no new non-runner line for
`limits.watch.quietMinutes`, 5; once per quiet stretch), heartbeat. Polling by length/offset; a shorter file (truncation,
rotation) is read again from its start. Delivered as `[AUTOMATIC]` mail from `system` (path, kind, exit code, bytes, last
20 lines); the mail store persists it and the router queues it while the session is busy or compacting, so it is
delivered exactly once, also across a daemon restart. Watched jobs are kept across a daemon restart: the runner
re-registers with its next beat, or the pid probe drops it and the exit file / "exit unknown" is reported.

Delivery rule: an `attached` job (a shim, the caller's foreground) whose session still has its turn in flight is not
mailed (the turn saw the end on its console); its end is recorded for the stall detector.

Stall detector (`StallDetector`, every 30 s, `limits.watch.stallMinutes` = 5): IDLE (ticket In Progress, at rest, no job,
no open owner question, not waiting on a mail reply) and BUSY-BUT-SILENT (turn in flight, a job ended after the last sign
of life, silent since). One nudge to the worker and one line to the PM (the ticket's starter, else `pm`) per stretch.
Never kills or restarts. A BUSY turn cannot be written into: the nudge queues; the PM line goes out at once.

Opt-out: `agenthq unwatched --reason "<why>" -- <cmd...>` (reason required). The command runs with the caller's stdio and
no log, but is registered with its reason: printed in the daemon log, counted (`WatchMeter.optOuts`), shown as
`watch OFF: <reason>`, reported to the PM once, and still covered by the heartbeat and the stall detector. The owner-only
`limits.watch.forced` = 0 turns the shims off for every session (`AGENTHQ_WATCH=off`); shipped default 1.

Visibility: `SessionRecord.watchLabel` (`watching <job> (quiet 2m)`, `watch OFF: <reason>`, `busy, silent 7m`, `stalled`,
`idle`; none for a session with no ticket and no job). `WatchMeter` counts events, heartbeats, opt-outs and stall nudges
(counts only; it says the watch uses no model tokens).

## 4. Remaining (not done in 802c64d5)

- Harness hooks that force path-named programs (`./gradlew`) through the runner: claude PreToolUse, opencode plugin, pi extension.
- Job Object / process-group tree wait (survivors are named, not waited on).
- UI: render `watchLabel` on the session card; add `WatchMeter` counts to the `usage_meter` frame.
- End-to-end stub-harness tests per harness (job ends while idle / busy-waiting / compacting resumes the worker), a daemon
  restart test; the pieces are unit-tested (runner, watcher, delivery rule, detector) and the mail queue has its own tests.
- Linux was not run (the code paths are the same JDK calls).
