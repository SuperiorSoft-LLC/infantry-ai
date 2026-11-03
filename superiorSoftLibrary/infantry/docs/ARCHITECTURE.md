# Infantry daemon -- implementation tour

Infantry: a JVM-only, kubernetes-free daemon that drives an agent CLI session over a WebSocket, plus
a tiny CLI client and a one-shot provider probe. **Not KMP** -- see `build.gradle.kts` for why.

> **The public daemon ⇄ client boundary is frozen in [`CONTRACT.md`](../CONTRACT.md)**, versioned as
> `EventVocabulary.VERSION`. If you are building a UI, a client, or anything that talks to the
> daemon, **read that file and nothing else** -- it is written so you never need to open
> `ProcessRunner`, a provider class, or `SessionManager`. This README is the implementation tour.

## Layering (Phase 2)

```
AgentProvider                 the abstraction -- provider-shaped, no "process" in the name
  ├── OpenCodeProvider        argv + delivery for `opencode`
  ├── ClaudeCodeProvider      argv + delivery for `claude`  (same seam, no widening)
  └── (future) HttpProvider   an API-backed provider, not a subprocess
        │
        ▼  driven through
ProcessRunner                 the CLI TRANSPORT -- how a child is driven (pipes / PTY)
  ├── PipedProcessRunner      default; stdout/stderr drained separately, stdin closed, tree-kill
  └── PtyProcessRunner        parked stub (Pty4J not a dependency)
```

Phase 3 adds a third layer *above* both, without touching either:

```
SessionManager                the LIFECYCLE owner -- creates sessions, drives runs, persists,
  ├── startNew / resume         kills, resumes, reconciles on startup
  ├── kill (graceful -> force)
  ├── loadAndReconcile
  └── eventSink  ->  SessionEvent  ->  Envelope  ->  WebSocket
```

The provider seam is **untouched** by Phase 3: `AgentProvider` still only builds argv, delivers a
prompt to stdin, and parses a session id. A provider implements the lifecycle's *mechanics*; the
`SessionManager` is the only thing that decides a session's *state*.

Phase 4 adds a workspace layer beside the lifecycle, and still touches neither the provider seam nor
the runner:

```
GitWorktreeManager            the WORKSPACE owner -- one git worktree per session
  ├── create(repo)              unique branch + path, collision-safe, idempotent per session
  ├── status                    exists / dirty / ahead / behind, computed from git on demand
  ├── remove(force)             refuses a dirty worktree; KEEPS the branch (recoverable archive)
  └── reconcile(refs)           re-registers worktrees from persisted sessions at startup
        ▲  WorktreeProvisioner  (narrow seam `SessionManager` depends on)
```

Phase 6 adds a message layer beside both, still touching neither:

```
Mailbox                        the MESSAGE owner (threads, delivery, receipts, limits)
  └── MailRouter               bridges messages to sessions: delivery == resume with the message
```

Phase 9 adds a **contract layer** above all of it -- the one thing that does not touch the seams,
because it is a *view* of them:

```
EventVocabulary / Envelope     the PUBLIC boundary (versioned) — CONTRACT.md
  └── EventNormalizer          translates a provider's raw line into neutral events
        ▲
SessionEvent / SessionRecord   unchanged — the neutral stream is derived from these, so the two
  │                           streams can never disagree about what happened
StubProvider + ScriptedRunner  a scripted provider + transport, so a UI can be built and tested
                               with no provider CLI installed (INFANTRY_PROVIDER=stub)
```

Three things are chosen by **configuration**, the way the rest of the system expects:

| Variable | Default | Meaning |
| --- | --- | --- |
| `INFANTRY_PROVIDER` | `opencode` | Which [AgentProvider] (`opencode`, `claude`, **`stub`**) a session gets when its spawn names none. |
| `INFANTRY_PROVIDERS` | unset | Further providers a client may choose per session (comma list, e.g. `claude`). Unset = the default only. Contract 1.3.0. |
| `INFANTRY_RUNNER` | `piped` | Which [ProcessRunner] transport (`piped`; `pty` is inert). Not consulted for a provider with no child process (the stub brings its own). |

A provider supplies its own argv and its own session-id parsing; the daemon and the `Envelope`
protocol stay provider-neutral. Since Phase 9 the daemon additionally publishes a **normalised**
event vocabulary (`text_delta` / `status` / `error` / `finished`) beside the raw provider frames,
which is what a client should render -- see `CONTRACT.md`.

## Session lifecycle (Phase 3)

A **session** is provider-neutral and logical, not a process. Both supported CLIs (`opencode run`,
`claude -p`) are one-shot -- they consume a prompt, answer, and exit -- so a logical session is a
*sequence of runs* that share one provider-side session id. The daemon owns the sequence; the CLI
owns the conversational context behind that id.

One model, both providers, both directions: `SessionRecord` is the internal state, the persisted
row, and the wire payload. It carries `id`, `provider`, `workingDir`, `spawnedAt`, `lastHeartbeat`,
`state`, `providerSessionId`, `childPid`, `runCount`, `resumeCount`, `lastExitCode`, `killOutcome`
and `lastError`.

A session is in exactly one of these states:

| State | Meaning |
| --- | --- |
| `STARTING` | a run has been spawned, no output observed yet. |
| `BUSY` | a run is in flight and has produced output. |
| `WAITING_INPUT` | the provider needs a human answer to continue. **Defined, never produced today** (see below). |
| `IDLE` | no run in flight; alive, persisted and resumable. The resting state between prompts. |
| `EXITED` | ended on purpose (a kill), or the run ended while no daemon was watching. Terminal, resumable. |
| `ORPHANED` | a daemon restart found this session's child still alive. Terminal for this daemon, resumable. |
| `FAILED` | abnormal end: non-zero exit, or a run that could not be started. Terminal, resumable. |

One run at a time per session. A prompt that arrives while a session is `BUSY` is refused (the client
sees an `error`) rather than racing a second process onto the same provider session.

## Status reporting

Every state except `WAITING_INPUT` is **observed**, not guessed: it follows directly from facts the
runner reports -- the process was spawned, output arrived, the exit code -- and there is no inference
in it. `childPid`, `runCount`, `resumeCount` and `lastExitCode` are likewise process facts.

`WAITING_INPUT` is **defined but never assigned by this daemon**, and we say so plainly rather than
pretending to know more than we do: neither CLI, run non-interactively (no TTY), ever signals "I need
input" -- it either finishes or blocks. The state exists so a future interactive/PTY transport or an
HTTP provider can raise it without a protocol change. Until then, a blocking run simply stays `BUSY`.

