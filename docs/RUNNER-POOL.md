# Runner pool: one orchestrator, N peer runners (RUNNER-POOL-1, 2026-11-07)

Design record (dated). Ticket 8ebf769c. Builds on [DAEMON-CORE.md](DAEMON-CORE.md) section 6 (6.1 to 6.7, ORCH-1) and
does not repeat it. No product code ships with this record; the baby steps in section 9 are backlog tickets.

Owner (2026-11-07): "connect to multiple daemons or servers ... offload workers between local and remote, some workers
on the server and some locally". Owner rule (2026-10-29): location transparency. Callers never know local vs remote; it
is a binding chosen by config, never an if/else.

Questions only the owner can answer are marked **[Q: owner]** and collected in section 11.

## 1. Topology

* **One orchestrator** per project set: the board, tickets, comments, mail routing, knowledge, policy authoring, usage
  pacing and the client-facing session index. Clients (desktop, web, Android, CLI) connect to the orchestrator only.
* **N runners.** A runner is a daemon that runs sessions, harnesses, worktrees, builds and the watch runner (the
  "runner" rows of DAEMON-CORE 6.1). Runners are peers: the local PC is a runner named `local`, a server is a runner
  named by the owner (e.g. `hetzner-1`). There is no primary runner; `local` is only the default placement.
* **Default: the orchestrator is the local daemon** (the PC is both orchestrator and runner `local`). Reasons: nothing
  changes for a user who never adds a server; the board's SQLite stays where it is; no state migration.
* **Later: the orchestrator may live on a server** (always on, so tickets keep moving while the PC sleeps; also the paid
  hosted path of DAEMON-CORE 6). The interfaces below are the same in both cases; only which daemon holds the board
  changes. Moving the board is a data migration (expensive to reverse, section 10), so it is a later step, not step 1.
  [Q: owner] is the always-on server orchestrator wanted soon, or is "PC orchestrates, server works" enough for launch?

```
            clients (UI, CLI, phone)
                     |  ws (existing)
            +--------v---------+
            |  ORCHESTRATOR    |  board, mail, knowledge, pacing, session index
            |  (local daemon)  |
            |  RunnerPool -----+------------------------+
            +---+--------------+                        |
                | RunnerLink (in-process)               | RunnerLink (remote binding, wss, paired)
          +-----v-----+                           +-----v------+
          | runner    |                           | runner     |
          | "local"   |                           | "server-1" |
          +-----------+                           +------------+
```

### Options considered

| option | for | against | verdict |
|---|---|---|---|
| A. Orchestrator = local daemon, servers are runners | zero migration, local-only unchanged, smallest first slice | board stops when the PC sleeps | **default, step 1** |
| B. Orchestrator on the server, PC is a runner | always on; same shape as the hosted product | moves the DB; PC must reach the server for everything; larger blast radius | later (step 9), same interfaces |
| C. Federation: every daemon keeps its own board, they sync | no single point | two writers per ticket, conflict rules, much more code | rejected: complexity does not earn its place |

## 2. The interfaces (contract first)

Both directions already have a type in `infantryDaemonApi` (`daemon/api/orchestrator/OrchestratorLink.kt`):
`OrchestratorLink` (runner -> orchestrator, contract 1.1.0) and `RunnerLink` (orchestrator -> runner, today only
`ticketIdOf`). The pool design **extends those two**; it adds no parallel channel.

### 2.1 `RunnerLink` grows into the runner command contract

Plain data in and out, `OrchestratorReply` error model, idempotent on a natural key, exactly as 6.5 states for the other
direction. New operations (each a minor bump of a new `RunnerLink.CONTRACT_VERSION`, starting `1.0.0`):

