# Infantry in-app build/debug: scope of the first slice (design only)

Ticket: `e06265ce-d805-4c48-8944-bce13155d364` (IN-APP BUILD/DEBUG 1/N). Blocked by
`8391e645` (INTERRUPT frame contract doc, done). This note is design only: no code.

Owner goal: Infantry is the only agent workspace the owner needs. Agents and the owner build, run and debug a project from inside
Infantry. The first slice proves the loop on one command: pick a saved command, stream its output
to the UI, stop it.

## 0. Base to build on

- The integration branch is **`feature-agenthq-mvp`**, contract **1.32.1** (`EventVocabulary.VERSION`).
  `INTERRUPT` (1.32.1) exists there. It does not exist on `ahq-main`, which is still at 1.7.0.
  Every frame named below is designed as an additive minor bump on **1.32.1**, so the next version
  is **1.33.0**. Build the code on a branch cut from mvp, not from the checkout this note was
  written in.
- Reusable today (no change needed):
  - `runner/ProcessRunner.kt`: `ProcessSpec(command: List<String>, workingDir, env)`,
    `ProcessListener` (`onStdout`, `onStderr`, `onExit`, line-oriented), `RunningProcess.killTree()`,
    `awaitExit()`, `pid`.
  - `runner/PipedProcessRunner.kt` is the production implementation. `ScriptedRunner.kt` is the test
    double.
  - The `FINISHED` outcome vocabulary: `SUCCEEDED`, `FAILED`, `KILLED_GRACEFUL`, `KILLED_FORCED`.
  - `ready` carries `instance=<uuid>`, and per-stream `sequence` plus `replay_request` /
    `replay_gap` exist (contract 1.7.0, F2). Only the run stream needs to opt in (follow-up T5).
- Not reusable, and why:
  - **Pods** (section 2.5, `pod_submit`): nodes are agent provider runs, the default provider only,
    and the surface is pull-only (`pod_query`). There is no output stream, so a build cannot be
    watched through it.
  - **Sessions** (`new`/`prompt`): these are provider conversations. A session's `INTERRUPT`
    semantics and its no-`FINISHED`-after-interrupt rule belong to chat, and a build has no
    conversation to keep.

## 1. The first slice (acceptance)

A user (or an agent) on a connected client does this:

1. Lists the daemon's **saved commands**. Each one is defined on the daemon side.
2. Starts one by id. For example `build-webtv-jvm-test`, which runs `.\gradlew.bat
   :webTvClient:jvmTest --tests ...`.
3. Watches stdout and stderr lines arrive in order, with a stream tag (`stdout` or `stderr`).
4. Presses Stop. Within a grace window the tree is killed (graceful, then forced).
5. Gets exactly one terminal frame, `run_finished`, with an exit code and an outcome.

Out of scope for the slice:

- Creating or editing commands from a client.
- stdin into a running command.
- Reconnect replay of run output (a client that drops mid-run sees the rest live, and gets a
  `run_finished` if it is connected at the end; the missed lines are a known gap, see T5).
- Multiple concurrent runs. The cap is one run in flight per daemon.
- Running a command in a worktree other than the configured repo.
- Anything about the agent sessions, the pod surface or tickets.

## 2. Security: why commands are pinned on the daemon

CONTRACT section 5 states that the bearer token is the whole credential. Any client that can reach
the daemon can already start an agent session that runs a provider CLI, so the token is effectively
code execution already. Still, a client must not be able to hand the daemon an arbitrary argv or
shell string. That would turn "may start a build" into "may run any program".

Rules for the slice:

- **A client sends a `commandId`, never an argv.** The argv lives in a daemon-side file, read at
  boot, and the client never sees a line of it to edit.
- **No shell string.** The argv is a `List<String>` and goes straight to `ProcessBuilder`. On Windows
  a `.bat` needs `cmd.exe /c` as the first two elements. That is acceptable only because the argv is
  pinned server-side.
- **The working directory is relative to the daemon's `INFANTRY_REPO`**, and must canonicalise to a
  path inside it. `..` and absolute paths are refused.
- **Env is daemon-side only.** No client-supplied environment in the slice.
- **Commands are validated at load.** An empty argv, a blank id or a duplicate id refuses the whole
  file, and the daemon logs why. A bad entry must not silently disable the others.

Proposed file: `<INFANTRY_HOME>/commands.json`, a JSON array of
`{ "id", "name", "argv": [...], "cwd": "relative/path" | null, "graceMillis": 5000 }`. The exact
location and format are an open question (section 7).

## 3. Daemon frames (proposed contract 1.33.0, additive)

Nothing existing moves. Every frame is new, so a 1.32.1 client never receives one.

| Type | `data` | Direction | Notes |
| --- | --- | --- | --- |
| `command_list` | empty | client to daemon | request the saved commands |
| `commands` | list of `CommandSpec` `{id, name, cwd}` | daemon to client | **no argv**: a client needs to know what it can run, not how |
| `run_start` | `{"commandId": "<id>"}` | client to daemon | refused with `error` if the id is unknown or a run is already in flight |
| `run` | one `RunRecord` `{runId, commandId, state, startedAt, pid, lastSequence}` | daemon to client | the answer to `run_start`; `state` is `STARTING`, `RUNNING` or `FINISHED` |
| `run_output` | `{runId, sequence, stream, line, truncated}` | daemon to client | `stream` is `stdout` or `stderr`; `sequence` starts at 1 per run and increases by 1 |
| `run_finished` | `{runId, exitCode, outcome, endedAt, detail}` | daemon to client | **always** sent exactly once per run, including after a Stop, and including a spawn failure |
| `run_stop` | `"<runId> [force]"` | client to daemon | graceful kill of the tree, then forced after the grace window; the answer is the `run_finished` |