M2-12: the status follows the agent's **turn**, not its process. A turn is `BUSY` (working) from the moment it is
accepted -- before the provider prints anything -- through reasoning, tool calls and the reply, and `IDLE` once it
ends. Booting a process for a turn is plumbing and never produces a `STARTING` flip (the daemon no longer assigns
`STARTING`); a process exiting between turns changes nothing; a crash mid-turn is `FAILED`, not `IDLE`.

## Persistence

Sessions survive a daemon restart. The store is a **JSON file** (`sessions.json`) behind a
`SessionStore` interface, written to `.tmp` and atomically moved into place so a crash mid-write
cannot truncate it; a corrupt file is treated as "no sessions" so the daemon always starts.

- Default location: `~/.infantry`, overridable with `INFANTRY_STATE_DIR`. Outside the repo by design,
  so a `git clean` cannot erase live sessions.
- **No database engine is added.** The Phase 3 need is "survive a restart", not "query sessions"; a
  SQLite/Exposed implementation becomes worth it in Phase 4 (worktree isolation), where sessions are
  queried and the write rate rises. Because the seam is an interface, that swap touches the store
  file and the construction site, not `SessionManager`.
- Write policy: a state transition, a newly observed `providerSessionId`, and a `childPid` change are
  force-written immediately (all three are crash-durability facts); plain heartbeats are throttled to
  once per second.

## Resume

`resume` is the operation that made losing a worker painful and is the one this phase exists for. It
clears the terminal state and starts a run whose request carries the stored `providerSessionId`, so
the CLI continues the **same** conversation (`opencode run --session <id>` / `claude --resume <id>`).
Context is not copied by Infantry; it lives in the CLI's own session store, which is exactly why the
provider session id is the durable field that matters.

Resume fails loudly for a session with no provider session id (nothing to continue) and refuses while
a run is already in flight. `SessionRecord.resumable` encodes the same rule for clients.

## Kill

`kill <id> [force]` stops a run in two steps and records honestly which happened:

1. **graceful** -- the only graceful signal these one-shot CLIs expose is stdin EOF, so stdin is
   closed and the daemon waits up to a grace window (default 2s);
2. **force** -- if the process is still alive (or `force` was requested), the whole process tree is
   killed (`taskkill /T /F` on Windows).

A deliberate kill always leaves the session `EXITED`; `KillOutcome` (`GRACEFUL` / `FORCED`) says
whether force was needed. Killing an idle session still resolves it to `EXITED` -- a half-dead agent
that looks alive is worse than a dead one.

## Crash handling (whose process is it?)

The daemon owns process trees **while it is alive**, and kills them on a graceful shutdown. If it dies
without cleanup, it does **not** kill recorded pids on the next startup -- a pid can be reused by an
unrelated process, and killing on that guess is worse than the alternative. It probes instead
(`ProcessHandle` by default):

- child gone  -> the run is over; the session becomes `EXITED` (its provider session id is kept);
- child alive -> the run outlived its daemon; the session becomes `ORPHANED`, not adopted (the pipe
  handle cannot be reattached) and not killed. Since both CLIs are one-shot, the orphan terminates
  itself; its pid is kept for visibility.

This is what makes a **remote** daemon possible later: session state is durable and self-reconciling,
so a client that does not own the daemon process can still reconnect and resume.

## Worktree isolation (Phase 4)

The phase that lets Infantry build itself with several agents in parallel without them fighting over
one working tree. A session that is given a repository runs in its **own git worktree on its own
branch**, so two agents never touch the same files.

**Create** -- `GitWorktreeManager.create(sessionId, repo)` shells out to `git worktree add` (the
`git` CLI, not JGit: linked worktrees and ahead/behind arithmetic are exactly what a re-implementation
gets wrong). The base is the requested ref resolved to an **immutable commit**, recorded as the
archive point.

**Branch and who owns the name** -- the manager owns branch names; a caller passes at most a task
*hint*. The name is `infantry/<slug-of-hint>-<session-tag>`, where the session tag comes from the
session's own unique id. That is the collision guarantee: two concurrent sessions on one repository
**cannot** derive the same branch. The path is `<repo>/.worktrees/<branch with '/' -> '-'>`, matching
the convention this repository already uses. `create` is idempotent per session.

**Collision handling** -- if the target path or branch already exists (a leftover, a reused id),
creation **retries** with a numeric suffix up to 5 times, then fails **loudly**. It never reuses,
overwrites or deletes another agent's worktree or branch. A provisioning failure FAILS the session
(`SessionRecord.lastError` set) instead of falling back to the shared checkout -- the fallback would
be exactly the collision this phase exists to prevent.

**Cleanup -- the default is RETAIN, and removal is reversible.** A killed or exited session keeps its
worktree and branch: they are the archive, and nothing is deleted by ending a session. Removal is
explicit (`worktree_remove`, or `/cleanup`) and:

- **refuses** a worktree with uncommitted changes unless `force` is passed (removing it would silently
  destroy work -- unacceptable);
- **keeps the branch** by default, so committed work is recoverable with
  `git worktree add <path> <branch>`.

**Visibility** -- every `session` record carries `worktreePath`, `worktreeBranch`, `repoRoot` and
`worktreeBaseRef`, and the daemon also emits a live `worktree` / `worktrees` frame carrying
`WorktreeStatus` (`exists`, `dirty`, `ahead`, `behind`), computed from git on demand, so a client can
show which agent is where and how far it has moved.

The minimal slice worth pulling forward (to build Infantry with a team *now*) is **create + wire +
retain** -- worktree per session, spawn in it, never auto-remove. Cleanup, collision retry and the
socket frames are the hardening on top.

## COMMS (Phase 6)

The phase that lets two agents **talk to each other** instead of a human relaying every step. It is
designed against the two delivery failures this project actually hit: an agent that went *silent* as
it neared its step limit (nobody waiting on it had a signal), and a QA run that lost its whole report
because it reported once at the end (per-check delivery would have survived).

```
Mailbox                       the MESSAGE owner -- threads, order, persistence, receipts, limits
  ├── send / thread / inbox     persist-before-deliver; threads read in order
  ├── deliverQueued             hands QUEUED messages over, in order, via the seam below
  └── eventSink  ->  MailEvent  ->  Envelope  ->  WebSocket
        ▲  MailDelivery        (narrow seam the mailbox drives)
MailRouter                    the BRIDGE to live sessions -- implements MailDelivery
  ├── onSessionEvent            a session that just came to rest gets its mail
  └── resume(session, prompt)   delivery == giving the agent its next turn
```