| operation | natural key | carries | fail mode on `Unavailable` |
|---|---|---|---|
| `hello()` -> `RunnerInfo(name, contract, harnesses[{id, models, loggedIn}], projects, capacity, os)` | none (read) | what the runner can run | runner marked `down` |
| `startSession(StartSpec)` -> `SessionRef(sessionId)` | `StartSpec.startId` (ticket id + attempt) | role, provider, model, effort, project id, ticket id, branch, brief text, parent, env names (never values) | ticket stays in todo, start reported failed |
| `stopSession(sessionId, reason)` | session id | kill / stop | retried by the binding; card shows "stop pending" |
| `deliverMail(sessionId, MailItem)` -> `delivered/queued/failed` | (session id, message id) | the `MailDelivery` edge of DAEMON-CORE 6.3 | mail stays persisted in the orchestrator's mailbox, redelivered |
| `events(sinceSeq)` -> `List<SessionEvent(session, seq, kind, payload)>` | (session, seq) | status, finished, usage, progress, denied | replayed from `sinceSeq` on reconnect |
| `readTranscript(sessionId, fromLine, maxLines)` | read | on-demand transcript for the UI | UI shows "runner offline" |
| `health()` -> `RunnerHealth(state, running, freeSlots, load)` | read | heartbeat | after `runners.lostAfterSec`, `down` |
| `forwardFrame(agentIdentity, Envelope)` (runner -> orchestrator, see 2.3) | frame id | an agent's board/mail/knowledge call from a remote worker | the agent's tool call returns unavailable |

`ticketIdOf` stays. `LocalRunnerLink` wraps `SessionManager`, `MailDelivery` and the worktree code unchanged, the same way
`LocalOrchestratorLink` wraps the board: local behaviour is the same functions called the same way.

### 2.2 `RunnerPool` (orchestrator side)

```
interface RunnerPool {
    fun runners(): List<RunnerEntry>            // name, binding id, health, info; "local" always present
    fun link(name: String): RunnerLink?         // null = unknown runner
    fun runnerOf(sessionId: String): String     // the session index; "local" for every session today
}
```

A remote runner is a **binding** registered by a plugin (`api.hooks.runnerBinding(id, binding)`, declared in
`daemon.hooks`), the same mechanism as `orchestratorBinding`. Core holds the pool, the registry and `local`; the remote
transport lives in a plugin (`Infantry_RemoteRunner`), so the transport guard of DAEMON-CORE 6.5 extends to `RunnerLink`
unchanged. Every orchestrator-side call that today names `SessionManager` for spawn/kill/resume goes through
`pool.link(pool.runnerOf(id))` (the 6.6 list: `bindSessionOnSpawn`, `sessionStartedSink`, MailRouter resume).

### 2.3 Agent tool calls from a remote worker

A worker's MCP server and CLI talk to **their own daemon** (`INFANTRY_URL`). On a remote runner that daemon has no board.
Two options:

| option | for | against |
|---|---|---|
| a. Worker points at the orchestrator (`INFANTRY_URL` = orchestrator, agent token minted by it) | no proxy code | the server must reach the PC (reverse reachability), a second credential per worker, breaks location transparency |
| b. **The runner forwards** orchestrator-side frames over its existing link (`forwardFrame`) | one channel, one credential, the worker sees no difference | each frame needs a side tag (runner/orchestrator) in the frame table |

**Chosen: b.** The frame table gets a generic `side` attribute (a core seam, no feature); a runner daemon with a remote
orchestrator forwards `side = orchestrator` frames with the caller's agent identity, and the orchestrator re-checks them
through `FrameGate` as that agent. In local mode the attribute is never consulted (one runner, one orchestrator, same
process).

**Built (RUNNER-POOL-7, ticket 581cd08a).** The side tag: `FrameTable.register(..., side = FrameSide.ORCHESTRATOR)` (default
`runner`); a plugin declares it in `module.json` as `daemon.side` (the plugin's default) or `side` on one tool or frame
(Infantry_Board, Infantry_Notes, Infantry_Roles, Infantry_Scripting and Infantry_Attention are `orchestrator`); the mail frames
the connection loop answers inline are `orchestrator` (`CoreFrameSides`). Because only the orchestrator dials, forwarding is a
long poll over the existing link:

1. A frame from a session the remote orchestrator started on this runner (`RemoteStarts.isLinked`; a daemon's own session is
   never forwarded, so local mode never reads the tag) whose side is `orchestrator` is held by `FrameForwarder`.
2. The orchestrator's `ForwardPump` keeps one pull loop per paired runner, and pulls ONLY while that runner has a session the
   orchestrator placed there (`RunnerHub.hasSessionsOn`): `runner_link_forward_pull` waits up to `limits.runners.forwardWaitSec`.
3. `ForwardedFrameGate` refuses the frame unless its session is one the orchestrator placed on THAT runner (the runner name is
   the link's), it names no other session (its `sessionId`, a tool call's `client.sessionId`), its side is `orchestrator`, and
   `FrameGate` passes it as a non-owner. It then runs on the orchestrator's own `/ws` as a non-owner caller (`LoopbackFrames`,
   the agent token when the sandbox is on), so policy, plugin gates and the agent tier run exactly as for a local agent.