Rules for these frames:

- **Stop is a kill, not an interrupt.** `INTERRUPT` keeps a session alive so the conversation can
  go on. A build has nothing to keep. The outcome after Stop is `KILLED_GRACEFUL` or `KILLED_FORCED`.
- **Always a `run_finished`, never a null exit that reads as a failure.** This is the lesson from
  `8391e645`: an interrupted turn that emits no FINISHED is deliberate, but a FINISHED with a null
  exit reads as FAILED. A killed run gets `KILLED_*` and a real `exitCode` or `null`, with `detail`
  saying why.
- **`exitCode` is `null` only when no process ever ran or the kill took the code away.** A spawn
  failure is `FAILED` with `exitCode: null` and the reason in `detail`.
- **Line handling.** Lines longer than 8 KB are cut and sent with `truncated: true`. Gradle prints
  progress with carriage returns and no newlines, so the splitter must treat `\r` as a line break as
  well as `\n`, or the whole build arrives as one line.
- **Output cap.** At most 10,000 `run_output` lines per run. After that the daemon sends one
  `run_output` with `line: "[output truncated]"` and drops the rest. A `run_finished` still follows.
- Errors use the existing `error` frame: unknown `commandId`, cwd outside the repo, a second
  `run_start` while one is in flight.

Sizing: the contract table gets seven new rows, and `CONTRACT.md` gets a 1.33.0 section. The
word-bounded "every Envelope frame is documented" test from `8391e645` must pass for all seven.

## 4. Client side (infantryUiCore, then infantryUi)

- `model`: a `RunState` keyed by `runId`. Lines are kept in `sequence` order and capped in memory
  (2,000 lines shown). One run is "current". `run_output` is applied only when `sequence == last+1`.
  A gap is a warning in the UI, not a silent skip.
- `Controller`: intents `startRun(commandId)` and `stopRun(runId)`. The commands list is loaded
  after `ready`, and only when the daemon's contract is at least 1.33.0, so an older daemon never
  sees an unknown frame.
- UI: a panel with a command dropdown, Start, Stop, and a monospace scrolling output pane. The
  panel does not show argv (the daemon does not send it).

## 5. Follow-up tickets (sized for Haiku, one area each)

Filed under this ticket as `child_of`. Those that need another first carry a `blocked_by` link.
Each has a RED test in its description and a named mutation, per the Infantry evidence bar
(`docs/INFANTRY-EVIDENCE-BAR.md`).

| # | Ticket | Area | Blocked by |
| --- | --- | --- | --- |
| T1 | Contract 1.33.0: add the seven run frame constants, CONTRACT.md section and table rows, and let the word-bounded test pass for them | protocol and docs | none |
| T2 | Daemon command registry: load `commands.json` at boot, validate, answer `command_list` with `commands` (no argv) | daemon | T1 |
| T3 | Daemon run start: `RunManager` on `ProcessRunner`, one run in flight, `run_start` to `run`, sequenced `run_output`, one `run_finished` on exit (tested with `ScriptedRunner`) | daemon | T2 |
| T4 | Daemon run stop: `run_stop` kills the tree gracefully, then forced, with `KILLED_*` outcomes; Windows tree kill tested | daemon | T3 |
| T5 | Client model and controller for runs in infantryUiCore: `RunState`, sequence check, bounded lines, Start and Stop intents; pure model tests, no Compose | client core | T1 |
| T6 | Run panel in infantryUi plus a live evidence test: real daemon, saved command `cmd.exe /c echo`, Start, Stop, assert the frames | UI and evidence | T4, T5 |

Filed ids (all `child_of` `e06265ce`):

- T1 `8a35df69-1bda-47c7-af60-97fb634f0a8d`
- T2 `47908f28-c91a-4ce7-8a30-4dae3898a03f`
- T3 `ac76c647-1fa0-4567-8561-9c43ab01153d`
- T4 `47bbccaa-abaf-4104-a71e-cd1c3932a912`
- T5 `5a40c1df-df99-4496-8081-d7dbb36a0c8e`
- T6 `28bfeb79-fa6e-4a9d-a671-3d8c0e70aaeb`

## 6. Risks and pitfalls to design for now

- **Gradle leaves a daemon behind.** The Gradle wrapper spawns a long-lived daemon JVM. A
  `killTree` on the wrapper may not reach it, because the daemon can outlive its parent. T4 must
  check the process list after a Stop. For the first command, use `--no-daemon`, or add a stop step,
  and do not call it done until that is proven.
- **Windows tree kill.** `Process.destroy()` does not kill descendants. The `killTree` path needs
  `taskkill /T /PID` on Windows, and the test must prove the grandchild dies.
- **Console encoding.** Decode child output as UTF-8 explicitly. Do not rely on the platform default,
  which is a code page on Windows.
- **Daemon restart mid-run.** The run dies with the daemon. `RunRecord` has no persistence in the
  slice, so after a restart the run is simply gone. That is acceptable for the slice and gets a
  follow-up if it matters. Do not invent a resume.
- **The bearer token is the boundary.** Do not put argv or env values into any frame or log. Log the
  command id, never the argv.

## 7. Open questions for the owner (not blocking T1)

1. Where should `commands.json` live: under `INFANTRY_HOME`, or next to the repo under version
   control? Under version control means the owner can review what runs. Under `INFANTRY_HOME` means
   it is per machine.
2. Should a client be allowed to create commands later (a follow-up frame), or is file-only the
   long-term answer?
3. Is one run in flight enough for the owner's first week, or should the cap be configurable?
