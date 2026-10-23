# AgentHQ session host: one process lifecycle for every harness

Plan for **BUG-M2-11a** (ticket 662ee553), built by BUG-M2-11b through 11f.

- **Status:** design only. Nothing here is built.
- **Author:** architect, 2026-10-19.
- **Evidence:** [AGENTHQ-SESSION-HOST-PROBE.md](AGENTHQ-SESSION-HOST-PROBE.md), the probe run on this machine
  against the installed CLIs.
- **Line numbers** were read, and re-checked before commit, in `.worktrees/bug-m2-11a-session-lifecycle-design`
  at **1897cca4** (fbcd4c52 plus a base-declaration commit). They will drift, so re-read them in your own tree
  before quoting them. `CONTRACT.md` means `superiorSoftLibrary/agentHq/CONTRACT.md`.

> Owner: "this was fixed on Opencode and its not fixed on [the other harnesses] which shows this was a hack fix
> only for opencode. this functionality and configurations should be a single managed process across all
> harness and models and sessions."

---

## 1. What is wrong today

M2-11 put a session lifecycle inside a **runner** for one harness. SessionManager learned about it through
identity checks and flags. Paths are relative to `src/main/kotlin/com/superiorsoftllc/agenthq/`.

| # | Fact | Where |
| --- | --- | --- |
| 1 | `sessionTransport()` defaults to null. Only `OpenCodeProvider` returns one: `OpenCodeServeRunner`, a 397-line `ProcessRunner` that owns the process map, the restart after a crash, release on close, and shutdown. | `provider/AgentProvider.kt:98`, `provider/OpenCodeProvider.kt:26,44`, `opencode/OpenCodeServeRunner.kt` |
| 2 | SessionManager branches on that transport: by identity (`target.runner === provider.sessionTransport()`), on `process.persistent`, and on `startedProcess`. | `session/SessionManager.kt:109, 112, 614-615, 647-655, 737, 847, 953` |
| 3 | The lifecycle plumbing leaks into the generic runner API: `ProcessSpec.sessionKey`, `RunningProcess.persistent` / `startedProcess` / `bootDetails`, `ProcessRunner.release` / `shutdown`. | `runner/ProcessRunner.kt:14, 67, 70, 73, 83, 86` |
| 4 | The serve runner rebuilds the turn by reverse-parsing an argv that was built for `opencode run`. | `opencode/OpenCodeCommand.kt:80` (`parseRun`) |
| 5 | The only on/off switch is an env var, for one harness. | `AGENTHQ_OPENCODE_SERVE`: `OpenCodeServeRunner.kt:357-358`, `provider/ProviderFactory.kt:27` |
| 6 | Claude and pi spawn one process per reply. Every reply re-prints `starting … model … in …`, `resuming provider session …` and `process started (pid N)` into the chat. The UI also renders that reply's `exit` frame as "process exited with code 0" (`agenthqUiCore/…/ui/model/Model.kt:968`). The ticket also reports a console window per reply on Windows. This is the visible "not fixed on the other harnesses". | `SessionManager.kt:607-615, 654`; `DaemonMain.kt:965-969` |
| 7 | M2-11 changed what goes on the wire (no `exit` per turn, boot lines moved to Activity) and never wrote it into CONTRACT.md. | `DaemonMain.kt:966-967, 974-976` |
| 8 | `opencode serve` ignores stdin EOF (probe). The one signal a dying daemon reliably sends therefore does not stop it, and nothing reaps a leftover server on the next start. | probe, opencode section |

The root cause is a **misplaced responsibility**. Process lifecycle is a daemon concern, but it was
implemented inside one harness's transport. Each further harness would have needed its own 400-line runner
plus more SessionManager branches.

## 2. What the probe established

| | claude 2.1.303 | pi 1.3.0 (omp = pi) | opencode 1.18.35 | descriptor, stub |
| --- | --- | --- | --- | --- |
| Persistent mode | `-p --input-format stream-json --output-format stream-json` | `--mode rpc` | `serve` | **none**, so one-shot |
| End of turn | `result` | `agent_settled` | `session.idle` | process exit |
| Abort, keeping the process | `control_request` interrupt | `{"type":"abort"}` | `POST …/abort` | kill |
| Graceful close | stdin EOF (0.65 s) | stdin EOF (26 ms) | **none**, tree kill | n/a |
| Resume in a new process | `--resume <id>` (1.4 s) | `--session <id>` (4.3 s) | session id, server-side | n/a |

Every harness AgentHQ runs has a real persistent mode. **The lifecycle is the same problem three times; only
the protocol differs.** The design draws its line at exactly that boundary.

## 3. Design

### 3.1 Four roles, one owner per concern

| Component | Owns | Names a harness? |
| --- | --- | --- |
| **SessionManager** (exists) | The session state machine, persistence, wire events, one turn per session (`:555`), the mail pump, the watchdog. | **No.** It holds no runner and calls the host. |
| **SessionHost** (new; one per daemon, owned by SessionManager) | Process lifecycle for **every** session: launch, reuse, abort, stop, restart-once after a crash, idle stop (11f), shutdown, the chat boot line, and Activity details. | **No.** |
| **HarnessSessionAdapter** (new; one per harness, plus `OneShotAdapter`) | The protocol only: how to launch, how to send one turn, how to recognise the end of a turn, how to abort it and how to close. | Its own harness only. |
| **ProcessRunner** (exists) | Start one child, stream its lines, tree kill. | **No.** It goes back to its pre-M2-11 shape. |

```
 prompt ─▶ SessionManager.startRun ──runTurn/abortTurn/stop──▶ SessionHost ──launch/sendTurn/abortTurn/close──▶ adapter
                 ▲   (state, wire)                              (lifecycle)                                    (protocol)
                 └──────────────── HostEvents ◀───────────────────────┘                                          │ spawns via
                                                                                                                 ▼
                                                                                          PipedProcessRunner / PtyProcessRunner
```

### 3.2 Adapter selection: one expression, no harness names