4. The answer goes back with `runner_link_forward_answer` and reaches the worker with its own request id. No answer within
   `limits.runners.forwardTimeoutSec` = an `error` "unavailable"; at most `limits.runners.forwardPendingMax` frames wait at once.

The orchestrator binding offers the same call as `OrchestratorLink.forwardFrame(AgentIdentity, ForwardedFrame)`
(`LocalOrchestratorServices.forwardFrame`), so a hosted orchestrator (step 9) answers it without the pump.

## 3. Placement: how a ticket start picks a runner

All data, never code-only.

* **Ticket field** `runner: local | <name> | any` (absent = no pin).
* **Routing rule field** `runner` on `protocol.RoutingRule` (`Projects.kt`), same values, default absent.
* **Setting** `runners.default` = `local`.
* Resolution order (one pure function, `Placement.pick`, unit-tested): explicit ticket pin > first matching routing rule's
  `runner` > `runners.default`. This mirrors how provider/model resolve today (pin > rule > weight mapping).
  RUNNER-AGENT-1 widens it to sessions outside the board: ticket pin > spawn `runner` arg (`infantry_spawn`, the `new` frame) >
  agent profile `runner` > routing rule > `runners.default` > local. A session with no ticket runs on the runner in its clone of the
  project remote (no ticket branch). An unknown, down, full or paused runner is refused with its reason, never a silent local start.
  `LocalRunner.start` returns one `StartedSession` (Local record or Remote view) to the `new` frame, `LocalSpawns` and mail.
* `any` = among runners that are `up`, have the ticket's project, have the pinned harness logged in, and have a free
  slot: the one with most free slots; ties go to `local`. None eligible = the start waits in todo with a reason on the
  card (same as a full lane today), never silently goes elsewhere.
* `<name>` that is down or full: the start is refused with the reason (`runner server-1 is down`); no fallback to local
  unless the rule says `any`. Predictable beats clever.
* **Caps:** `runners.<name>.maxWorkers` (default 1) per runner, under the global worker cap that exists today. The usage
  governor (the two-worker hook) keeps its global `maxWorkers`; it paces **per provider account**, not per runner,
  because a Claude login shared by the PC and the server is one usage meter. [Q: owner] is one Claude subscription
  logged in on two machines acceptable, or does the server get its own account / API key?
* **Health:** `up` (heartbeat within `runners.heartbeatSec`), `degraded` (late, or `hello` reports a harness logged out),
  `down` (silent for `runners.lostAfterSec`). Only `up` runners receive new starts.

### 3.1 A runner goes away mid-ticket

The session lives on the runner; its process may still be running there. The orchestrator cannot know, so it does not
guess:

1. Card shows the worker as `runner offline` (from `health`); the ticket stays in_progress. Nothing is reaped
   (`DoneWorkerReaper` treats an unknown runner session as "not finished", like `ticketOf` -> Unavailable today).
2. Mail to that session stays queued in the orchestrator's mailbox (already persisted before delivery).
3. On reconnect: `events(sinceSeq)` replays what happened; the board catches up. Nothing is lost that the runner kept.
4. After `runners.lostAfterMin` the owner gets an attention item. Setting `runners.onLost`:
   `attention` (default) | `restart-elsewhere` (opt-in: start the ticket again on placement, from the last pushed
   branch; unpushed work on the dead runner is lost, the attention line says so).

Unpushed commits are the real risk. Setting `runners.pushOnCommit` (default `false`, cheapest): `true` makes a remote
runner push the ticket branch after each worker commit so a restart elsewhere loses at most uncommitted edits.

Built as RUNNER-POOL-8 (`runners/RunnerWatch.kt`, run after each heartbeat round):

* The runner's outbox (`RemoteStarts`) numbers events per session and keeps them until an `events(acked)` call
  acknowledges them (`limits.runners.outboxMax`, oldest dropped first and logged). The acknowledgement rides on the next
  read, so a lost answer is answered again; the watch applies each `(session, seq)` once and logs a gap the bound left.