A message reaches a recipient the only way Infantry gives an agent a turn: by **resuming its session
with the message rendered as the next prompt**. That is literally "it lands when the agent's console
returns" -- the console *is* the session, and a session at rest is one a message can wake.

**Threads.** Every message belongs to a thread: an agent **pair** (a deterministic, order-independent
pair of session ids, so `A→B` and `B→A` are one conversation), a **run** (`run:<id>`), a **pod**
(`pod:<id>`), or any named thread. Each message carries a monotonic `seq` within its thread, so a
thread reads in order even when two messages share a millisecond. A reply always lands in the thread
of the message it answers, and always goes back to that message's sender.

**Delivery is durable and explicit.** `send` writes the message to `mailbox.json` as `QUEUED` and
*only then* can anyone try to deliver it -- so a daemon that dies between the two loses nothing, and
the next daemon delivers it when the recipient is free. The delivery policy is stated, not magic:

- recipient has a run in flight (`STARTING` / `BUSY` / `WAITING_INPUT`) → **retry**: stay `QUEUED`
  and hand over when that run finishes;
- recipient is resumable and at rest (`IDLE`, or a terminal state with a provider session id) →
  **deliver**: resume it carrying the message;
- recipient does not exist, or has no provider session id → **fail**: recorded as `UNDELIVERABLE`
  with the reason. This is the *visible* delivery failure the phase requires; it is never retried
  silently, because it can never succeed.

**Read receipts.** Silence has two opposite causes -- "not delivered yet" and "delivered, read, and
deliberately not answered" -- and from the sender's side they look identical, so they are separate
states, not booleans:

| State | Meaning |
| --- | --- |
| `QUEUED` | persisted, waiting for the recipient's console. Never lost. |
| `DELIVERED` | handed to the recipient's console (its run was started carrying it). |
| `READ` | the recipient acknowledged it (`mail_read`); reading requires delivery. |
| `ANSWERED` | a reply exists in the thread; clears a needs-input signal. |
| `UNDELIVERABLE` | delivery failed permanently, with the reason recorded. |

**Durable state.** A message may carry a `Checkpoint` (`state`, `decisions`, `evidence`, `blockers`,
`nextAction`). It rides in the delivered prompt, so a handoff survives a context reset: the receiving
console starts from where the last one stopped even with no memory of it.

**Needs-input.** A sender can flag a message `needsInput` when it is genuinely blocked. The daemon
reports the open signals (`mail_waiting`), filtered to senders that are **actually at rest** -- an
agent mid-run is not "waiting on you". A false waiting signal erodes trust faster than no signal, so
the flag is always explicit and always clears when the question is answered.

**Boundaries.** Both are checked *before* anything is stored, so a refused message is never delivered
later: the boundary is real, not cosmetic.

- a per-sender sliding-window **rate limit** (default 12 / 10 min);
- a per-thread **conversation ceiling** (default 60 messages). At the ceiling the send is refused and
  a `CeilingReached` event tells the humans to step in -- **stop and report beats silent truncation**.

**Answering a message (agent-facing).** A delivered message is a prompt handed to a *spawned CLI
agent*, which cannot speak the daemon's WebSocket. So the daemon also exposes COMMS over HTTP,
and the delivered prompt prints the exact commands. The surface is small and REST-ish:

| Method | Path | Body / query | Response |
| --- | --- | --- | --- |
| POST | `/mail/send` | a `MailSendRequest` (JSON) | the stored `Message`, or 400 + `MailRefused` |
| POST | `/mail/read` | `{"messageId": "..."}` | the updated `Message`, or 404 |
| GET | `/mail/inbox` | `?session=<id>` | list of `Message` |
| GET | `/mail/outbox` | `?session=<id>` | list of `Message` |
| GET | `/mail/thread/{threadId}` | -- | ordered `Message` list |
| GET | `/mail/waiting` | -- | list of `WaitingOn` |

The delivered prompt carries ready-to-run commands, e.g.

```
curl -s -X POST http://127.0.0.1:8787/mail/send -H "content-type: application/json" \
  -d '{"from":"<me>","to":"<them>","replyTo":"<id>","body":"..."}'
```