```kotlin
fun selectAdapter(provider: AgentProvider, policy: LifecyclePolicy): HarnessSessionAdapter =
    if (policy.keepAliveMinutes == 0) OneShotAdapter(provider)
    else provider.sessionAdapter() ?: OneShotAdapter(provider)
```

This is the **only** place where "persistent or not" is decided.

- A harness with a persistent mode returns an adapter.
- A harness without one returns null and gets the one-shot adapter, behind the same host.
- Turning the setting off sends every harness down the same one-shot path.

SessionManager, the host and the daemon contain no `if (provider.id == …)`, no `is` check on an adapter
type, and no env toggle. 11b proves this with a mutation (section 11).

## 4. Interfaces

Package `com.superiorsoftllc.agenthq.session.host`. These are sketches: names and signatures are the
contract, and bodies are 11b's job. `RunRequest` is the existing type (prompt, workingDir, resumeSessionId,
model, effort). `env` is the per-session env that SessionManager already builds (`SessionManager.kt:587-593`).

```kotlin
/** How a turn ended. A crash or a failed launch is FAILED; only the host's OWN abort makes INTERRUPTED. */
enum class TurnEnd { COMPLETED, FAILED, INTERRUPTED }

/** The one lifecycle setting (section 8), read on every decision, never cached. */
data class LifecyclePolicy(val keepAliveMinutes: Int)

/** The adapter's own handle on what it launched. The host only reads pid / isAlive, subscribes onExit, kills. */
interface HostedProcess {
    val pid: Long?                                  // null = no OS process right now (one-shot between turns, stub)
    val isAlive: Boolean
    fun onExit(handler: (exitCode: Int) -> Unit)    // once, when the OS process ends for any reason
    fun killTree()
}

/** Where an adapter reports. Thread-safe; the host serialises delivery per session. */
interface AdapterSink {
    fun output(line: String)                        // a provider stdout line, VERBATIM (it becomes a raw `stdout` frame)
    fun diagnostic(line: String)                    // a provider stderr line, verbatim
    fun detail(text: String)                        // plumbing for Activity; never a chat line
    fun providerSession(id: String)                 // the provider's session id once known (pi get_state, opencode POST /session)
    fun turnEnded(end: TurnEnd, exitCode: Int? = null, reason: String? = null)   // exactly once per sendTurn
}

interface HarnessSessionAdapter {
    val label: String                               // "claude", "pi", "opencode": named in the one chat line
    val bootTimeoutMs: Long get() = 60_000          // probe: opencode listens at 1.6 s, pi acks at 8.5 s
    val needsPipes: Boolean get() = true            // a line protocol cannot run over a PTY (echo, CRLF, line limits)

    /** What a live process is bound to. A different value on the next turn = restart and resume (section 5). */
    fun fingerprint(request: RunRequest, env: Map<String, String>): String

    /** Start a process ready to take turns, through [runner]. Runs on the host's executor; may block up to bootTimeoutMs. */
    fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess

    /** Send one turn on a live process; its end comes back through sink.turnEnded. */
    fun sendTurn(process: HostedProcess, request: RunRequest)

    /** Ask the harness to stop the current turn and KEEP the process. false = it cannot; the host kills. */
    fun abortTurn(process: HostedProcess): Boolean

    /** Ask the process to exit. false = it has no graceful exit (opencode); the host kills at once. */
    fun close(process: HostedProcess): Boolean
}

/** Session-scoped callbacks, implemented once by SessionManager. Never called while the host holds its lock. */
interface HostEvents {
    fun stdout(sessionId: String, line: String)
    fun stderr(sessionId: String, line: String)
    fun bootLine(sessionId: String, text: String)               // the ONE chat line per launch + failure explanations
    fun activity(sessionId: String, detail: String)
    fun providerSession(sessionId: String, id: String)
    fun process(sessionId: String, pid: Long?)                  // the session's live OS process, or none
    fun turnEnded(sessionId: String, end: TurnEnd, exitCode: Int?, processExited: Boolean, reason: String?)
    fun processExited(sessionId: String, exitCode: Int)         // a kept process died with no turn in flight
}

class SessionHost(
    private val runnerFor: (AgentProvider) -> ProcessRunner,    // today's Configured.runner: piped, PTY or the stub's ScriptedRunner
    private val lineRunner: ProcessRunner,                      // piped; given to every adapter with needsPipes
    private val policy: () -> LifecyclePolicy,                  // SettingsService, section 8
    private val events: HostEvents,
    private val clock: () -> Long = System::currentTimeMillis,
    private val select: (AgentProvider, LifecyclePolicy) -> HarnessSessionAdapter = ::selectAdapter,
) {
    /** Run one turn. Returns at once; launch and I/O run on the host's executor. At most ONE launch per call. */
    fun runTurn(sessionId: String, provider: AgentProvider, request: RunRequest, env: Map<String, String>)
    fun abortTurn(sessionId: String)                            // the owner's interrupt
    fun stop(sessionId: String, force: Boolean = false)         // close / kill / delete / watchdog
    fun shutdown()                                              // daemon stop: nothing survives it
}
```

**Registration.** `AgentProvider.sessionTransport()` (`AgentProvider.kt:98`) is replaced by:

```kotlin
fun sessionAdapter(): HarnessSessionAdapter? = null
```

Plugin harnesses get the same seam on `CliHarness`, the class the daemon adopts (`SessionManager.kt:951`):

```kotlin
open fun sessionAdapter(): HarnessSessionAdapter? = null
```

`HarnessProvider` delegates to it. Claude has two entry points, `ClaudeCodeProvider` (`ProviderFactory.kt:29`,
id `claude`) and the `ClaudeHarness` plugin harness (`harness/claude/ClaudeHarness.kt:27`), and both return the
same `ClaudeSessionAdapter`, so behaviour never depends on which path resolved the id. `AgentProvider.transport()` (`:91`) stays: it is only "which runner",
and it is the stub's `ScriptedRunner` escape hatch.

## 5. Lifecycle rules (the host's whole job)

One table, the same for every harness:

| Situation | Host does | Chat line (stderr `[agenthq] …`) | Activity | Wire |
| --- | --- | --- | --- | --- |
| First prompt, no process | launch (with `--resume`-equivalent if the session has a provider id), then sendTurn | `↻ session started (<label> · <model>)` | launch details: argv summary, cwd, pid, resumed id | `status` BUSY, `childPid` |
| Prompt, process alive, same fingerprint | sendTurn only | **none** | none | |
| Prompt, fingerprint changed (claude: model/effort/cwd/env) | close the old process (grace), launch and resume | `↻ session restarted (<label> · <model>)` | which field changed | |
| Prompt, no process, the session has a provider id (after an idle stop or a daemon restart) | launch and resume | `↻ session resumed (<label> · <model>)` | | |
| Kept process dies between turns | forget it; **no** relaunch until a prompt arrives | none | `the <label> process exited (code N)` | `exit` N |
| Prompt after that crash | launch **once** and resume | `↻ session restarted after a crash (<label> · <model>)` | | |
| Kept process dies mid-turn | the turn ends FAILED; it is **never** retried | `the <label> process exited (code N) during the turn; the next message restarts it` | | `exit` N, then `finished` FAILED |
| Launch fails (spawn error, exit before ready, `bootTimeoutMs`) | the turn ends FAILED; no retry; the next prompt may launch again | `the <label> process did not start: <stderr tail>` | | `finished` FAILED |
| Owner interrupt | `abortTurn`; if it answers false, or no end arrives within 5 s, then `killTree` | the existing line (`SessionManager.kt:772`) | | no `finished` (CONTRACT.md:388, :1182) |
| Close / kill frame, watchdog | `close`; tree kill after the existing `killGraceMs`; `force` kills at once | | | `finished` KILLED_* (unchanged) |
| Delete | stop (the session is already at rest) | | | |
| Daemon shutdown | stop every process: close, grace, kill | | | |
| Idle for `keepAliveMinutes` (11f) | stop | **none** | `stopped after N idle minutes` | **no** `exit`; record IDLE, `isProcessAlive` false |
| Daemon start (11f) | reap recorded kept pids whose start instant matches | | `reaped stale <label> process pid N` | |

The rules about chat lines, in short:

- **Launches:** one chat line per launch, zero per reused turn.
- **Failures and interrupts:** each keeps its one explanation (also the auth hint at `SessionManager.kt:721-726`).
- **Everything else:** goes to Activity.

`<model>` is named as the current line names it (`:649`): the session's model, else the provider's default,
else `default`.

**Invariants**, each one a test in 11b:

1. At most one hosted process per session.
2. The host never launches without a prompt (no auto-restart, no pre-launch) and launches at most once per
   `runTurn`.
3. A turn is never retried. A prompt reaches a harness at most once.
4. Every `runTurn` ends in exactly one `turnEnded`, including a launch failure and a death mid-turn.
5. A launch produces one chat line, and a reused turn produces none. Launch and turn details go to Activity
   only.
6. Every session OS process is spawned by `runnerFor(provider)` or by `lineRunner`. An adapter never builds a
   runner or calls `ProcessBuilder`; `OpenCodeServeRunner.kt:59`'s private `PipedProcessRunner()` goes away.
   Console flags and tree kill therefore live in one place.
7. No code outside an adapter names a harness.
8. `stop` is idempotent, and `shutdown` leaves no process alive.
9. The host never calls `HostEvents` while it holds its own lock. SessionManager holds its monitor when it
   calls into the host (`startNew` `:342`, `resume` `:474` and `delete` `:882` are `@Synchronized`), and the
   handlers the events land in are `@Synchronized` too (`heartbeat` `:686`, `finish` `:698`, `update` `:980`).
   A host that called back while holding its lock could deadlock against them.

**Races handled by the host,** not by SessionManager:

- **Interrupt during a launch:** the host records it, and once `launch` returns it aborts or kills before
  sending the turn. Today's `SessionManager.kt:646` does the same.
- **Stop during a launch:** the host kills once `launch` returns.
- **An end that follows the host's own abort** counts as INTERRUPTED, whatever the harness calls it: claude's
  `error_during_execution`, pi's `aborted:true`, opencode's `MessageAbortedError`.

## 6. Adapters

### 6.1 opencode: `OpenCodeServeRunner` becomes `OpenCodeServeAdapter` (11b)