* A remote session has no record on the orchestrator, so `DoneWorkerReaper` reaps nothing of it. Mail to it goes
  through the pool (`MailRouter` `remoteSession`); an offline runner answers QUEUED, the mail stays queued, and the
  watch pumps it when the session comes to rest (`finished` / idle status) or the runner is back.
* After `limits.runners.lostAfterMin` one `runner_lost` attention item per outage. With `restart-elsewhere` the board's
  `restartElsewhere` op starts each ticket again via placement minus the lost runner (its pin or rule is skipped; a
  placement that still picks it is refused). A local restart fetches the ticket branch from `origin` when the checkout
  lacks it.
* `runners.pushOnCommit`: the runner checks each such session's HEAD every `limits.runners.commitCheckSec` and pushes
  the ticket branch when it moved.
* A remote session is in the orchestrator's session list (`RemoteSessionMirror`: the pool's index plus the runner's status events; state `STARTING` until its first event), so the board does not call a live remote worker gone and the `runner offline` badge can show for it. Its output is read from the runner on demand (`runners.transcripts = on-demand`): a `replay_request` for a remote session calls `readTranscript`, so `infantry logs <session>` prints what the runner kept (STDOUT/STDERR lines). Live streaming (`stream`) is not built yet.

## 4. Code and git on a remote runner

* **Source of truth is the project's git remote.** A project with no remote URL cannot be placed on a remote runner
  (placement refuses with the reason). The runner never receives files from the orchestrator.
* **Clone:** a remote runner keeps one cache clone per project under its state dir (`runners.cloneRoot`, default
  `<stateDir>/clones`), fetches before each start, cuts the ticket worktree from it with the existing `worktree` code.
* **Push:** the runner pushes the ticket branch to the remote when the worker finishes (and per commit if
  `runners.pushOnCommit`). Git credentials: a per-repo **deploy key** (or fine-scoped token) on the server, never the
  owner's personal key. [Q: owner] may the server push only `ticket/*` branches (recommended), or also the dev branch?
* **VC merges on the orchestrator's runner by default** (`local` today): VC is a ticket with a role like any other, so its
  placement is a routing rule (`role: vc, runner: local`). The local runner fetches the ticket branch from the remote,
  merges, pushes. This keeps the merge where the owner's main checkout and the build folder are. A server-side VC is a
  routing-rule change, not code.
* **Ticket files and paths:** ticket rows already carry relative paths (DAEMON-CORE 6.3 "paths are runner-local"); a
  remote runner resolves them in its own clone. `ticket files --related` is computed from git, so it works from any
  clone of the same remote.
* **Secrets and logins are per runner and never travel.** The owner logs in `claude` (and any other CLI) on the server
  themselves, over SSH. `hello` reports which harnesses are installed and logged in (a yes/no, not the credential). API
  keys, run secrets (`infantry_run_secrets`) and the keystore master key stay in each runner's own keystore. `StartSpec`
  carries env variable **names** a session needs, the runner fills values from its own store or refuses.
* **As built (RUNNER-POOL-6, first slice).** `Infantry_RemoteRunner` registers the `remote` runner binding: each
  operation is one frame over `api.runnerLinks` (core holds the url, pin and credential). A ticket placed on a remote runner
  is refused when its checkout has no git repository or no `origin` remote. Otherwise the orchestrator sends the remote URL, the base and the
  recorded branch, never a path. The runner clones it into `runners.cloneRoot` (blank = `<stateDir>/clones`, one clone per
  project), or fetches it again, cuts the ticket worktree with `TicketBranches`, starts the harness in it, and pushes the
  ticket branch to `origin` after each turn. Only `ticket/*` is pushed (owner decision): a start naming another branch is refused, and no other ref is ever pushed. Then it queues a `finished` event (`limits.runners.outboxMax` bound). The
  board records the branch the runner answers. `hello` reports the harnesses, whether each is signed in (today:
  registered and not refused by its provider), `runner.projects` and capacity. The pool heartbeat (`limits.runners.heartbeatSec`)
  sets up / degraded / down. The orchestrator reads the runner events after each round (RUNNER-POOL-8, section 3.1). Not yet:
  `envNames` are carried but not yet filled on the runner.
* **Build caches** are per runner and local (Gradle cache, node_modules). No sharing in this design; the first build on
  a fresh server is slow, later ones are warm. Shared caches are an opt-in later idea, not a step here.