so a handoff can be **answered**, not only delivered, with no human relaying anything. It reuses the
same `Mailbox`/`MailRouter` as the WebSocket, so limits and receipts are identical. A send response
carries the message's *current* state (delivered, or undeliverable with the reason), because an HTTP
caller has no event stream. The mail API is behind the same gate as every other route. With no token it answers only loopback
callers; with a token every `/mail/*` route requires `Authorization: Bearer <token>`. The daemon hands
its agents the token as `INFANTRY_TOKEN`, and a delivered prompt's `curl` commands send
`$INFANTRY_TOKEN`, so the literal token never appears in a prompt. Set `INFANTRY_MAIL_BASE` to change the
address agents call; see [Remote daemon](#remote-daemon-phase-7).

**Why not SQLite**, same as the session store: a message list that is only ever read by thread does
not need a database engine. `MailStore` is an interface, so swapping one in later touches the store
file and the construction site, not `Mailbox`.

## What it does

- Ktor server (CIO) on `127.0.0.1:8787` (`INFANTRY_HOST` and `INFANTRY_PORT` change it), with a
  WebSocket endpoint at `/ws` and `GET /health` (`/health` reports the provider, runner, counts and
  contract version; it is the one route that never needs a token).
- Phase 6 also serves COMMS over HTTP (`/mail/send`, `/mail/read`, `/mail/inbox`,
  `/mail/outbox`, `/mail/thread/<id>`, `/mail/waiting`), so a spawned agent can answer a delivered
  message with `curl` -- the agent-facing half of COMMS.
- On each prompt frame the daemon drives one run of the selected provider's CLI, writes the prompt to
  **stdin**, closes stdin, and streams stdout/stderr back over the socket **and** to the daemon's own
  stdout. Every line and every state change is tagged with its session id.
- On connect the daemon sends the full session list, and thereafter broadcasts every session state
  change, so a client always sees the live picture -- including after a daemon restart.

## Run the demo (two terminals)

Terminal 1 -- the daemon:

```
.\gradlew.bat :superiorSoftLibrary:infantry:runDaemon
```

Terminal 2 -- the client:

```
.\gradlew.bat :superiorSoftLibrary:infantry:runClient
```

Client commands:

```
<line>                 send a prompt (starts a session, or continues the active one)
/new                   the next prompt starts a fresh session (default provider and model)
/new <provider> [model]   the same, on that provider and model; holds until the next /new
/providers             list the providers this daemon can spawn, with their default models
/new @<agentId>        the next sessions spawn from that agent profile; holds until the next /new
/agents                list the agent profiles
/agent-save <json>     create or overwrite a profile, e.g. {"name":"Backend","role":"backend"}
/agent-delete <id>     delete a profile (sessions spawned from it are unchanged)
/tickets               the backlog, column by column (`ticket_list`, contract 1.6.0)
/ticket <id>           one ticket (`ticket_get`)
/ticket-new <title>    create a ticket at the bottom of todo
/ticket-save <json>    create or edit a ticket, e.g. {"id":"ticket-1a2b3c4d","ticketType":"defect"}
/ticket-move <id> [status] [position] [wontfix]   e.g. /ticket-move <id> done wontfix
/ticket-link <id> <type> <otherId>   e.g. /ticket-link <a> blocked_by <b>
/ticket-unlink <id> <type> <otherId>
/ticket-reorder [status] <id> <id> ...   those tickets go on top of their column, in that order
/ticket-delete <id>    delete a ticket and its links
/sessions              list known sessions
/worktrees             list session worktrees and their status (path, branch, dirty, ahead/behind)
/cleanup <id> [force]  remove a session's worktree (refuses a dirty one unless `force`)
/kill <id> [force]     terminate a session (graceful, or force immediately)
/resume <id> <prompt>  resume a finished/dead session with its next turn
/mail <to> <subj> :: <body>   send a message (agent-pair thread); `from` = this connection's session
/need <to> <subj> :: <body>   same, flagged needs-input (asks for a reply)
/inbox | /outbox       this session's received / sent messages
/threads               list COMMS threads
/thread <id>           read a thread in order
/read <msgId>          acknowledge a message (delivered -> read)
/waiting               agents at rest that are blocked on an answer
exit | quit            close the socket
```

## Provider probe (Phase 0/2 comparison harness)

Runs ONE prompt through the selected provider + runner and prints exit code, line counts, session id
and elapsed time -- same listener and same stdin-EOF delivery for every provider:

```
.\gradlew.bat :superiorSoftLibrary:infantry:probeProvider
```

## Provider configuration

| Variable | Applies to | Default | Meaning |
| --- | --- | --- | --- |
| `INFANTRY_PROVIDER` | -- | `opencode` | `opencode`, `claude`, or **`stub`** (no CLI needed; see `CONTRACT.md` §4). The default provider. |
| `INFANTRY_PROVIDERS` | daemon | unset | Further providers a session may choose (comma list, e.g. `claude`); unknown names fail startup. A spawn may also name any model of a configured provider, metered ones included: the daemon checks a model's syntax, not its price (`CONTRACT.md` §2.2.1). |
| `INFANTRY_STUB_DELAY_MS` | stub (test-only) | `25` | Pause in ms before each scripted stub line. Raise it so a `cancel --force` can land mid-run (see [Running with no provider CLI](#running-with-no-provider-cli-phase-9)). Non-integer or negative fails loudly. |
| `INFANTRY_RUNNER` | -- | `piped` | `piped` (or `pty`, inert). |
| `INFANTRY_STATE_DIR` | daemon | `~/.infantry` | Directory of the durable session store. |
| `INFANTRY_DB` | daemon | `<stateDir>/infantry.db` | The SQLite database holding runs, pods, nodes and their event logs (Phase 4.5+). Opened at daemon startup. |
| `INFANTRY_REPO` | daemon | unset | Repository new sessions are cut a **worktree** from. Unset = other sessions share the daemon's directory (Phase 3), but a ticket start still cuts its own `ticket/` branch and worktree in the main checkout of the git repository that directory is in, also from a linked worktree (BUG-WT-1); the boot line names it. |
| `INFANTRY_BASE_BRANCH` | daemon | `HEAD` | The ref a ticket start cuts its `ticket/` branch from: that ref's **commit**, so uncommitted changes in any checkout are not on the branch and never refuse a start; the start answer says `cut from <ref> <sha>` (BUG-REGRESS-1). A ref that does not resolve refuses the start. |
| `INFANTRY_MAIL_BASE` | daemon | `http://<bind host>:<port>` (a wildcard bind is reached at `127.0.0.1`) | Base URL printed into delivered prompts for the agent-facing HTTP mail API. |
| `INFANTRY_PORT` / `INFANTRY_HOST` | daemon | `8787` / `127.0.0.1` | Bind address. A non-loopback host needs a token, or the daemon refuses to start (exit 2). |
| `INFANTRY_TOKEN_FILE` | daemon, CLI | unset | File holding the bearer token (UTF-8; a byte-order mark is ignored). Preferred on Windows. The daemon refuses to start when both token variables are set. |
| `INFANTRY_TOKEN` | daemon, CLI | unset | The token itself. When the daemon has one, it hands the value to its agents as `INFANTRY_TOKEN`. |
| `INFANTRY_URL` | CLI | unset | Daemon URL (`ws://` or `wss://`) when none of `--url`, `--endpoint`, `--host` or `--port` is given. The daemon exports it into every session it spawns. |
| `INFANTRY_POD_SPAWN_CAP` | daemon | `4` | Most live sessions a session may grow its pod by through `infantry submit` / `infantry_spawn` (contract 1.58.0). A spawn from the user or the UI is never charged. A malformed value fails startup. |
| `INFANTRY_POD_SPAWN_DEPTH` | daemon | `1` | How deep spawns from inside sessions may nest: `1` = a session the user started may spawn, a session it spawned may not. |
| `INFANTRY_ENDPOINTS` | CLI | `<state dir>/endpoints.json` | The named-daemon list; see [Remote daemon](#remote-daemon-phase-7). |
| `INFANTRY_OPENCODE_EXE` | opencode | auto-detected | Native `opencode.exe` (`%APPDATA%\npm\...`). |
| `INFANTRY_OPENCODE_MODEL` | opencode | `opencode/minimax-m3.1-flash-free` | **Free** model -- the demo must not burn a metered one. Used when the session, its profile and its ticket name no model; a blank or `default` value counts as unset. |
| `INFANTRY_BIN` | daemon | the installDist `bin` dir (`build/install/infantry/bin`; `runDaemon` installs it and sets this) | Directory holding the `infantry` / `infantry.bat` launchers. The daemon prepends it to every spawned session's `PATH` and exports it, so an agent can run `infantry knowledge` (BUG-G2). It also sets `JAVA_HOME` for the session when the daemon's environment has none. |
| `INFANTRY_CLAUDE_EXE` | claude | auto-detected | Native `bin/claude.exe` (`%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\...`). |
| `INFANTRY_CLAUDE_MODEL` | claude | CLI default | Alias (`haiku`) or full id. |
| `INFANTRY_CLAUDE_PERMISSION_MODE` | claude | CLI default | `--permission-mode` passthrough. |
| `INFANTRY_CLAUDE_MCP_CONFIG` | claude | none | `--mcp-config` values, comma/newline separated. |
| `INFANTRY_CLAUDE_STRICT_MCP` | claude | `false` | Adds `--strict-mcp-config`. |
| `INFANTRY_WS_URL` | client | `ws://127.0.0.1:8787/ws` | Client target. |

## One prompt = one process

Both `opencode run` and `claude -p` are one-shot: they consume stdin to EOF, answer once, and exit.
The daemon therefore treats each client message as its own run rather than holding a long-lived
interactive child; continuity comes from resuming the provider session, not from keeping a process
alive. A truly interactive child needs a PTY, which stays parked.

## Protocol

WebSocket text frames carry a JSON `Envelope { type, data, sessionId? }`. The vocabulary is
**provider-neutral**: it names transport facts, not any CLI's event schema.

- client -> daemon: `prompt`, `new`, `sessions`, `kill` (`"<id> [force]"`),
  `resume` (`"<id>\n<prompt>"`), and (Phase 4) `repo` (a path, empty to clear) and
  `worktree_remove` (`"<id> [force]"`); `worktrees` requests the list.
- daemon -> client: `ready`, `sessions` (list), `session` (one record), `stdout`, `stderr`, `exit`,
  `error`, and (Phase 4) `worktree` (one status), `worktrees` (list), `worktree_remove` (a
  `CleanupResult`).
- client -> daemon (Phase 6): `mail_send` (a `MailSendRequest` as JSON), `mail_read`
  (`"<messageId>"`), `mail_inbox` / `mail_outbox` (`"<sessionId>"`, blank = the frame's `sessionId`, else this
  connection's active session), `mail_thread` (`"<threadId>"`), `mail_threads`, `mail_waiting`.
- daemon -> client (Phase 6): `mail` (one `Message`), `mails` (a list), `mail_threads` (a list of
  `ThreadSummary`), `mail_waiting` (a list of `WaitingOn`), `mail_refused` (a `MailRefused` -- a
  rate limit or a ceiling, never a silent drop).
- contract 1.3.0: `new` may carry a `SpawnOptions` (`{"provider":"claude","model":"sonnet"}`) that
  chooses where this connection's next sessions run, and `providers` requests the list, answered by
  a `providers` frame (a list of `ProviderInfo`). A choice the daemon cannot honour is an `error`,
  never a session on another provider (`CONTRACT.md`, section 2.2.1).
- contract 1.4.0: agent profiles. `agent_save` (an `AgentProfile` as JSON) is answered by `agent`,
  `agent_list` and `agent_delete` (`"<id>"`) by `agents`. `new {"agentId":"backend"}` spawns the
  connection's next sessions from that profile, with its persona ahead of the first prompt, and every
  `SessionRecord` carries `agentId` and `role` (`CONTRACT.md`, section 2.2.2). Profiles live in the
  `agents` table of `INFANTRY_DB`.
- contract 1.5.0: the backlog. `ticket_save`, `ticket_get`, `ticket_move`, `ticket_link` and `ticket_unlink` are answered by `ticket`; `ticket_list`, `ticket_reorder` and `ticket_delete` by `tickets`. Every change is pushed to every connected client. See CONTRACT.md section 2.2.3.
- contract 1.6.0: ticket hand-out and controlled context. Moving to `in_progress` or sending `ticket_start_next` starts the ticket's assigned profile; busy profiles queue in `todo`, and session endings persist to `lastRun` and drain the lane. `settings_get` / `settings_set` expose the durable `context.includeLinkedTasksContext` project setting. Ticket/session additions and context rules are in CONTRACT.md section 2.2.4.
- contract 1.8.0: MVP projects and stories. `project_save` / `project_get` / `project_list` manage projects; `story_save` / `story_get` / `story_move` / `story_list` manage the story board. Tickets can carry `projectId`; projects own the ordered `storyIds`. See CONTRACT.md section 2.2.5.

`sessionId` tags every streamed line with the session it belongs to. It is optional, so a Phase 1/2
client that never knew the field keeps working. From contract 1.2.0, a client frame's `sessionId`
also *addresses* the session: `prompt` continues it, `mail_send` sends from it when the request's
`from` is blank, and `mail_inbox` / `mail_outbox` read its mail when the frame's data is blank. For
`prompt`, an unknown or non-resumable id is answered with `error` and is never forked into a new
session (`CONTRACT.md`, section 8, F1).

### The two streams

Since Phase 9 every lifecycle event produces frames on **two parallel streams**, both derived from the
same event, so they can never disagree:

| Stream | Frames | Who should render it |
| --- | --- | --- |
| **Public, versioned** (contract 1.0.0) | `text_delta`, `status`, `error`, `finished` | **everyone** -- provider-neutral, no CLI knowledge needed |
| **Raw** (unchanged since Phase 1) | `stdout`, `stderr`, `exit` | nobody new; kept so an existing client is untouched and the provider's exact bytes are never lost |

The provider's own stream lines are forwarded verbatim on `stdout` (opencode `--format json`, claude
`--output-format stream-json`). Translating each provider's events into the neutral
`text_delta`/`status`/`error`/`finished` frames happens in ONE place,
`com.superiorsoftllc.infantry.protocol.EventNormalizer`, and the full mapping is recorded in
`CONTRACT.md` §2.5 -- including what is deliberately *not* mapped (`step_start` / `step_finish` are
provider bookkeeping, and a provider's claim to be "done" never ends a run: that is the exit's job).

## Running with no provider CLI (Phase 9)

```bash
# Terminal 1 -- the daemon, with a scripted stub instead of a real agent CLI
INFANTRY_PROVIDER=stub .\gradlew.bat :superiorSoftLibrary:infantry:runDaemon

# Terminal 2 -- the client, unchanged
.\gradlew.bat :superiorSoftLibrary:infantry:runClient
```

The stub is a provider selected by configuration like any other, and it goes through every layer a
real provider does -- including the normaliser -- so a UI built against it renders a real provider
with no change. See `CONTRACT.md` §4.

**Test-only: making a run long enough to cancel.** The stub replays at 25 ms per line, so the
default 4-line script finishes in about 100 ms, before a CLI `cancel --force` over a real network
can arrive. Set `INFANTRY_STUB_DELAY_MS` (e.g. `10000`) on the daemon to stretch each line; a force
cancel then kills the replay and the session ends `CANCELLED`. Keep `lines x delay` under 30 s: the
in-memory runner's exit wait is 30 s, after which an uncancelled run reports failure. Unset = 25 ms,
behaviour unchanged. A "hold until cancelled" mode was judged unnecessary: a plain delay is enough.

## Cost note

The opencode provider defaults to the **free** `minimax-m3.1-flash-free` model; use it for any verification.
The claude provider has no free tier and draws on the Owner's Claude subscription -- do not run it
incidentally.

## Orchestration (Phase 5)

A **pod** is a goal decomposed into named **nodes** ordered as a DAG (`PodSpec` / `NodeSpec`). `Orchestrator`
runs it through `SessionManager`; it is reachable from the daemon over the wire since Phase 5b (`pod_submit` / `pod_query` / `pod_cancel`, answered by `pod`).

- **Model:** a run is one process; a node is a prompt + dependencies + retry budget; each attempt is a NEW
  run, linked by `node_attempts`. Edges (`node_dependencies`) join stable node ids, so retries never rewrite them.
  Schema v4 (`3.sqm`): `pod_nodes`, `node_dependencies`, `node_attempts`; `runs` is untouched.
- **Validation:** `PodValidator` rejects blank/duplicate/unknown/self/cyclic specs in memory before any write.
  The DAG is immutable after `submit`.
- **Scheduling:** per-pod `maxConcurrency` cap, ready nodes start in spec order; retries wait for a slot too.
  No fail-fast: a failed node skips only its transitive dependents.
- **Lifecycle comes from the daemon:** an attempt ends only on a `SessionEvent.Status` in IDLE/FAILED/EXITED/ORPHANED.
  Outcome is `killOutcome` first, then state (`RunOutcomes`); never the exit code.
- **Retry:** only `FAILED` is retried, as a fresh session whose prompt is the original plus `RetryFeedback`.
  Kills (graceful or forced) end CANCELLED; ORPHANED ends FAILED; neither is retried.
- **Cancel:** `cancelPod` / `cancelNode`, graceful or forced, via `SessionManager.kill`.
- **Review:** a CONVERGED pod puts every node in `reviewQueue()`; `review(nodeId, APPROVED|REJECTED)` records the
  decision only (no git changes).
- **Recovery:** after `store.recoverAfterCrash(now)`, `orchestrator.recover(now)` marks open pods INTERRUPTED.
- **Wiring:** the orchestrator never assigns `SessionManager.eventSink`; `InfantryDaemon.build` composes it (see Phase 5b below)
  as a third arm, after broadcast and the mailbox.

## Daemon wiring (Phase 5b)

Phase 5 built the orchestrator but deliberately left it unreachable. Phase 5b is the wiring, and its
only job is to make Phase 5 callable from outside the daemon.

- **The daemon owns the store.** `InfantryDaemon.build` opens a `SqlInfantryStore` on `INFANTRY_DB`, or
  `infantry.db` inside the state dir (`INFANTRY_STATE_DIR`, default `~/.infantry`) - the same place
  `sessions.json` and `mailbox.json` live. Opening a SQLite file during production startup is the
  reviewed act the earlier ruling asked for, so it is explicit at the call site, not buried in a
  constructor. `InfantryDaemon.close()` releases it, after the orchestrator and the sessions.

- **Recovery runs at startup, in this order:** `store.recoverAfterCrash(now)` closes runs still in flight as
  `ORPHANED` (appending a terminal `finished(ORPHANED)`), then `orchestrator.recover(now)` marks every
  open pod `INTERRUPTED`. Crash recovery FIRST, so a pod interrupted mid-flight is reconciled
  against runs that no longer claim to be running. Both are idempotent.

- **The event sink has three arms,** in this order, and the order is load-bearing:
  `broadcast(clients, event)` then `mail.onSessionEvent(event)` then `orchestrator.onSessionEvent(event)`.
  The orchestrator must observe the same events the clients are told about, or it schedules against
  stale state.

- **`/health` grew two counts:** `pods` (no end time) and `runsInFlight` (no end time). Every field
  1.0.0 published is unchanged.

- **Contract 1.1.0 is additive.** The four frozen frames (`text_delta`, `status`, `error`, `finished`)
  are byte-for-byte what 1.0.0 published; 1.1.0 only adds `pod_submit` / `pod_query` /
  `pod_cancel` / `pod` and the two health counts. See `CONTRACT.md`.

## Remote daemon (Phase 7)

A client on one machine can submit, watch and cancel a pod on a daemon running on another. The rules
are in `CONTRACT.md`: admission and tokens (sections 7.1 and 7.2), transport (7.3), finding a daemon
(7.4), reconnect (7.6), and the Phase 1 findings (section 8).

> **Trusted network only.** Infantry has no accounts and no login. The one credential is a bearer
> token that you create and share. The daemon does not encrypt anything: the token, every frame and
> every mail body cross the network in cleartext over `ws://` and `http://`. Run a remote daemon on a
> network you trust, or keep it on loopback and reach it through an SSH tunnel.

In this module `:run` starts the CLI. The daemon is `:runDaemon`, and `:runClient` is the legacy client.

### 1. Choose the bind address

| `INFANTRY_HOST` | Reachable from | Token |
| --- | --- | --- |
| unset (`127.0.0.1`) | this machine only | not needed |
| `0.0.0.0` | every network interface | required |
| a LAN address, such as `10.0.0.5` | that interface only | required |

A non-loopback host with no token makes the daemon refuse to start (exit code 2). A daemon bound to a
LAN address does not answer on loopback, so a client on the daemon's own machine must use that address
too. Agents are given `INFANTRY_MAIL_BASE`, which defaults to the bind address (or `127.0.0.1` for
`0.0.0.0`), so they can call back.

### 2. Create the token (PowerShell 7, on the daemon machine)

```powershell
New-Item -ItemType Directory -Force C:\infantry | Out-Null
$token = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
[System.IO.File]::WriteAllText('C:\infantry\token.txt', $token, [System.Text.UTF8Encoding]::new($false))
icacls 'C:\infantry\token.txt' /inheritance:r /grant:r "${env:USERNAME}:R"
Remove-Variable token
```

- The token is 64 characters, well above the 32-character minimum. Do not print it, paste it into a chat
  or a ticket, or put it on a command line. Copy the file to each client over a channel you trust.
- Only your account can read the file, so run the daemon as that account.
- Windows PowerShell 5.1 writes a byte-order mark. The daemon and the CLI both ignore it.

### 3. Open the port (elevated PowerShell)

```powershell
New-NetFirewallRule -DisplayName 'Infantry daemon' -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private
```

The rule covers **Private** networks only. A network that Windows classifies as Public stays closed,
which is the safe default. Change a network's classification only when you trust it.

### 4. Start the daemon

```powershell
$env:INFANTRY_HOST       = '10.0.0.5'            # or 0.0.0.0; leave unset for loopback only
$env:INFANTRY_PORT       = '8787'
$env:INFANTRY_TOKEN_FILE = 'C:\infantry\token.txt'
$env:INFANTRY_REPO       = 'C:\src\my-repo'      # optional: default repository for new sessions
.\gradlew.bat :superiorSoftLibrary:infantry:runDaemon
```

`INFANTRY_PROVIDER` selects the default provider as in the table above (default `opencode`), and
`INFANTRY_PROVIDERS` any others a session may choose (e.g. `claude`). The banner states how
the daemon admits callers, and a `WARNING` line appears when it listens beyond loopback. Check
reachability from a client machine; `/health` needs no token:

```powershell
curl.exe -s http://10.0.0.5:8787/health
```

`/health` reports the provider, the runner, the contract version and four counts. It names no session,
path, prompt or output (`CONTRACT.md` section 8, F4).

Stop the daemon with Ctrl+C in its window. An agent process can outlive a hard kill; end a leftover
process tree with `taskkill /T /F /PID <pid>`.

### 5. Install the CLI on each client

```powershell
.\gradlew.bat :superiorSoftLibrary:infantry:installDist
build\install\infantry\bin\infantry.bat --help
```

The launcher needs `java` on `PATH` or `JAVA_HOME` set. The `build\install\infantry` folder is
complete (it carries its own jars), so copy it to any machine with a Java 21 runtime. Below, `infantry` means
`build\install\infantry\bin\infantry.bat`. Flags can appear anywhere on the line.

### 6. Name the daemons

Put each daemon in `%USERPROFILE%\.infantry\endpoints.json`, the default location (`--endpoints FILE` or
`INFANTRY_ENDPOINTS` override it):

```json
{
  "endpoints": [
    { "name": "b", "url": "ws://10.0.0.5:8787/ws", "tokenFile": "C:\\infantry\\token-b.txt" }
  ]
}
```

- The file names token **files**. It holds no token values, so it can be shared without exposing a
  secret. A `token` key is ignored silently, so keep tokens in files.
- With `--endpoint`, the token is the endpoint's own `tokenFile`; a `--token-file` on the same command
  line overrides it. An endpoint with no `tokenFile` sends no token, and an endpoint never receives the
  environment token.

### 7. Use it

```powershell
infantry submit "<goal>" --endpoint b          # prints the pod id
infantry watch <podId> --endpoint b            # streams until the pod converges, fails or is cancelled
infantry runs <podId> --endpoint b             # the pod's runs, with outcome and session id
infantry logs <sessionId> --endpoint b         # a run's raw stdout and stderr, from now on
infantry pods --endpoint b                     # the sessions the daemon knows about
infantry cancel <podId> --force --endpoint b   # --force kills the process trees
```

Without an endpoint, pass `--url ws://HOST:PORT/ws` and `--token-file`, or set `INFANTRY_URL` and
`INFANTRY_TOKEN_FILE`. The resolution order is in `CONTRACT.md` section 7.4.

### 8. Drops and reconnects

- `watch` survives a dropped connection. It reconnects with backoff (250 ms, doubling to 8 s, about
  16 seconds of attempts in all) and keeps streaming. A refused token is never retried.
- Output produced while you were disconnected is not replayed. The contract has no replay (finding F2),
  so the CLI says so when it reconnects. `logs` shows lines from the moment it attaches.
- `submit` is never re-sent after a drop. If the connection drops before the answer, the CLI says the
  submit may or may not have been applied, and tells you not to re-send unless a duplicate pod is
  acceptable. The contract has no pod listing; `pods` lists sessions, so a pod that has started its
  runs shows up there.
- `cancel` and the read commands are re-sent after a drop, because repeating them gives the same answer.

### 9. Several daemons

Each command talks to one daemon, chosen with `--endpoint NAME` or `--url`. To work with two daemons,
run two commands. Fanning one command out across daemons is not built. Every daemon has its own token,
so give each endpoint its own token file.

### 10. Verify a deployment

Run these from a client machine, against the daemon on the other one:

1. `curl.exe -s http://10.0.0.5:8787/health` returns JSON with `"contract": "1.8.0"`.
2. `infantry pods --endpoint b` prints `SESSIONS: <count>`.
3. `infantry submit "<goal>" --endpoint b` prints a pod id.
4. In a second window, run `infantry watch <podId> --endpoint b`, then `infantry cancel <podId> --force --endpoint b`. The watch's final `[pod]` line shows `CANCELLED`.

The repository's gate test runs a client and a daemon on one machine, talking over that machine's LAN
IPv4 address. It exercises the real socket path, but it is not a route between two physical machines.
Run the steps above across your two machines before you rely on the deployment.

### 11. Windows notes

- **Provider executables.** The JVM cannot start an npm shim (`opencode.cmd`, `claude.ps1`); it fails
  with `CreateProcess error=193`. The providers use native executables: the defaults look for
  `opencode.exe` and Claude Code's `bin\claude.exe` in the npm global folder under `%APPDATA%` or
  `%LOCALAPPDATA%`; Claude Code is also looked for under `%USERPROFILE%\.local\bin`. Set `INFANTRY_OPENCODE_EXE`
  or `INFANTRY_CLAUDE_EXE` when detection misses them.
- **Process trees.** `cancel --force` kills an agent's process tree through the daemon. For a tree that a
  hard kill left behind, use `taskkill /T /F /PID <pid>`.
- **`local.properties`.** A new git worktree has no `local.properties`, and Gradle fails with
  `SDK location not found`. Copy it from the main checkout before the first build. It is gitignored:
  never print or commit its contents.
- **Line endings.** Keep `gradlew` LF-terminated when a Linux container uses the checkout. A CRLF
  script fails there with a `bad interpreter` error.
- **Paths belong to the daemon.** Paths in frames name folders on the daemon's machine. A client on
  another machine cannot open them, so do not assume a shared filesystem or matching drive letters.
- **git on PATH.** The daemon runs `git` for worktrees, so `git` must be on the daemon's `PATH`.

### 11a. Through Tailscale (a phone or another PC)

Tailscale gives each device on your tailnet a stable `100.x.y.z` address. A phone or a second PC on the
same tailnet can reach the daemon at that address with no port forwarding on the router. Use the same
token setup as sections 2 to 5.

1. **Bind to the Tailscale address, not `0.0.0.0`.** Then only tailnet peers can reach the socket, and
   the LAN and any public network cannot. On the daemon machine:

   ```powershell
   $ts = (tailscale ip -4 | Select-Object -First 1).Trim()   # e.g. 100.101.102.103
   $env:INFANTRY_HOST       = $ts
   $env:INFANTRY_PORT       = '8787'
   $env:INFANTRY_TOKEN_FILE = 'C:\infantry\token.txt'          # section 2; required, or the daemon exits 2
   .\gradlew.bat :superiorSoftLibrary:infantry:runDaemon
   ```

   The banner prints `listening on ws://100.x.y.z:8787/ws` once, followed by the non-loopback `WARNING`.
   Start Tailscale before the daemon. If the address is not up yet, the bind fails and the daemon
   refuses to start. As with any LAN bind, the daemon then does not answer on `127.0.0.1`, so local
   clients use the `100.x` address too.

2. **Scope the firewall rule to the tailnet** (elevated PowerShell). Use this rule instead of the
   Private-profile rule in section 3:

   ```powershell
   New-NetFirewallRule -DisplayName 'Infantry daemon (Tailscale)' -Direction Inbound -Protocol TCP `
       -LocalPort 8787 -RemoteAddress 100.64.0.0/10 -Action Allow
   ```

   `100.64.0.0/10` is the address range Tailscale assigns to peers. A connection from any other
   address stays blocked.

3. **Check reachability from the other device.** `/health` needs no token:

   ```powershell
   curl.exe -s http://100.101.102.103:8787/health
   ```

   A timeout means the firewall rule or Tailscale itself. A JSON answer means the path works.

4. **Connect a client** with the token file copied over a channel you trust (section 2):

   ```powershell
   infantry pods --url ws://100.101.102.103:8787/ws --token-file C:\infantry\token.txt
   ```

   You can also add it to `endpoints.json` (section 6) as
   `{ "name": "home", "url": "ws://100.101.102.103:8787/ws", "tokenFile": "C:\infantry\token.txt" }`.
   Any other client, such as a phone app, sends `Authorization: Bearer <token>` on the WebSocket
   upgrade. Without the token the upgrade gets `401` with a `Bearer` challenge, and only `/health`
   answers.

> **Transport.** Tailscale carries traffic between peers inside WireGuard, which encrypts it, so on
> this path the token and the frames are not readable on the wire. That covers the tailnet hop
> only. The daemon itself still speaks cleartext `ws://`, so the "trusted network only" rule above
> still applies to any non-Tailscale bind. The token is still required: tailnet membership alone
> must not be enough to drive your agents.

Regression guard: `MobileBindTest` (wildcard bind with no token refuses to start; token refused and
admitted on a wildcard bind; default bind is loopback). No test runs a real tailnet.

### 12. Roles and avatars (Phase 10a)

The **role catalog** (`com.superiorsoftllc.infantry.role`) is a JVM library; this phase adds **no protocol
surface**. The canonical id set (`RoleIds`) -- `architect`, `backend`, `frontend`, `fullstack`, `qa`,
`devops`, `security`, `mobile`, `pm`, `marketing`, `git`, `seo`, `i18n`, `brainstormer`, `bot`,
`chief-of-staff`, plus `custom` -- is a **portable contract** matching the Phase 9 agent-profile role
shape (`[a-z][a-z0-9-]{0,31}`), not a new taxonomy. A ticket-assigned role (`role:backend`) needs a
`role` key on the session record to have a producer; wiring that is a contract bump, **not this phase**.

Everything the catalog renders is **replaceable data, never compiled-in Kotlin**: there is no
`when (roleId)` and no sealed role type.

- **Bundled roles** -- `/roles/index.json` plus one persona markdown file per role. Swap the manifest
  and the set changes with no code change. An **absent or empty manifest is legal**: the canonical ids
  still resolve (a generated colour, no persona).
- **Custom roles** -- a directory of `<roleId>.md` files (the filename IS the role id), optional
  `name` / `color` front matter, body is the persona. A malformed file is skipped and reported, never
  fatal. Precedence: custom file > bundled manifest > the canonical contract.
- **Avatars** -- first hit wins, three tiers: (1) a **user override** at
  `<INFANTRY_UI_DATA_DIR|~/.infantry-ui>/avatars/<roleId>.<png|webp>` -- works for custom ids too;
  (2) a **bundled** asset named by `/avatars/index.json`; (3) a **generated** badge (role colour +
  initials). Tier 3 is load-bearing: the product works with **zero** bundled art.
- **Manifest provenance** -- each `avatars/index.json` entry is `{ file, source, licence }`, so the
  licence question is answered by audit. **This phase ships an empty manifest** (`{}`) because the
  licence is explicitly undecided; the resolver falls through to tier 3.
- **Uploads are client-side** -- `<INFANTRY_UI_DATA_DIR|~/.infantry-ui>`, not the daemon: a per-client
  skin, not a bug, and moving it daemon-side is a cheap later migration. Accept **PNG or WebP only**,
  at most **512 px** and **256 KB** (`AvatarUploads`).

Verify the shipped data and the chain without a UI: `.\gradlew.bat :superiorSoftLibrary:infantry:roleCatalog`
(add `-Proles=<dir>` for a custom-role directory).

## How to add a new message source

Every turn in a transcript carries a typed `MessageOrigin` (owner, agent, external, system), set where the turn is created. A new
source (a webhook, an email bridge, a scheduler) does not touch any client or know how the wire works:

1. Implement `mailbox/MessageSource`: `origin` (who it is, as a `MessageOrigin`) and `render()` (the text the harness's CLI
   reads; this is the only place any header text is written).
2. Deliver it with `SessionManager.resume(sessionId, source.render(), source.origin)`. The daemon puts `origin` on the `turn`
   frame; the UI, the CLI and the MCP tools already render every origin kind.
3. Need a new kind of sender? Add a case to `MessageOrigin` (daemon `protocol/MessageOrigin.kt` and the client twin
   `ui/model/MessageOrigin.kt`). The exhaustive `when` in `OriginRenderer.kt` stops compiling until clients handle it.

Never parse the rendered text back into an origin. The one exception is `LegacyOriginAdapter`, for transcripts older than 1.125.0.