| `OpenCodeServeRunner` today | Fate |
| --- | --- |
| Per-session server map keyed by `ProcessSpec.sessionKey`; `release(key)`; `shutdown()` | **SessionHost** (generic) |
| Restart once on the next prompt after a death, "the next message restarts it" (`:340-350`) | **SessionHost** (generic; same wording, `<label>`-parameterised) |
| `RunningProcess.persistent` / `startedProcess` / `bootDetails`, overridden by its turn class (`:250-262`) | **deleted**: the host knows when it launched |
| `launcher: ProcessRunner = PipedProcessRunner()` (`:59`) | **deleted**: the runner comes from the host (invariant 6) |
| `ENV = "AGENTHQ_OPENCODE_SERVE"` (`:358`), `fromEnv()` (`:363`) | **deleted**, not renamed (section 8) |
| `parseRun(argv)` reverse-parse (`OpenCodeCommand.kt:80`) | **deleted**: the adapter receives `RunRequest` |
| Spawn `opencode serve --port … --hostname 127.0.0.1` with a random password; wait for `listening on` | adapter `launch` |
| Basic auth (`:119`), `post()` (`:193`), SSE reader on `/event` (`:207`), event dispatch | adapter (protocol) |
| Automatic reject of permission and question requests | adapter (protocol) |
| `POST /session`, or reuse of `resumeSessionId` | adapter `launch`, reported through `sink.providerSession` |
| `prompt_async` with the model in the body | adapter `sendTurn`; the model is per turn, so it is **not** in the fingerprint |
| Server parts mapped onto `opencode run` JSON lines (today's translator keeps working) | adapter, via `sink.output` |
| `/abort` inside `killTree()` (`:291`) | adapter `abortTurn` returns `true` |
| No graceful exit (stdin EOF ignored, probe) | adapter `close` returns `false`, so the host tree-kills |

- **Fingerprint:** `workingDir` + `env`. Effort is not passed to opencode today, so nothing changes there.
- **What moves:** reading the file, roughly 150 of the 397 lines are lifecycle and move to the host; the
  protocol becomes the adapter. That is an estimate, not a count.
- **Tests:** `OpenCodeServeSessionTest`'s six tests split. The five lifecycle ones are re-expressed in
  `SessionHostTest` over a **fake** adapter, so they now prove the host for every harness.
  `runLineType_mapsFinishedServerPartsOntoRunLines` stays with the adapter.

### 6.2 claude: `ClaudeSessionAdapter` (11c)

| Concern | What the adapter does |
| --- | --- |
| Launch | The one-shot argv `ClaudeCommand` builds today (`--output-format stream-json --verbose`, model, effort, MCP and permission flags), minus the prompt, plus `--input-format stream-json`, plus `--resume <id>` when resuming. There is no ready signal to wait for: the probe wrote turn 1 one millisecond after the spawn, and claude buffered it (`system/init` at +3.1 s, reply at +4.6 s). |
| Turn | One stdin line: `{"type":"user","message":{"role":"user","content":[{"type":"text","text":<prompt>}]}}` |
| End | The `result` record: COMPLETED when `is_error` is false, otherwise FAILED with the CLI's reason. INTERRUPTED when it follows the adapter's own interrupt. |
| Abort | `{"type":"control_request","request_id":<uuid>,"request":{"subtype":"interrupt"}}`, returns true (`control_response` came in 12 ms). |
| Close | Close stdin, returns true (exit 0 in 0.65 s). |
| Fingerprint | The launch argv without `--resume`, + `workingDir` + `env`. A model or effort change therefore restarts and resumes (decision D5: `set_model` was not probed). |
| Session id | Unchanged: `sessionIdFromLine` reads `session_id` from init and result. |

**11c must also handle three things:**

- **`system/init` arrives on every turn (probe).** Raw `stdout` stays verbatim, but the translation must not
  re-announce the session per turn. `ClaudeStreamTranslator.kt:85` only suppresses the repeat within ONE
  translator instance, so either the translator lives as long as the process, or the announcement is keyed on
  the session id.
- **The translator emits the terminal error "at process exit"** (`ClaudeStreamTranslator.kt:31`). On a kept
  process the end is the `result` record, so the terminal event must fire there.
- **The Windows console check** (section 9) is part of 11c's definition of done.

### 6.3 pi: `PiRpcAdapter` (11d)

| Concern | What the adapter does |
| --- | --- |
| Launch | `PiCommand`'s argv (`PiCommand.kt:59-64`) with `--mode rpc` instead of `--mode json`, no prompt, `--session <id>` when resuming. Then `get_state`, whose `data.sessionId` goes to `sink.providerSession`. RPC mode prints no session header (probe). |
| Turn | `{"id":<uuid>,"type":"prompt","message":<prompt>}`. A `response` with `success:false` is FAILED (reason = its error). `disposition:"handled"` is COMPLETED at once, because no run started (rpc.md line 67). |
| End | **`agent_settled`**, not `agent_end`: retries, compaction and follow-ups can come after `agent_end` (rpc.md line 69). `aborted:true` after the adapter's own abort is INTERRUPTED; `aborted:true` otherwise is FAILED ("aborted by the harness"). |
| Abort | `{"type":"abort"}`, returns true. The turn ends on `agent_settled`; `response/abort` can arrive **after** it (probe). |
| Extension UI | Dialog methods (`select` / `confirm` / `input` / `editor`) get `{"type":"extension_ui_response","id":…,"cancelled":true}` plus an Activity detail. Fire-and-forget methods (`notify`, `setStatus`, `setWidget`, `setTitle`, `set_editor_text`) are Activity only. Neither ever reaches chat. |
| Close | Close stdin, returns true (exit 0 in 26 ms). |
| Fingerprint | Launch argv without `--session`, + `workingDir` + `env`. |
| Framing | `PipedProcessRunner` splits lines with `BufferedReader.forEachLine` (`PipedProcessRunner.kt:37`), and that never splits inside a JSON string: U+2028/U+2029 are not line breaks to Java. 11d pins this with a test. |
| Translator | Rpc emits json-mode event records plus `response` and `extension_ui_request` lines. The pi translator must ignore those two types; a test covers it. |

**omp:** no adapter. On this machine `omp` is a shim that runs pi, and OMP is on the owner's backburner list
("do not implement, do not stub").

### 6.4 `OneShotAdapter` (11b): every harness without a persistent mode, and every harness when the setting is 0

| Concern | What the adapter does |
| --- | --- |
| Launch | Starts **no** process. It returns a logical handle (`pid` null, alive until `close`). This is what makes a one-shot session print ONE `↻ session started` line, like every other session. |
| Turn | Today's exact path: `provider.spec(request)` + env, `runner.start(spec, …)`, `provider.deliver(process, request)` (now `SessionManager.kt:576-661`). The child's exit ends the turn: COMPLETED on 0, FAILED otherwise, with that `exitCode`. The child's pid is reported for the turn's duration. |
| Abort | Tree-kills the turn's child and returns true. The turn ends INTERRUPTED. |
| Close | Kills an in-flight child, if any, and returns true. |
| Fingerprint | Constant: there is no process to re-bind. |
| `needsPipes` | `false`: it uses the runner the daemon configured for that provider (piped, PTY, or the stub's `ScriptedRunner`). |

It serves descriptor harnesses, the stub, any provider whose `sessionAdapter()` is null, and every provider
when `keepAliveMinutes == 0`.

## 7. What is deleted or changed

| Where (1897cca4) | Today | After |
| --- | --- | --- |
| `SessionManager.kt:109, 112, 953` | `provider.sessionTransport() ?: …` when building `Configured` | `Configured(provider, runner)` without the transport part. The runner is what the host spawns one-shot turns through. |
| `SessionManager.kt:607-615` | Boot details printed as chat lines unless there is a session transport | Boot details go to the host and from there to Activity. `val sessionTransport` and its branch are **deleted**. |
| `SessionManager.kt:637-661` | `target.runner.start(spec, listener)`, the persistent/startedProcess branch (`:647-655`), `process started (pid)`, `provider.deliver` | `host.runTurn(sessionId, provider, request, runEnv)`. The spawn and deliver move into `OneShotAdapter`, the raced-interrupt kill (`:646`) into the host. |
| `SessionManager.kt:699-737` | `finish(exitCode)`; `processExited = run.process?.persistent != true` (`:737`) | `finish(end, exitCode, processExited)`, fed by `HostEvents.turnEnded`. The state mapping (IDLE / FAILED / EXITED, auth hint) is unchanged. |
| `SessionManager.kt:752-762` | `interrupt` tree-kills the process (`:758`) | `host.abortTurn(sessionId)`. The process survives when the harness can abort. |
| `SessionManager.kt:819-825, 842-855` | `kill` → `releaseTransport`; the grace wait skipped when `process.persistent` (`:847`) | `host.stop(sessionId, force)`, which owns the grace. |
| `SessionManager.kt:886` | `delete` → `releaseTransport` | `host.stop(sessionId)` |
| `SessionManager.kt:923-933` | `shutdown` kills runs, then calls every runner's `shutdown()` (`:932`) | Runs are marked killed as today, then `host.shutdown()`. |
| `SessionManager.kt:935-940` | `releaseTransport` | **deleted** |
| `SessionManager.kt:665-679` | The watchdog calls `kill` | Unchanged; `kill` now reaches `host.stop`. |
| `runner/ProcessRunner.kt:14, 67, 70, 73, 83, 86` | `sessionKey`, `persistent`, `startedProcess`, `bootDetails`, `release`, `shutdown` | **deleted**: runners start one child again |
| `provider/AgentProvider.kt:98` | `sessionTransport()` | **replaced** by `sessionAdapter()` |
| `provider/OpenCodeProvider.kt:26, 44`; `provider/ProviderFactory.kt:27-28` | `serveTransport` param; `OpenCodeServeRunner.fromEnv()` | `override fun sessionAdapter() = OpenCodeServeAdapter(…)`; the factory loses the env read |
| `opencode/OpenCodeServeRunner.kt` (397 lines) | A `ProcessRunner` with a lifecycle | `opencode/OpenCodeServeAdapter.kt`, protocol only (6.1) |
| `opencode/OpenCodeCommand.kt:80` | `parseRun` | **deleted** |
| `session/Session.kt:280`, `daemon/DaemonMain.kt:965-969` | `Exit.processExited` computed from `persistent` | The field stays, and its meaning becomes "the session's hosted process ended without the daemon asking". New `SessionEvent.ProcessExit(sessionId, code)` maps to an `exit` frame only, for a death between turns. |
| `harness/CliHarness.kt`, `provider/HarnessProvider.kt` | n/a | `open fun sessionAdapter()` + delegation; `Harness.kt:23` goes to `"1.4.0"` |

**No code path keeps a second lifecycle.** This grep is part of 11b's gate, and it must return nothing after 11b:

```sh
git grep -nE 'sessionTransport|releaseTransport|AGENTHQ_OPENCODE_SERVE|parseRun|\bsessionKey\b|startedProcess|\.bootDetails\b|val persistent|\.persistent\b' -- superiorSoftLibrary/agentHq/src/main
```

- **On 1897cca4 it returns 34 lines,** every one of them M2-11 plumbing.
- **A bare `persistent` is left out on purpose:** `mailbox/Message.kt:16` uses the word in prose.
- **Who uses the M2-11 members today:** outside `ProcessRunner.kt`, only `OpenCodeServeRunner` and
  `SessionManager`. No test double overrides them, and `RunManager`, the other `ProcessRunner` client, uses
  none of them.

## 8. The single setting

| | |
| --- | --- |
| Key | **`session.keepAliveMinutes`** |
| Type, range | int, 0..1440 |
| Default | **30** |
| Store | `SettingsService` (`settings/SettingsService.kt`, same mechanism as `context.includeSkillsContext` at `:80`) |
| Frames | The existing `settings_get` / `settings_set` / `settings` (contract 1.6.0, CONTRACT.md:1177-1179, :1396). No new frame. |

- **0 = keep-alive off.** Every harness runs one-shot, **still through the host**, so the chat and Activity
  rules do not change. Off changes cost and latency, never what the chat shows.
- **N > 0 = keep alive.** A session's process lives between turns and stops after N idle minutes. The idle
  stop is 11f (built: `SessionHost.stopIdle`, swept every 30 s on the host clock).
- **Live changes:**
  - A busy session picks up the new value at the end of its turn.
  - An idle session picks it up at once: lowering the value stops processes that have been idle longer than
    it, and 0 stops every kept process. Built in 11f: `settings_set` on the key calls
    `SessionManager.lifecyclePolicyChanged()`, which re-runs the idle sweep at once.
- **Why one integer:** it is the switch and the policy together, so "enabled" and "timeout" can never
  disagree.
- **Why 30 minutes:** a launch costs 1.4-8.5 s, plus opencode's 28 s cold first turn on a free model (probe).
  30 minutes covers a conversation's pauses (reading a reply, a mail round-trip) without holding processes
  overnight. This is a judgement call, and memory per kept process is **not measured** (risk R1).
- **No env var, no per-harness key.** `AGENTHQ_OPENCODE_SERVE` is deleted, not renamed. A harness that
  cannot keep a process has no adapter, and that is a fact about the harness, not a setting.

## 9. Windows console

The ticket reports a console window per reply for claude and pi. The probe spawned through Node with
`windowsHide`, which says nothing about the daemon's Java `ProcessBuilder`, so **the cause is not
identified**. There are three candidates:

- the runner's direct child;
- a grandchild the harness starts itself (tool shells, npm `.cmd` shims);
- the PTY runner.

The design commits to two things:

1. **A kept process can show a window at most once per session, not once per reply.** That is a direct
   consequence of section 5.
2. **Invariant 6:** every session process comes from `PipedProcessRunner` / `PtyProcessRunner`. If a flag or a
   different spawn path is needed, it goes there once, for every harness, never into an adapter.

**11c measures it** on the real daemon, started the way the app starts it: open a claude session, send two
prompts, then list every process descending from the daemon that owns a window.

```powershell
$root = <daemon pid>
$all  = Get-CimInstance Win32_Process
$kids = @($root); $i = 0
while ($i -lt $kids.Count) { $p = $kids[$i]; $all | ? { $_.ParentProcessId -eq $p } | % { $kids += $_.ProcessId }; $i++ }
$kids | Select-Object -Skip 1 | % { Get-Process -Id $_ -ErrorAction SilentlyContinue } |
  ? { $_.MainWindowHandle -ne 0 } | Select-Object Id, ProcessName
```

- **Pass:** the list is empty.
- **Fail:** the named process says which candidate it is, and the fix goes into the runner. Java's
  `ProcessBuilder` exposes no creation flags, so the options are a native spawn inside the runner or a change
  to how the daemon itself is started. That is decided only if the check fails.
- **11d** repeats the same check for pi.

## 10. Wire and contract impact

**Contract `1.60.0` → `1.61.0`** (`EventVocabulary.VERSION`, `EventVocabulary.kt:249`), shipped and
documented in CONTRACT.md by 11b. No new frame type, and every frame keeps its shape.

| Frame | Change |
| --- | --- |
| `exit` (raw stream, CONTRACT.md:1479) | **Behavioural.** It now reports that the session's hosted process ended without the daemon asking (a crash), during or between turns. A turn's end and its exit code are in `finished`. One-shot sessions **stop sending `exit` per turn** (≤ 1.60.0 did). M2-11 already stopped it for opencode, undocumented. |
| `stderr` boot lines | **Behavioural.** One `[agenthq] ↻ session <started \| resumed \| restarted \| restarted after a crash> (<label> · <model>)` per launch, for every harness. The per-turn lines move to `status.activity`: `starting … model … in …`, `resuming provider session …`, `process started (pid N)` and `Resuming session …`. The failure explanations of section 5 stay in chat. |
| `finished` | Shape unchanged. For a turn on a kept process, `exitCode` is 0 (COMPLETED), or the process code / 1 (FAILED). M2-11 already does this for opencode; it is now documented. INTERRUPTED still sends none. |
| `status` | `childPid` / `isProcessAlive` describe the session's live process: the kept process between turns, the turn's child during a one-shot turn. M2-11 reports `isProcessAlive=false` between turns while its server lives (`SessionManager.kt:734`); that is corrected. |
| `settings` | **Additive:** the key `session.keepAliveMinutes`. |

**Consumers checked:**

- **UI** (`agenthqUiCore/…/Model.kt:968`): renders `exit` as a muted "process exited with code N" LOG line.
  It now appears only on a crash, which is what that line is for.
- **CLI client:** `ClientMain.kt:383` closes on `finished`, and `:478` also on `exit`, so
  `agenthq client "<prompt>"` still exits.
- **Replay:** `SessionReplay.kt:97` only lists the type.

**Harness contract `1.3.0` → `1.4.0`** (`Harness.kt:23`): `CliHarness` gains `sessionAdapter()`. The change is
additive, but the handshake is exact-match, so both in-tree plugins (Claude, Pi) are rebuilt against 1.4.0 in
the same change. An out-of-tree plugin built for 1.3.0 is refused, as it is on every version change.

**Tests that pin the superseded behaviour.** 11b rewrites them on purpose: the new assertion is written first
and goes RED on old behaviour. It is never edited back to whatever the code happens to produce.

| Test | Pins |
| --- | --- |
| `agentHq/…/session/SessionBootLinesTest.kt` (8 tests) | The per-turn `starting` / `started` lines, plus RESUME-1's `Resuming session` / `resuming provider session` lines. The model-naming and auth-hint tests keep their meaning and read from Activity or the one boot line. |
| `agentHq/…/daemon/StubDaemonContractTest.kt:130` | `listOf("0")` raw exit for a stub session; becomes none |
| `agentHqUi/…/DaemonIntegrationTest.kt:192-201, 328-329` | The per-run boot LOG lines and the process-exit notice (UI-BOOT-1, UI-LOG-1, RESUME-1) |
| `agentHq/…/opencode/OpenCodeServeSessionTest.kt` (6 tests) | Move to `SessionHostTest` over a fake adapter (6.1) |
| `agentHq/…/session/SessionStoreWindowResumeTest.kt:199-209` | Reads `resume()`'s `error("unknown session …")` line **from the source**, so moving code is safe. Keep that call inside `resume()`. |

Checked and **unaffected**: `agentHqUi/…/ChatStderrUiTest.kt:30` and `agenthqUiCore/…/ChatModelTest.kt:77`.
Both feed the UI a synthetic `exit` frame, and the UI still renders an `exit` that arrives, so they stay as they
are.

This **supersedes** UI-BOOT-1's per-turn boot lines, UI-LOG-1's per-turn exit notice and RESUME-1's per-resume
line, which the owner's "exactly one collapsed boot line in chat" replaces. RESUME-1's intent (a continuation
is visibly distinct from a fresh start) survives as `↻ session resumed` vs `↻ session started`.

## 11. Follow-up tickets

Every gate follows `docs/AGENTHQ-EVIDENCE-BAR.md` at the repo root:

- **RED** is behavioural; a build failure is not a RED.
- **GREEN** is `:superiorSoftLibrary:agentHq:test --no-daemon --no-build-cache` with 0 failures, read from the
  JUnit XML (plus `:superiorSoftLibrary:agentHqUi:test` where noted).
- **Mutations** change the product, never the assertion, and each one must turn a named test red.

**Order:** 11b → (11c ∥ 11d ∥ 11f) → 11e. BUG-DEFMODEL can go at any time.

### BUG-M2-11b (fc3c9bbc): SessionHost core; opencode moves onto it, SessionManager branches removed. **CONFIRMED, scope adjusted**

**Builds:**

- `session/host/` with `SessionHost`, the interfaces of section 4 and `OneShotAdapter`;
- `OpenCodeServeAdapter`;
- SessionManager rewired, with every deletion in section 7;
- `CliHarness.sessionAdapter()` and HARNESS 1.4.0;
- `session.keepAliveMinutes` with 0 vs N semantics, as the replacement for the deleted env toggle;
- CONTRACT.md 1.61.0;
- the test rewrites of section 10.

**Adjustments to the filed scope:**

- *Added:* `OneShotAdapter`. Deleting the branches means every non-opencode harness must go through the host
  in the same change, so descriptor and stub harnesses land here, not in 11e.
- *Added:* the setting. The env toggle dies here, so its replacement ships here too.
- *Added:* the contract doc and the harness seam.
- *Moved out to 11f:* idle stop, live re-evaluation, orphan reaping.

**RED,** on today's code: a stub session gets two prompts. Assert exactly one `↻ session started (stub · …)`
chat line, no `starting …` / `process started …` chat lines, and zero `exit` frames, with the details found in
Activity. Today it prints two of each and sends two `exit` frames.

**`SessionHostTest`,** over a fake adapter with id **`fake-harness`** (deliberately not opencode):

- two turns, one launch;
- fingerprint change: restart and resume;
- death between turns: `exit`, and the next prompt relaunches once with the provider id;
- death mid-turn: FAILED, no retry;
- launch failure: FAILED with the stderr tail, and the next prompt launches again;
- interrupt: `abortTurn`, INTERRUPTED, no `finished`;
- `abortTurn` false: kill;
- close: close, then kill after the grace; `force`: kill at once;
- shutdown: nothing alive;
- `keepAliveMinutes = 0`: `fake-harness` runs one-shot.

**Mutations,** each turning a named test red:

| # | Mutation | Goes red |
| --- | --- | --- |
| M1 | Launch on every turn | "two turns, one launch" |
| M2 | Special-case a harness in `selectAdapter` (`if (provider.id == "opencode") …`) | The `fake-harness` keep-alive tests. This is the owner's "no special-cased harness" proof. |
| M3 | Print the boot line per turn | The RED test |
| M4 | Retry the turn after a death mid-turn | "no retry" |

**Gate:**

- `agentHq:test` and `agentHqUi:test`;
- the section-7 grep returns nothing;
- `OpenCodeServeSessionTest`'s behaviours are still covered.

**Size:** the largest of the series, about 3-4 h for one agent. **Recommended: keep it as ONE ticket.** The
SessionManager branches exist only for opencode, so moving opencode and deleting them is a single change. A
halfway state would run two lifecycles side by side, which is the defect this series removes. If the PM needs
tickets under an hour anyway, the least-bad cut is:

- **11b-1:** the host, `OneShotAdapter`, the boot-line/exit rule, the contract and the seam. Opencode keeps
  its M2-11 path for one ticket.
- **11b-2:** opencode onto the host, the M2-11 plumbing deleted, the setting.

### BUG-M2-11c (de770fe2): Claude Code adapter. **CONFIRMED**

**Builds:** `ClaudeSessionAdapter` (6.2), returned by both claude entry points, plus the translator fixes in
6.2.

**RED:** a counting fake runner replays stream-json, and two prompts on one claude session must produce
**one** `start`. After 11b it produces two, because claude is still one-shot.

**Tests:**

- one process for two turns;
- the repeated `system/init` gives one session announcement;
- the terminal error fires at the `result`, not at process exit;
- an interrupt gives INTERRUPTED, the process stays alive, and the next turn works;
- a model change restarts and passes `--resume`;
- close writes stdin EOF.

**Mutation:** end the turn at process exit instead of at `result`; the "two turns" test goes red.

**Manual:** the section-9 console check, plus the RSS of the kept process after three turns
(`Get-Process … WorkingSet64`), recorded in the ticket.

### BUG-M2-11d (b1510460): Pi adapter. **CONFIRMED, scope adjusted: Pi only, no Oh My Pi adapter**

**Builds:** `PiRpcAdapter` (6.3), returned by `PiHarness` (`harness/pi/PiHarnessPlugin.kt:23`) through
`CliHarness.sessionAdapter()`.

**Why no OMP adapter:** `omp` is a pi shim on this machine, and OMP is on the owner's backburner list ("do not
implement, do not stub").

**RED:** a counting runner, two prompts, **one** `start`.

**Tests:**

- end on `agent_settled`, with an `agent_end` → retry → `agent_settled` script whose turn must not end at
  `agent_end`;
- `response/abort` arriving after `agent_settled`;
- `disposition:"handled"`;
- `success:false`;
- a dialog request cancelled, and a fire-and-forget request kept out of chat;
- U+2028 in a prompt and in a reply;
- the session id taken from `get_state`.

**Mutation:** end the turn on `agent_end`; the retry test goes red.

**Manual:** the console check and the RSS figure, as in 11c.

### BUG-M2-11e (bbfe08dc): descriptor harnesses use SessionHost; one lifecycle setting in Settings UI. **ADJUSTED**

- **Descriptor half: done by 11b.** `OneShotAdapter` is what descriptors use, and 11b's stub/one-shot tests
  prove it. A declarative persistent mode for descriptors is **dropped** (YAGNI): none is installed, and none
  of the probed harnesses would use it.
- **What remains:** the Settings UI control for `session.keepAliveMinutes`. It shows 0 as "Off (start a process
  per reply)" and N as "Keep each session's process for N idle minutes".
- **RED:** the settings screen model has no control bound to the key.
- **Test:** reading and writing goes through `settings_set`, and the UI's unit test checks the label for 0 and
  for N.
- **Dependencies:** 11b for the key; 11f so the label describes real behaviour.
- **Size:** small.

### BUG-M2-11f (NEW, proposed): idle stop, live policy, orphan reaping

**Probe result (2026-10-19, before any code):** opencode serve SURVIVES. (A) A real daemon on port 0 with its own state
dir launched `opencode serve` for one prompt; after `taskkill /F` on the daemon's java pid alone, opencode.exe was still
alive at +1 s, +5 s and +15 s. (B) A parent-less serve with broken stdout/stderr pipes still created a session, ran a full
`prompt_async` turn (PONG) and answered 401 to a bad request. Reaping is therefore needed, not shrunk to a test.
Pitfall: a Node parent puts its children in a kill-on-close job object (libuv), so killing a Node parent kills the child
too; the probe's parent needed `detached: true`. The daemon is a JVM (plain `ProcessBuilder`, no job object).

**Builds:**

- the idle timer, on the injectable host clock;
- the live re-evaluation rules of section 8;
- reaping on daemon start.

**Reaping:** persist the kept process's pid and start instant (`ProcessHandle.info().startInstant()`) in the
session record. This is an additive `sessions.json` field. On start, tree-kill a live process only when **both**
the pid and the start instant match, so a reused pid is never killed. The record comes back IDLE and
resumable.

**First step, before any code:** measure the need. Hard-kill a daemon with a live opencode session, then check
whether `opencode.exe` survives. The probe proved that serve ignores stdin EOF; it did not prove that serve
survives a broken stdout pipe.

**Tests:**

- a fake clock gives a stop after N minutes, with no `exit` frame and no chat line, and the next prompt shows
  `↻ session resumed`;
- lowering N stops over-idle processes at once, and 0 stops all;
- reaping: a matching pid and instant are killed, a matching pid with another instant is left alone.

**Mutations:**

- ignore the start instant; the reused-pid test goes red;
- emit `exit` on the idle stop; the no-`exit` test goes red.

### Side ticket, filed by the PM as BUG-DEFMODEL: opencode's default model no longer exists

`OpenCodeCommand.DEFAULT_MODEL = "opencode/space-bunny-free"` (`OpenCodeCommand.kt:19`) is not in today's
catalog: `opencode models` printed 84 lines, and none of them is space-bunny. As a result, an opencode session
with no model and no `AGENTHQ_OPENCODE_MODEL` fails every turn. This is independent of the session host. The
fix is either to choose the default from the live catalog or to update the constant.

## 12. Risks and non-goals

| # | Risk | Mitigation |
| --- | --- | --- |
| R1 | Memory: N kept processes; RSS per process not measured | The idle stop (11f, default 30 min). RSS is recorded in 11c/11d. Add a cap only if those numbers call for one. |
| R2 | A model or effort change on claude restarts the process (1.4 s boot plus resume) | Acceptable; it is rare within a conversation. `set_model` can replace it later if it is probed. |
| R3 | A crash loop: a harness that dies on every launch | Impossible by construction. The host launches only on a prompt and at most once per prompt, and turns are never retried (invariants 2-3). |
| R4 | A protocol drift in a CLI update (e.g. pi renames `agent_settled`) | Each adapter has its scripted-stream tests, and the probe scripts are in the repo to re-run (`docs/session-host-probe/`). |
| R5 | opencode boots fail transiently (`ServeError`, probe run 2) | The turn fails with the stderr tail, and the next prompt launches again. |
| R6 | The 11b test rewrite is large (`agentHq` + `agentHqUi`) | Section 10 lists every test and why it changes. Rewrites are RED-first, never assertion edits. |

**Non-goals:**

- **Concurrency:** still one turn per session (`SessionManager.kt:555`). The host serialises per session.
- **Out of the host:** the model-catalog probes (`PiModelCatalog.kt:136` and `OpenCodeProvider.kt:115`, each
  a bare `ProcessBuilder`) and saved-command runs (`commands/RunManager.kt:38`, through its own
  `ProcessRunner`). They are not sessions, so they keep their own spawns.
- **No new listening ports.** Opencode keeps its 127.0.0.1 server with a random per-process password; claude
  and pi are stdio only.
- **No omp adapter, no persistent descriptor mode, no per-harness settings.**

## 13. Decisions

| # | Decision | Rejected alternative, and why |
| --- | --- | --- |
| D1 | The lifecycle lives in one daemon-owned `SessionHost` | A lifecycle-owning runner per harness: that is M2-11 three times, plus a SessionManager branch for each |
| D2 | An adapter is protocol only: restart, stop and boot lines are the host's | Adapters owning restart logic: divergent crash behaviour per harness |
| D3 | One-shot is an adapter behind the same host | A bypass path for one-shot harnesses: SessionManager would keep two lifecycles and two chat behaviours |
| D4 | One integer setting, `session.keepAliveMinutes`, default 30, 0 = off for all | A boolean plus a timeout (two keys that can disagree); per-harness env vars (the defect itself) |
| D5 | claude restarts and resumes on a launch-flag change; opencode carries the model per turn | `set_model` on a live claude process: not probed, so not designed on |
| D6 | `exit` = a hosted process ended unasked; one-shot turns stop sending `exit` | Keeping a raw `exit` per one-shot turn: the UI renders it in chat (Model.kt:968), which keeps a visible per-harness difference, the very complaint |
| D7 | Never retry a turn; restart only on a prompt; at most one launch per prompt | An automatic retry or a pre-launch: duplicate prompts to a harness and crash loops |
| D8 | No omp adapter; no declarative persistent descriptor mode | Both are speculative: nothing installed needs them (YAGNI) |
| D9 | HARNESS contract 1.4.0 for the new seam | Leaving it at 1.3.0: the handshake is exact-match, so a silent shape change would be invisible to plugin authors |
| D10 | Line-protocol adapters always run on the piped runner | Letting `AGENTHQ_RUNNER=pty` reach them: a PTY echoes input and rewrites line ends, which breaks JSON framing. Today's `OpenCodeServeRunner` already always pipes. |