* **Builds and releases** (the automation role) stay on `local` by routing rule unless the owner moves them: the dist
  folder and signing keys live on the PC.

## 5. Wire

What crosses between orchestrator and runner, and nothing more:

| orchestrator -> runner | runner -> orchestrator |
|---|---|
| start/stop session (`StartSpec`: ids, role/provider/model, brief text, branch) | `hello`, `health` |
| mail text for a session | session events `(session, seq)`: status, finished, usage numbers, progress, denied |
| transcript read requests (UI on demand) | transcript lines (on demand only) |
| | forwarded agent frames (board, comments, mail, knowledge calls of its workers) |

Never: file contents, diffs, credentials, keystore material. Transcripts cross only when a client asks to read one
(setting `runners.transcripts = on-demand`, the cheapest; `stream` is opt-in).

* **Channel:** one WebSocket per runner, carrying the existing `Envelope` (`infantryProtocol`) with a new frame family for
  the link operations. It reuses the daemon's server, auth hook and codec; the binding plugin owns it. Since RUNNER-POOL-6a
  the transport (WebSocket client, wss listener, TLS certificate and pin) lives in the `Infantry_RemoteRunner` plugin
  (`plugins/official/Infantry_RemoteRunner/daemon`) behind `RunnerLinkTransport` (`hooks.runnerLinkTransport`, DaemonApi
  1.44.0); core keeps the frames, pairing, credentials, rekey, detach, audit and allow-lists in `runners/`.
* **Who dials:** in step 1 the **orchestrator dials the runner** (a server is reachable; the PC usually is not). A
  reverse mode (runner dials out to the orchestrator, nothing listens on the runner) is the same link with the dial
  flipped; it is needed for option B / hosted orchestration and for runners behind NAT. After the handshake both modes
  are identical.
* **Versioning:** `hello` exchanges `OrchestratorLink.CONTRACT_VERSION` and `RunnerLink.CONTRACT_VERSION`; a major
  mismatch marks the runner `degraded("version")`; a missing minor operation answers `Unavailable("version")`. Callers
  never branch on the version (6.5 rule).
* **Ordering and replay:** the runner keeps an outbox of events with a per-session monotonic `seq`, kept until the
  orchestrator acknowledges it (bounded by `limits.runners.outboxMax`). The orchestrator dedupes by `(session, seq)`.
* **Idempotency:** `startSession` keyed by `startId`; a retried start after a timeout returns the same session.

## 6. UI

* **One board, one sidebar.** Clients still talk to the orchestrator only. The session list frame gains `runner`
  (default `"local"`), filled from `RunnerPool.runnerOf`. Transcript reads are forwarded by the orchestrator.
* **Runner badge** on each worker card and sidebar row: shown only when more than one runner is configured, so the
  single-machine UI is pixel-identical. Badge colour by health (`up` / `degraded` / `offline`).
* **"Add a server" flow** (a settings card in a new plugin, `Infantry_Runners`; CLI equivalents first):
  1. On the server: `infantry runner pair --name server-1` prints a one-time pairing code (expires in
     `runners.pairCodeMin`, default 10) and the server's TLS certificate fingerprint.
  2. On the PC: Settings > Runners > Add, or `infantry runner add server-1 wss://host:port --code <code>`; the UI shows
     the fingerprint for the owner to compare; on confirm the two daemons exchange a long-lived pair credential.
  3. The card lists runners with health, harnesses logged in, projects allowed, running/max workers, and actions:
     pause (no new starts), detach (revoke), rotate credential.
  The QR pairing link (`ui/pair/PairingLink.kt`) can carry the same code later; step 1 is CLI + card.

## 7. Security review (before any code)

A link that makes another machine start agent CLIs with tool permissions **is a remote-code-execution channel**. Today's
remote access (`infantry remote on`) is cleartext `ws://` over Tailscale with one shared token (`CliMain.remote`); that
is not good enough for a runner link and the runner link must not reuse that token.

| threat | control |
|---|---|
| Anyone who reaches the port starts processes | Runner link refused unless paired. Pairing = one-time code shown on the runner's own console (owner present on both ends), single use, short expiry. |
| Token theft from the wire | **TLS required** (`wss://`) for every non-loopback runner link; self-signed is fine, the certificate fingerprint is pinned at pairing. `runners.allowPlainTailnet` (default `false`) is an explicit opt-in to plain ws over a tailnet, with its risk stated in the setting text. |
| A stolen pair credential is valid for ever | Per-pair credential (not the owner token, not an agent token), stored in each side's keystore; rotated in-band every `runners.credentialRotateDays` (default 30); revocable from either side (`runner detach`). |
| Wrong orchestrator drives the runner | **Scoping:** a runner accepts link frames from exactly one paired orchestrator id. The runner credential can call only the link operations; it cannot call owner frames (settings, plugins, keystore, updater, remote on/off). |
| Orchestrator asks for anything | **Runner keeps the final say.** Every remote-originated start/stop passes the runner's `FrameGate` and active policy as a non-owner caller seat `orchestrator:<id>`. Runner-local allow-lists: `runner.projects` (which projects it will clone), `runner.harnesses`, its own worker cap, and a local kill switch `infantry runner detach`. A remote policy can tighten, never loosen (DAEMON-CORE 6.4). |
| Replay of a captured start | `startId` idempotency + per-frame nonce and timestamp window inside TLS. |
| Secrets in transit | None beyond the brief and mail text. Env values, API keys, harness logins never cross (section 4). |
| Forwarded agent frames escalate | The orchestrator re-gates every forwarded frame as the named agent; a runner cannot claim the owner identity (the credential is runner-scoped, the identity field is checked against the sessions that runner owns). |
| Compromised orchestrator = code on the server | Inherent. Mitigate by running the server runner as a dedicated unprivileged user or in the shipped docker image (`docker-smoke.yml` already builds it); documented in the add-a-server card. |
| Audit | The runner appends every remote operation (who, what, ticket, result) to its own audit log; `infantry runner audit`. |

Rate limits on pairing attempts (`runners.pairAttemptsPerHour`, default 5).

**As built (RUNNER-POOL-5, 2026-11-07).** Code: `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/runners/`.

- `infantry runner pair --name <n>` (runner console) makes the link certificate on first use (EC P-256, self-signed,
  `<stateDir>/runner-link/tls.p12`), prints its SHA-256 fingerprint and a one-time code, and serves the link until the code
  is used or expires. The code is single use, expires after `limits.runners.pairCodeMin`, and every try counts against
  `limits.runners.pairAttemptsPerHour` (kept on disk, so a new `pair` does not reset it; past the limit the live code is burned).
- **The pairing link (RUNNER-LINK-1).** `runner pair` prints ONE line first:
  `infantry://runner?v=1&name=<name>&host=<h1>&host=<h2>&port=<link port>&code=<XXXX-XXXX-XXXX-XXXX>&fp=<sha-256 hex, lower case, no colons>[&plain=1]`
  (codec `runners/RunnerPairLink.kt`; `host` repeats in the order a client tries them, IPv6 in brackets; `fp` is required unless
  `plain=1`; a link of a newer `v` is refused with "update this app"). The old code and fingerprint lines follow it under "or by hand:",
  and the `pair` answer keeps `code`/`fingerprint`/`port` next to the new `link`. Host order: every `--host` flag (repeatable, comma
  list ok), then the setting `runners.advertiseHosts` (default `[]`), and only if both are empty the detected non-loopback addresses
  of up interfaces (tailnet 100.64.0.0/10 first, then private, then the rest; docker/veth/link-local skipped); at most
  `limits.runners.advertiseHostsMax` (4). Accepting the link on the client is RUNNER-ADD-LINK-1.
- `infantry runner add <name> <wss-url> --code <c> --fingerprint <fp>` (orchestrator) checks the pin in the TLS handshake
  (no CA, no host name), then trades the code for a 32-byte per-pair credential. The orchestrator keeps it in its link
  keystore (`<stateDir>/runner-link`, separate from the owner keystore); the runner keeps only its SHA-256. With no
  `--fingerprint` the call stops after the handshake and prints the fingerprint the runner presented, to compare.
- Caller seat `orchestrator:<id>`: `FrameGate.refusal(type, seat)` lets that seat call only `runner_link_*` frames, and no
  other seat may call them. The runner accepts one orchestrator id; a second pair is refused until `infantry runner detach`.
- A remote start passes `runner.projects` and `runner.harnesses` (empty = none, `*` = any; harness = the start's provider),
  then reaches the runner's local link with no repository path from the wire.
- Rotation: the daemon rekeys each paired runner whose credential is `limits.runners.credentialRotateDays` old
  (`runner_link_rekey`; the old credential fails at once); `infantry runner rotate <name>` does it by hand.
  `infantry runner detach <name>` (orchestrator) or `infantry runner detach` (runner) revokes.
- The daemon serves the link (wss on `limits.runners.linkPort`) only once paired, checked every `limits.runners.linkCheckSeconds`;
  a machine never paired opens no port and no link keystore. `runners.allowPlainTailnet` switches to plain ws.
- Audit: `<stateDir>/runner-link/audit.jsonl`, one line per remote operation (refused ones too): time, seat, operation,
  ticket, result, peer address; `infantry runner audit [--limit N]` (default `limits.runners.auditShown`).
- Not built yet: the per-frame nonce and timestamp window of the replay row (start idempotency is `startId`), and
  orchestrator-side placement on a paired runner (later steps).

## 8. Settings (every trade-off named, cheapest default = local only)

| setting | default | meaning |
|---|---|---|
| `runners.enabled` | `false` | off: pool is `local` only, no runner link listener, no new UI. Nothing changes for a user who never adds a server. |
| `runners.default` | `local` | placement when no pin and no rule |
| `runners.<name>.url`, `.maxWorkers` | -, `1` | per runner |
| `runners.heartbeatSec` / `runners.lostAfterSec` | `30` / `120` | health; longer = cheaper |
| `limits.runners.lostAfterMin` | `30` | when an offline runner raises attention |
| `runners.onLost` | `attention` | or `restart-elsewhere` |
| `runners.pushOnCommit` | `false` | push ticket branch per commit (safer restart, more pushes) |
| `runners.transcripts` | `on-demand` | or `stream` |
| `runners.cloneRoot` | `<stateDir>/clones` | remote runner clone cache (blank = the default) |
| `limits.runners.outboxMax` | `500` | event outbox bound, kept until acknowledged (RUNNER-POOL-8; was `eventsKept`) |
| `limits.runners.commitCheckSec` | `30` | how often a runner checks `pushOnCommit` sessions for a new commit |
| `limits.runners.forwardWaitSec` | `10` | how long one forward pull waits on the runner (also the pump's retry pause; capped at half the link call timeout) |
| `limits.runners.forwardTimeoutSec` | `300` | how long a remote worker's orchestrator-side frame waits for its answer |
| `limits.runners.forwardPendingMax` | `64` | orchestrator-side frames one runner holds at once |
| `runners.pairCodeMin`, `runners.pairAttemptsPerHour` | `10`, `5` | pairing |
| `runners.credentialRotateDays` | `30` | rotation |
| `runners.allowPlainTailnet` | `false` | opt-in plain ws over a tailnet |
| `runner.projects`, `runner.harnesses` (runner side) | empty = none | what this runner agrees to run |
| `orchestrator.provider` (exists, env today) | `local` | unchanged; becomes a declared setting (ORCH-1 left item 3) |

## 9. Baby steps (each a backlog ticket with grep-able acceptance)

| step | ticket | what | behaviour change |
|---|---|---|---|
| 1 | RUNNER-POOL-2 | `RunnerLink` command contract + `LocalRunnerLink` + `RunnerPool` with only `local`; spawn/stop/resume call sites of 6.6 go through it | none |
| 2 | RUNNER-POOL-3 | placement data: `runner` on tickets and `RoutingRule`, `runners.*` settings declared, pure `Placement.pick` | none (only `local` resolvable; unknown name refused) |
| 3 | RUNNER-POOL-4 | session frame carries `runner`; badge on worker card, hidden with one runner | none visible |
| 4 | RUNNER-POOL-5 | pairing, runner-scoped credential, wss with pinned fingerprint, runner audit log (section 7) | only when paired |
| 4a | RUNNER-POOL-6a | move the link transport (WebSocket client, wss listener, TLS) out of core into `Infantry_RemoteRunner` behind `RunnerLinkTransport`; `infantry runner pair/add/rotate/detach <name>` go through the daemon as the owner-only `runner_admin` frame | none with the plugin on; without it no link is served and those commands say they need it |
| 5 | RUNNER-POOL-6 | `Infantry_RemoteRunner` binding: hello/health/start/stop/events over the link; remote clone + push; a ticket pinned `runner: <name>` starts on the server (**the first slice**) | only for pinned tickets |
| 6 | RUNNER-POOL-7 (built) | frame `side` tag + runner-side forwarding of agent frames (section 2.3), so a remote worker can read its ticket, comment and report | only remote workers |
| 7 | RUNNER-POOL-8 | event outbox + replay, runner-lost handling, `onLost`, `pushOnCommit` | only remote |
| 8 | RUNNER-POOL-9 | `any` placement, per-runner caps, governor per provider account; Runners settings card (add-a-server UI) | only when `runners.enabled` |
| 9 | (later, not filed) | reverse dial + orchestrator on a server (option B / hosted) | migration |

Steps 5 and 6 together are the owner's "some workers on the server" demo; 1 to 4 are safe groundwork that change
nothing locally.

## 10. Decisions that are expensive to reverse

* **The board lives in one place (single orchestrator).** Choosing federation later would rewrite ticket state rules. We
  choose single-writer now because it is the simplest correct model.
* **Git remote is the only code path to a runner.** Once runners depend on clone/push, "send files over the link" would
  be a security and size regression; we never add it.
* **Agent frames are forwarded by the runner (2.3 b).** Workers then never hold an orchestrator credential; switching to
  direct connection later means minting and rotating per-worker credentials across machines.
* **Moving the orchestrator to a server** (step 9) migrates the board DB; going back means migrating it again.
* **The pair credential format and the link frame family** become a wire contract between machines on different app
  versions; version them from day one (`hello`).

## 11. Open questions [Q: owner]

1. [Q: owner] Default orchestrator stays on the PC for now (recommended), or is an always-on server orchestrator wanted
   soon (board keeps moving while the PC sleeps)?
2. [Q: owner] One Claude subscription logged in on both PC and server (one usage meter, the governor paces per account),
   or a separate account / API key for the server?
3. [Q: owner] Server git rights: push `ticket/*` branches only with a deploy key (recommended), or also merge/push the dev
   branch (VC on the server)?
4. [Q: owner] Runner lost mid-ticket: raise attention and wait (recommended default), or restart elsewhere automatically?
5. [Q: owner] Transport for step 1: wss with a pinned self-signed certificate (recommended), or accept plain ws inside
   Tailscale as the first slice?

## 12. Deployment mode (RUNNER-POOL-10, 2026-11-07)

Owner (comment on 8ebf769c): one build, one image; a startup mode picks what runs. `DeploymentMode` (`daemon/DeploymentMode.kt`):
`both` (default, unchanged), `runner` (no board: `DeploymentMode.refusal` answers every client frame whose side is `orchestrator`, or in the
board, pipeline, knowledge, memory and role families, with "runner mode"; a session a remote orchestrator started is forwarded first, never
refused), `orchestrator` (`manager.spawnGate` refuses every local session; the board reports `local` as `orchestrator-only`, so placement
refuses it and `any` skips it). Sources: `--mode`, `INFANTRY_MODE`, setting `daemon.mode`. The runner-only start is documented in
[Run a remote environment](wiki/run-a-remote-environment.md).

Also built: the usage allowance counts a worker on a remote runner (`workerMeters` adds in-progress tickets whose session `runnerOf` is not
`local`, metered by the ticket's provider / model), so the pacing refuses a start over the account's allowance whichever runner it
would use; `runners.<name>.paused` (placement answers "runner x is paused"); `runner_admin` op `list` (name, url, pinned fingerprint,
health, workers, cap, paused; plus `enabled`) for the Runners card rows. Not built: the Compose rows of the card (Pause / Detach / Rotate
buttons) on `Infantry_Runners`; the CLI and settings cover them.

## RUNNER-REMOTE-IO-1: remote session I/O

The runner's on-demand transcript (`runners/RunnerTranscript.kt`) is built from the session stream by frame type only (turns, raw
lines, errors; answer text when a harness printed no raw line), so it holds for every harness. The orchestrator's `prompt` frame for
a remote session goes through `LocalRunner.deliverVia` (the mail path). `RunnerLinkServer` accepts transcript and deliver frames for
any session the orchestrator started (`RemoteStarts.isLinked`), with or without a git remote.
