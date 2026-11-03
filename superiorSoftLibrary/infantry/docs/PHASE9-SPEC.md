# Infantry Phase 9 — design spec (backlog, ticket start, triggers, honesty, quota)

> Design for Phase 9 `ae1cd5f1` ("planning surfaces: backlog, triggers, quota"), written under ticket
> `8e5ce1a9` (Phase 9a, design only). Baseline: contract **1.2.0**, `ahq-main` at `ad2cb1c`.
> Written 2026-10-09. No code was changed.

**Scope.** Answers the six questions of `8e5ce1a9`, plus two Owner requirements PM relayed on that
ticket on 2026-10-09 (comments `86f71ab4`, `49c89c1f`; memory note
`decisions-infantry-controlled-context-principle`):

- **R1** — ticket-driven starts: assign an agent or a role to a ticket, move it to `in_progress`, and a
  session starts carrying the ticket's specs. This is the primary Phase 9 behaviour.
- **R2** — how much context a started session receives is a setting.

R1 overrides the Phase 9 ticket's narrower wording. Each rule below is stated on its own terms, with
the reason for it; where a rule is a design choice rather than a requirement, the spec says so.

**Read only what your story needs.** Each story in §8 names its sections (R2 applied to this file).

**Evidence paths** are relative to `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/`
unless a path is given.

**Needs an Owner decision (does not block stories 9.0–9.6):** (a) polling undocumented account-usage
endpoints with the CLIs' stored credentials (§6.3 step 6); (b) admitting signed webhooks without the
daemon bearer, an `AccessPolicy` change (§4.6).

---

## 0. What exists today

| Fact | Evidence |
| --- | --- |
| One provider per daemon: `SessionManager` holds a single `AgentProvider`; the factory picks one from `INFANTRY_PROVIDER`. | `session/SessionManager.kt:54-55`, `provider/ProviderFactory.kt:65` |
| The model is per daemon, read from env when the command is built; `RunRequest` has no model. | `provider/AgentProvider.kt:16-20`, `opencode/OpenCodeCommand.kt:37`, `claude/ClaudeCommand.kt:54` |
| Both CLIs take a model flag, so a per-run model is plumbing, not research. | `OpenCodeCommand.kt:58` (`-m`), `ClaudeCommand.kt:106` (`--model`) |
| Only pod runs are durable: the orchestrator is the only writer of `runs`/`events` rows, and boot recovery only closes them. A plain session's run outcome is not in SQLite. | `orchestration/Orchestrator.kt:285, 297, 376, 399-407`; recovery `persistence/InfantryStore.kt:206-219`, called at `daemon/DaemonMain.kt:260` |
| Nothing has a wall-clock limit; the only timer is the kill grace. | `SessionManager.kt:60, 411` |
| Retry exists only inside pods: on FAILED only, bounded by `maxAttempts` (default 1); a kill is never retried. | `orchestration/RunOutcomes.kt:24`, `Orchestrator.kt:429-433`, `orchestration/PodSpec.kt:13` |
| On restart, open pods become INTERRUPTED and are never resumed. | `Orchestrator.kt:251-265`, `CONTRACT.md:310` |
| The mailbox already does visible refusal (12 per sender per 10 min, 60 per thread) and holds a message QUEUED until the recipient's run ends. | `mailbox/Mailbox.kt:147-161, 233-238`, `mailbox/MailRouter.kt:19-25, 89` |
| The run `events` table is pinned to the four vocabulary types by a CHECK. | `src/main/sqldelight/com/superiorsoftllc/infantry/db/events.sq:20-22` |
| `SessionRecord` has no `role`, `model`, `agentId` or `ticketId`. | `session/Session.kt:78-108` |
| No backlog, trigger, agent-profile or quota code exists in the daemon. | grep of `infantry/src/main`, 2026-10-09 |

---

## 1. Backlog and dependency enforcement (Q5)

### 1.1 Model (daemon-owned, SQLite)

- `tickets`: id, title, description, type (`feature`, `bug`, `chore`, `docs`, `refactor`, `test`,
  `perf`, `security`, `style`), status, position, `agentId` (a profile id or
  `role:<role>`), `provider?`, `model?`, `useWorktree`, `worktreeBranch?`,
  `includeLinkedTasksContext?`, `wontFix`, `queuedFor?`, `queuedAt?`, `sessionId?`, timestamps.
- `ticket_links`: (ticketId, relatedId, type). Stored directions: `blocked_by`, `child_of`,
  `relates_to`, `duplicates`. The wire also accepts `blocks`, `depends_on`, `parent_of`,
  `duplicated_by` and normalises them, so all eight names are accepted.

### 1.2 Columns

`todo` (ready) · `in_progress` (a session was started for it) · `pending` (held by an open prerequisite,
and nothing else) · `done`. An `in_review` column with a reviewer gate is deferred (§8).

### 1.3 The pending rule lives in the daemon; the UI only renders it

- `pending` is **derived, never set by hand**. A ticket is `pending` while at least one `blocked_by`
  prerequisite is not `done`. Adding such a link moves a `todo` ticket to `pending`. Closing the last
  open prerequisite moves it back to `todo` in the same transaction. Nobody starts a held ticket or
  moves it by hand: it returns to `todo` by itself once the last prerequisite is done.
- `ticket_move` into `pending` without an open prerequisite is refused, and so is `ticket_move` out of
  `pending` while the ticket is held. The refusal lists the open prerequisites.
- Reopening a `done` prerequisite re-holds dependents that are still `todo`. Started dependents are
  left alone: nothing is ever killed silently.
- A prerequisite closed as **won't fix** does not release its dependents. They stay `pending`,
  `holdReason` names it, and an attention item is raised (§5.4). Never start work whose premise was
  declined. This rule is a design choice.
- Cycles are refused (reuse `PodValidator`'s cycle check). A self-link is refused, as `nodes.sq:34`
  does. `child_of` is structure, not ordering, and never holds a ticket. Deleting a ticket removes its
  links and re-evaluates its dependents.
- **Why the daemon owns it.** State is what the daemon decides, never what a participant asserts. That is the session rule, `CONTRACT.md:440-442`. The UI's `reduce` is a
  pure renderer (`infantryUi/.../ui/model/Model.kt`, branch `feature-agenthq-phase8`). A client that
  bypasses the UI still meets the rule. The precedent is the pod DAG, whose `dependsOn` the
  orchestrator enforces (`Orchestrator.kt:327, 470`). The two differ on purpose: a failed upstream
  SKIPS a pod node (terminal), while an open prerequisite HOLDS a ticket (it waits as long as it
  takes). Reuse the validator, not the scheduler.

### 1.4 The hand-out rule

A ticket is handed out (a session started for it) **only** by one daemon function, `handOut(ticketId,
origin)`. It re-checks, at that instant and inside the same transaction, that every `blocked_by`
prerequisite is `done`. Every path goes through it:

- a human `ticket_move` to `in_progress`;
- `ticket_start_next`;
- a trigger action (§4);
- a capacity queue draining (§2.3).

Nothing caches "ready". A held ticket answers `start: {ok:false, reason:"held", detail:[open ids]}`.
While a prerequisite is still open, the ticket stays in `pending` and Start next skips it.

### 1.5 Position

Positions are contiguous per column, with 0 at the top. `ticket_reorder {status?, ids[]}` takes the
order from `ids`; unlisted tickets keep their relative order below the listed ones (`backlog_reorder`).
`ticket_start_next` takes the top `todo` ticket that is not held and whose assignee has capacity right
now (§2.3). Position orders work *within* an assignee lane; a busy lane never blocks a free one.

---

## 2. Starting a session from a ticket (R1, primary behaviour)

### 2.1 Agent profiles

`agents` table: id, name, role (canonical id: `architect`, `backend`, `frontend`, `fullstack`, `qa`,
`devops`, `pm`, `git`, …), `provider?`, `model?`, `instructions?` (persona text), `maxConcurrent`
(default 1). A profile is a **template**: a ticket start spawns a fresh session from it, and the
profile itself never runs. A ticket can therefore start while no saved session of that profile is
online.

### 2.2 What the spawn carries

| Ticket spec | Resolution (first non-null wins) | Today |
| --- | --- | --- |
| title + description | always: the prompt core (§3) | — |
| provider | ticket → profile → daemon default | **blocked**: one provider per daemon (§2.7) |
| model | ticket → profile → provider default (env) | **blocked**: no per-run model (§2.7) |
| skills | not in Phase 9; skills arrive with Phase 10 (`730e519b`) | absent |
| `useWorktree` / `worktreeBranch` | ticket; the branch is a hint and the manager owns the name (`SessionManager.kt:172-173`) | `startNew(prompt, workingDir, repo, branchHint, baseRef)` exists (`SessionManager.kt:176-182`) |
| agent / role | ticket `agentId`, recorded on the session as `agentId`, `role`, `ticketId` | absent (Phase 8 gap) |

- **Answer shape.** The `ticket` answer carries `executorOverrides` (what the ticket pins) and
  `effectiveExecutor` (what will run, with the source of each value). Reasoning effort is not carried: no CLI flag has been verified for it.
- **Unknown provider or model.** An unknown or unconfigured provider/model is **refused with a reason,
  never replaced**. A fallback to the executor's default model was rejected: a silent substitution
  can land on an exhausted account (memory `backlog-model-null-picks-dead-account`).

### 2.3 Role matching and the none-free case

- **Matching.** `agentId: "role:backend"` resolves to the profiles with role `backend`, in profile
  order. The first one with capacity wins. Capacity means fewer than `maxConcurrent` of its sessions
  have a run in flight (STARTING, BUSY or WAITING_INPUT). That is the same in-flight set MailRouter
  uses (`MailRouter.kt:19`).
- **None free → queued.** Not refused, and not over-spawned. The ticket stays `todo`: it is ready, so
  it is not `pending`. It shows `queuedFor: "role:backend"` and `queuedAt`. When a matching profile's
  run ends (`RunOutcomes.ATTEMPT_OVER`, `RunOutcomes.kt:10`), the daemon hands out the top queued
  ticket of that lane through `handOut`. A drained hand-out is unattended, so the launch gate (§6.3)
  applies. This is the mailbox's "QUEUED until the run ends" pattern (`MailRouter.kt:19-21`).
- **No profile has the role, or the named profile does not exist → `start_refused`, never queued.**
  It can never succeed, the same reasoning as UNDELIVERABLE (`MailRouter.kt:24-25`).
- **Named profile at capacity → queued on that profile.**
- **Restart.** A restart clears every queue mark and records one attention item listing them. Nothing
  starts on boot (§5.5).
- **Why profiles are capped.** A session per start with no per-agent cap was rejected. Infantry
  caps profiles because R1 asks for "an idle agent", and because quota (§6) makes unbounded spawns
  unsafe.

### 2.4 Repo and worktree

- **Where the repo comes from.** The client never names a path (F3, `CONTRACT.md:580`; "the daemon
  supplies WHERE", `DaemonMain.kt:691`). The backlog belongs to the daemon's `INFANTRY_REPO`
  (`DaemonMain.kt:195`), never to a connection's `repo` frame (`DaemonMain.kt:494, 578-582`), because
  a trigger-started ticket has no connection.
- **Fail closed.** `useWorktree: true` with no `INFANTRY_REPO` fails closed: `start_refused: no repo
  configured`, the ticket stays `todo`, and the start is not retried. A provisioning failure already
  fails the session instead of sharing the checkout (`SessionManager.kt:168-170`). `ticket_start_next`
  fails closed the same way (`start_refused`, not transient).
- **One backlog per daemon.** Several repos per daemon is out of scope.

### 2.5 The hand-out transaction

It runs on one worker or under one lock, like the Orchestrator, so two clients pressing Start cannot
hand out a ticket twice:

1. **Prerequisites:** re-check them (§1.4). If held, refuse.
2. **Executor:** resolve it (§2.2). If that is impossible, refuse. If there is no capacity, queue (§2.3).
3. **Launch gate:** apply it (§6.3). If it blocks, refuse with the rule as the reason.
4. **Prompt:** compose it (§3).
5. **Session:** call `startNew` with the provider, model, daemon repo and branch hint. The session
   records `ticketId`, `agentId`, `role`, `model` and `contextSources`.
6. **Ticket:** move it to `in_progress`, set `sessionId`, and push `ticket` to every client.

The answer is `ticket` with `start: {ok, reason: started|queued|held|start_refused|launch_blocked, detail}`.

### 2.6 After the run

- **Ticket state.** The ticket stays `in_progress`: the session is resumable, and a human may follow
  up. `done` is set by a human, or by the agent over the agent-facing HTTP path, as mail answers
  already are (memory `infantry-mailbox`).
- **Durable outcome.** It goes into `ticket_runs` (ticketId, sessionId, startedAt, endedAt, outcome,
  detail), because plain session runs are not persisted today (§0). The outcome comes from
  `RunOutcomes.of` (`RunOutcomes.kt:15`), not from `DaemonMain.finishedEnvelope` (§10.6).
- **Failure.** A failed run shows on the ticket as `lastRun`. The backlog never restarts it.
- **Done event.** Moving a ticket to `done` emits the internal `ticket_done` event that ship triggers
  read (§4.5).

### 2.7 Technical blockers (as PM asked: technical only)

1. **One provider per daemon** (`SessionManager.kt:55`). A ticket naming another provider cannot
   start. Story 9.0 adds a provider registry. `SessionRecord.provider` already exists
   (`Session.kt:80`), so resume can find the right provider.
2. **No per-run model** (`AgentProvider.kt:16-20`). Story 9.0.
3. **Skills do not exist in Infantry.** Phase 10 (`730e519b`) builds them, and Phase 10 is blocked by
   Phase 9. Phase 9 therefore ships without skills, and Phase 10 adds the fields. Phase 9 must not wait
   on Phase 10.
4. **No relay for webhooks** (§4.6). Infantry cannot mint a public URL.

---

## 3. Controlled context (R2)

Principle (Owner, via PM): a session knows its piece, not the epic; fewer tokens per session.

| Source | What goes in | Default | Setting lives in | Phase |
| --- | --- | --- | --- | --- |
| Ticket core | title + description, verbatim | always on, not configurable | — | 9 |
| Profile instructions | the profile's `instructions` | on when non-empty | the profile | 9 |
| Linked tickets | one line each (id, title, status, link type), never bodies | on | project default + ticket `includeLinkedTasksContext` | 9 |
| Parent epic body | never; the parent appears only as its one linked-ticket line | off, not offered | — | 9 |
| Trigger event | coalesced summary (count, first and last event), ≤ 2 KB, quoted as data | on for triggered starts | the trigger | 9 |
| Retry feedback | the previous attempt's last 10 errors + last 2000 chars (`orchestration/RetryFeedback.kt`) | pods only, on retry | node `maxAttempts` | exists |
| Skills | per `skillsMode` (merge/replace), `skillIds` | on, merge | project + ticket `includeSkillsContext`, `skillsMode`, `skillIds` | 10 |
| Project memory | a pointer to the index, not note bodies | decide in Phase 10 | project + ticket | 10 |
| Client context | — | not added: Infantry has no public feedback channel, so the source does not exist | — | — |

- **Where it lives.** Project defaults go in a daemon `settings` table (`settings_get` /
  `settings_set`, a closed key set; Phase 9 has one key, `context.includeLinkedTasksContext`, default
  true). Ticket overrides are nullable, with null meaning inherit. The `ticket` answer reports
  `effectiveContext` with the source of each value, mirroring `effectiveExecutor`.
  - *Why nullable:* per-ticket booleans that default to true would freeze each ticket at its creation
    value. Nullable inherit lets a project default change reach existing tickets.
- **Wire fields.** Phase 9 adds `includeLinkedTasksContext: Boolean?`. Phase 10 adds
  `includeSkillsContext: Boolean?`, `skillsMode`, `skillIds` and the memory switch. Those names are
  reserved here but not added early: no field ships before the daemon serves it.
- **Observability.** The session records `contextSources: [{source, chars}]`, so the token goal is
  measured per run rather than asserted.

---

## 4. Triggers and coalescing (Q1)

### 4.1 Model

- **Fields.** `triggers`: id, name, kind, action, enabled, state (`ARMED`, `RUNNING`, `TRIPPED`,
  `DISABLED`), `coalesceMs`, ceiling, `deadlineMinutes`, `stallMinutes`, `catchUp`, plus kind fields.
- **Kinds.** `manual`, `schedule`, `ship`, `webhook`. A `quota` kind needs the account bars from §6
  and is deferred.
- **Actions:**
  - **`start_next`** — Hands out the top startable ticket,
    optionally filtered by `agentId` or role. An empty column is recorded as `nothing_to_start`, not
    as a failure.
  - **`prompt`** — a prompt template plus `agentId` or role, and optional provider and model. It runs
    as a **one-node pod**, so its outcome is durable and recovery covers it (§0). This needs optional
    `agentId`, `provider` and `model` on `NodeSpec` (§7.3).
  - **`pod`** — a stored `PodSubmitRequest` template.

### 4.2 Coalescing: key, window, events arriving mid-run

- **Key.** The key is `triggerId`, and a trigger has at most one run in flight, always. Per-key
  parallel runs (for example one per branch) are deferred.
- **Window.** The first event on an idle, armed trigger opens a **fixed** window of `coalesceMs`. The
  window is measured from that event and does not slide, so the delay stays bounded. The default is
  120 000 ms, so ten pushes in a row become one run. Every event inside the window
  folds into one pending run. When the window closes, the run starts and carries the folded summary:
  count, first and last event, and the distinct delivery ids.
- **Mid-run.** Events that arrive while a run is in flight fold into **one follow-up slot** (count,
  first, last). That allows at most one follow-up run. It starts only if the current run succeeded
  (§5.2), and it opens its own window. N runs are never queued. This is the mailbox pattern: one queued
  delivery, released when the run ends.
- **`coalesceMs: 0`.** It only means "start immediately", never one run per call: mid-run events
  still fold. The reason is the Phase 9 ticket's
  rule: "a webhook storm must not become a hundred agents".

### 4.3 Rate ceiling

At most `ceiling.runs` starts per `ceiling.windowMs` per trigger (default 6 per hour), mirroring
MailLimits (`Mailbox.kt:158-161`). An event that would exceed it is recorded `refused_ceiling`, and
the trigger **trips** (§5.2). Hitting the ceiling means "stop and report to the human"
(`Mailbox.kt:149`), not a delay.

### 4.4 Schedules and catch-up

- **Fields.** The schedule fields are `frequency`, `everyMinutes`,
  `timeOfDay`/`timesOfDay`, `weekday`, `dayOfMonth`.
- **Catch-up.** `catchUp` defaults to true. Slots missed while the daemon was down
  fold into **one** catch-up run, never one run per slot, and the count of missed slots is recorded.
  With `catchUp: false`, a slot missed by more than 10 minutes is recorded `missed`.
- **Fire during a run.** A fire that arrives while a run is in flight folds into the follow-up slot.

### 4.5 Ship events

A ship trigger fires when a ticket enters `done`, **once per ticket id**, so reopening and closing the
same ticket does not fire it again. A won't-fix ticket never fires it. A manual
`trigger_fire` replays the last shipped ticket. Firing on entry to
`in_review` waits until that column exists (deferred).

### 4.6 Webhooks

- **Admission.** `POST /hooks/{triggerId}` is admitted **without the daemon bearer**, but only with a
  valid per-trigger signature, the v1 scheme:
  `X-Infantry-Signature: v1=<hex HMAC-SHA256 of "v1:<timestamp>:<delivery>:<raw body>">`, plus
  `X-Infantry-Timestamp` and `X-Infantry-Delivery`.
- **Replay and duplicates.** The replay window is 300 s (allowed range 60–3600). A delivery id seen
  before is recorded `duplicate` and never run.
- **Payload.** The body is capped at 64 KB. The payload is untrusted data: it is quoted into the
  prompt, never treated as instructions.
- **Contract impact.** This changes `daemon/AccessPolicy.kt` and `CONTRACT.md` §7.1, so it is a story
  of its own and needs an Owner decision. The Origin refusal stays.
- **No relay.** Infantry has no relay that mints a public URL. A sender has
  to reach the daemon directly, over LAN, VPN or the operator's TLS reverse proxy. `CONTRACT.md` §7.3
  assumes a trusted network.
- **Deferred.** Vendor presets (GitHub `X-Hub-Signature-256` and others), filters and restricted runs.

### 4.7 Every event gets a disposition

`trigger_events` holds one row per event: id, triggerId, receivedAt, source, `deliveryId?`, digest
(≤ 2 KB), disposition. The dispositions are `started`, `folded`, `followup_folded`,
`dropped_after_failure`, `suppressed_tripped`, `refused_ceiling`, `duplicate`, `missed`,
`nothing_to_start` and `refused_launch_gate`.

- **No silent drop.** Every event gets a row; retention is the last 500 rows per trigger.
- **Separate table.** These rows are not run events, and the `events` CHECK (`events.sq:22`) stays
  as it is.

---

## 5. Unattended honesty (Q2)

### 5.1 Success

- **Pod-based actions.** A `pod` or `prompt` action succeeds when its pod reaches `CONVERGED`.
- **Ticket hand-outs.** A `start_next` action succeeds when the ticket's run ends `SUCCEEDED`.
- **Everything else fails:** FAILED, CANCELLED, INTERRUPTED, KILLED_*, ORPHANED, DEADLINE, STALLED and
  `start_refused`.

### 5.2 The trip rule

A failure moves the trigger to `TRIPPED`, in the same transaction that writes an attention item. A
tripped trigger:

- starts nothing until a human sends `trigger_arm`;
- drops its follow-up slot, recorded as `dropped_after_failure` with the event count;
- records every later event as `suppressed_tripped`.

Acknowledging the attention item does **not** re-arm the trigger; arming is a separate, explicit act.

### 5.3 Deadline and stall

- **Limits.** Every unattended run has `deadlineMinutes` (required, default 60) and `stallMinutes`
  (default 15): no output while a run is in flight, measured on `SessionRecord.lastHeartbeat`
  (`Session.kt:85`).
- **On expiry.** The run is killed, graceful then forced (`SessionManager.kill`, `SessionManager.kt:391`; `Orchestrator.cancelPod`
  for a pod), and recorded as `DEADLINE` or `STALLED`.
- **Why both are required.** No run has any timeout today (§0), so either of these holds the in-flight
  slot forever and the trip rule never fires:
  - `WAITING_INPUT` is never produced (`CONTRACT.md:436`), so a run blocked on a permission prompt
    looks BUSY indefinitely.
  - An exhausted OpenCode free quota has been seen to **hang** rather than fail. The endpoint
    answered a retryable HTTP 503 ("Endpoint is unavailable"), and `opencode run` retried forever with
    zero stdout. This was seen on three free models and is recorded in project memory
    `opencode-cli-workers` (2026-10-08). Only the stall timer catches it.
- **The scheduler stall.** The orchestrator's `scheduler stalled` throw (`Orchestrator.kt:349`) is
  caught only by `guarded` (`Orchestrator.kt:105-110`), so the pod stays RUNNING. The deadline is the
  backstop, and §10.4 reports the stall itself.
- **Scope.** Human-started ticket sessions get no deadline by default.

### 5.4 What is reported, and where

1. **Durable.** An `attention` row records: id, at, kind (`trigger_tripped`, `deadline`, `stalled`,
   `ceiling`, `launch_blocked`, `prerequisite_declined`, `queue_cleared`), `triggerId?`, `ticketId?`,
   `podId?`, `sessionId?`, outcome, detail, `ackedAt?`.
2. **Push.** An `attention` frame is broadcast to every client as soon as the row is written, the same
   way mail frames are (`DaemonMain.kt:459`). Never stdout only (compare `CeilingReached` today,
   `DaemonMain.kt:452-457`).
3. **Pull.** `attention_list` / `GET /attention`. This is the replay for honesty events, so a client
   that was disconnected loses nothing, and it does not wait on F2 (`CONTRACT.md:574-578`).
4. **On the object.** The trigger shows `TRIPPED`, `trippedAt` and `trippedReason`; the ticket shows
   `lastRun`.
5. **Operator.** A stderr line, as today.

### 5.5 Restart

- **Runs in flight.** A triggered run that was in flight ends as INTERRUPTED (pods,
  `Orchestrator.kt:251-265`) or EXITED/ORPHANED (sessions). That counts as a failure, so the trigger
  trips with "daemon restarted mid-run".
- **Open windows.** An open coalescing window is dropped and recorded; the trigger stays armed.
- **Capacity queues.** Queue marks are cleared (§2.3).
- **Nothing starts on boot** except a schedule catch-up (§4.4).

### 5.6 Never silently retried: every path

- The trigger layer never retries.
- The backlog never restarts a failed ticket run.
- A refused start (held, impossible executor, no repo, launch gate) is never retried by the daemon.
  The capacity queue is not a retry, because that run never started.
- Pod retries stay as they are: opt-in per node, bounded by `maxAttempts`, and visible as attempts with
  `RetryFeedback`. A `prompt` action creates its node with `maxAttempts = 1`. A `pod` template keeps
  its own value, and that value is shown.

---

## 6. Quota visibility (Q3)

### 6.1 Answer

**No, the daemon cannot read provider quota today.**

- Nothing in `AgentProvider` asks for it.
- The normalizer drops the lines that carry usage (`protocol/EventNormalizer.kt:19, 69, 102`).
- Raw stdout is not stored.
- `RunRecord` has no usage columns, and `/health` has none (`DaemonMain.kt:80-92`).

**The sources do exist outside the daemon.** A usage dashboard already running on this machine
reported live bars on 2026-10-09:

- **Claude:** "Current session" 40%, "Current week (all models)" 15%.
- **OpenCode plan `opencode-go`:** "5h limit" 0%, "Weekly limit" **100%** (resets
  2026-10-12T00:00Z), "Monthly limit" 99%.
- **Free OpenCode models:** no bar at all.

### 6.2 Data source per provider

| Provider | Source | Gives | How verified |
| --- | --- | --- | --- |
| Claude Code 2.1.295 | Account endpoint `GET /api/oauth/usage`, behind Claude's own `/usage` screen | session (5 h) and weekly bars: percent used, reset | The path string and the labels "Current session" / "Current week (all models)" are in the installed binary, and that usage dashboard shows the same labels. Undocumented; needs the CLI's OAuth credential. **Not called.** |
| Claude Code | In-stream `{"type":"rate_limit_event","rate_limit_info":{status, resetsAt, rateLimitType, utilization, overageStatus, isUsingOverage, …}}` | limit state at run time | Builder functions in the installed binary. **Not observed at runtime**: Claude is not run incidentally (README cost note). |
| Claude Code | `result` line: `total_cost_usd`, `usage`, `modelUsage` | per-run usage | Strings in the binary; no fixture captured. |
| Claude Code | `--max-budget-usd` (print mode) | a per-run spend cap: a lever, not visibility | `claude --help` |
| OpenCode 1.18.35, plan `opencode-go` | 5 h / weekly / monthly bars exist (the usage dashboard shows them) | percent used, reset | **Source not identified.** The installed CLI has neither those labels nor a usage command (`opencode providers` = list/login/logout). Ask the Owner; do not guess an endpoint. |
| OpenCode, free models (`opencode/*-free`) | none | — | No bar anywhere. The installed binary classifies failed requests: `FreeUsageLimitError` → `free_tier_limit`; a Go limit → `account_rate_limit`, "It will reset in …". The exhaustion recorded in project memory (`opencode-cli-workers`, 2026-10-08, not reproduced here) matched neither: it was a generic retryable 503, "Endpoint is unavailable", which `opencode run` retried forever with **zero stdout**. Free-model exhaustion therefore does not show on the stream; it looks like a stall (§5.3). |
| OpenCode | `step_finish` line: `part.cost`, `part.tokens {input, output, reasoning, cache{read, write}}` | per-step usage | The `StepFinishPart` schema and the `run` emitter (`step_finish`, `{part}`) are in the installed binary; repo fixtures hold only `{"type":"step_finish"}`. **Not captured live.** |
| OpenCode | `opencode stats` | local consumption totals | Ran it: table only, no JSON flag, no limit or reset. Free-model cost shows $0.00 while space-bunny-free shows 494 messages / 2.8M input / 110.1M cache read, so cost is useless as a budget signal there. |

### 6.3 Smallest honest design (stories 9.7, 9.8c)

1. **Capture before parsing.** Commit real fixtures first. An OpenCode free-model run is allowed.
   Claude fixtures come only from an Owner-approved run or an Owner-supplied transcript.
2. **Observed usage, per run.** Parse `step_finish` / `result` into `run_usage` (runId, provider,
   model, input, output, reasoning, cacheRead, cacheWrite, `costUsd?`). Push a `usage` frame when the
   run ends. `finished` stays byte-for-byte.
3. **Observed limit state, per provider.** The last `rate_limit_event` becomes `provider_state`
   {provider, status, window, `utilization?`, `resetsAt?`, observedAt, source}.
   - Exhaustion is classified only from captured samples. Until one exists the state is `unknown`,
     never 0%: having no quota signal is not the same as 0% used.
   - An OpenCode free-model run that stalls is reported as `stalled`, noting that free-quota exhaustion
     has looked like this (§5.3). The stream cannot tell the two apart, so the daemon never labels it
     `exhausted`.
4. **Operator budget.** Counted by the daemon from its own runs (runs or tokens per day, per
   provider). It is labelled "budget", never "quota".
5. **Launch gate.** The minimal rule: block a launch the quota cannot pay for.
   - **Unattended starts** (triggers, drained queues) are refused when the provider's state is
     exhausted or its budget is spent. That records `refused_launch_gate` and raises an attention item.
   - **Human starts** get a warning and still run.
   - **Deferred:** reroute and adjust rules. They need several providers per daemon (9.0) plus a
     routing policy.
6. **Account bars, behind an Owner decision.** A `QuotaSource` adapter per provider would poll the
   account endpoint.
   - **Claude** is possible today, but the endpoint is undocumented and the adapter reads a credential
     file.
   - **OpenCode Go** is blocked until its source is named.
   - **Failures** show as `unknown` with the error.

### 6.4 Rejected

- Calling undocumented endpoints without an Owner decision.
- Parsing the `opencode stats` table: brittle, and local totals are not limits.
- Estimating "remaining quota" from local counts and calling it quota.

---

## 7. Wire contract impact (Q4)

### 7.1 Rule

**Everything below is additive, so every bump is a minor one.**

- **The version names what the daemon serves.** Each story that adds wire surface bumps the minor in
  the same commit and adds a "What changed in 1.x.0" block (the `CONTRACT.md:12-29` pattern).
- **Numbering.** The first such story ships 1.3.0; later numbers follow merge order.
- **Nothing early.** No frame or field is published before a handler serves it.
- **Spec first.** As with the session `role`, nothing reaches the wire outside this section without a
  spec change.

### 7.2 New frames (request → answer, and pushes)

| Story | Frames |
| --- | --- |
| 9.1 | `agent_save` → `agent`; `agent_list` → `agents`; `agent_delete` → `agents` |
| 9.2 | `ticket_save` / `ticket_get` / `ticket_move` / `ticket_link` / `ticket_unlink` → `ticket`; `ticket_list` / `ticket_reorder` / `ticket_delete` → `tickets`; push `ticket` on every change |
| 9.3 | `ticket_start_next` → `ticket`; `settings_get` / `settings_set` → `settings`; `ticket_move` to `in_progress` now starts a session (§2.5) |
| 9.4 | push `attention`; `attention_list` → `attention_items`; `attention_ack` → `attention`; HTTP `GET /attention` |
| 9.5 | `trigger_save` / `trigger_arm` / `trigger_disable` / `trigger_fire` → `trigger`; `trigger_list` / `trigger_delete` → `triggers`; `trigger_events` → `trigger_events`; push `trigger` |
| 9.6 | HTTP `POST /hooks/{triggerId}` (signature-admitted, §4.6) |
| 9.7 | push `usage`; push `provider_state`; `provider_states` → `provider_states` |

### 7.3 Optional fields on existing payloads

- **`session`:**
  - `model` (9.0);
  - `agentId`, `role`, `ticketId` (9.1);
  - `contextSources` (9.3).

  `role` closes the Phase 8 gap with no UI change: `infantryUi` already reads `role` off the session
  JSON (`Model.kt:249`).
- **`pod_submit` `NodeSpec`:** `agentId`, `provider` and `model` (9.5; resolution as §2.2).
- **`/health`** (counts only, F4, `CONTRACT.md:586`):
  - `providers` (9.0);
  - `attentionOpen` (9.4);
  - `triggersTripped` (9.5);
  - a provider-state summary (9.7).
- **Unchanged:** `ready`, and the four rendered events (`text_delta`, `status`, `error`, `finished`).
  Usage gets its own frame.

### 7.4 Storage

There is one migration per story, following the "NEXT MIGRATION" header convention (`3.sqm:5`): the
first is `4.sqm` (v4 → v5), and later numbers follow merge order.

| Story | Tables |
| --- | --- |
| 9.1 | `agents` |
| 9.2 / 9.3 | `tickets`, `ticket_links`, `settings`, `ticket_runs` |
| 9.4 | `attention` |
| 9.5 | `triggers`, `trigger_events` |
| 9.7 | `run_usage`, `provider_state` |

Sessions stay in their JSON store, so the new optional session fields need no migration.

### 7.5 Client impact

- **Phase 8 UI.** It ignores unknown frames (`Model.kt:246`, `else -> s`) and never refuses a contract
  version (`Model.kt:165`).
- **CLI.** It warns on **any** contract difference, including a newer minor (`cli/CliMain.kt:417`).
  This is cosmetic; see §10.9.
- **Reconnect.** A reconnecting client re-syncs with the list frames plus `attention_list`, so Phase 9
  does not need F2.

---

## 8. Stories (Q6), in order

| # | Story | Needs | Wire | Read |
| --- | --- | --- | --- | --- |
| 9.0 | **Provider registry + per-run model.** `INFANTRY_PROVIDERS` list; `INFANTRY_PROVIDER` stays the default. An unknown provider or model is refused. Resume uses the recorded provider. Tests run two stub providers with different ids. | — | `/health.providers`, `session.model` | §0, §2.2, §2.7 |
| 9.1 | **Agent profiles + session attribution** (`agentId`, `role`, `ticketId`). | 9.0 | agent frames | §2.1, §7 |
| 9.2 | **Backlog core:** tickets, links, columns, positions, derived pending, cycle and won't-fix rules. Until 9.3 lands, `ticket_move` to `in_progress` is refused with "starting arrives in 9.3". | — (can overlap 9.0/9.1; merges serially) | ticket frames | §1, §7 |
| 9.3 | **Ticket start + controlled context:** `handOut`, executor resolution with sources, role capacity queue, fail-closed worktree, prompt composition, `ticket_runs`, `ticket_start_next`, settings. | 9.0, 9.1, 9.2 | §7.2 row 9.3 | §1.4, §2, §3 |
| 9.4 | **Attention + unattended deadline/stall.** Also routes the pod `scheduler stalled` throw and the mail `CeilingReached` into attention (§10.4, §10.5). | merge after 9.0 (both touch `SessionManager`) | attention frames | §5.3, §5.4 |
| 9.5 | **Triggers:** manual, schedule, ship. Actions `start_next` / `prompt` / `pod`. Coalescing, follow-up slot, ceiling, trip/arm, catch-up, dispositions. **Meets the Phase 9 → 10 gate.** | 9.3, 9.4 | trigger frames | §4.1–4.5, §4.7, §5 |
| 9.6 | **Webhook ingress:** v1 signatures, replay window, dedupe, body cap, `AccessPolicy` change (Owner decision). | 9.5 | `POST /hooks/{id}` | §4.6 |
| 9.7 | **Usage + limit state + budget + launch gate**, fixtures first. Account bars only after the Owner decides (§6.3 step 6). | 9.0, 9.4 | `usage`, `provider_state` | §6 |
| 9.8a | **UI: backlog board, profiles,** started/queued/held states. | 9.3 + Phase 8 merged | — | §1, §2 |
| 9.8b | **UI: triggers + attention panel.** | 9.5 + Phase 8 merged | — | §4, §5 |
| 9.8c | **UI: usage and limit bars.** | 9.7 + Phase 8 merged | — | §6 |

**Before any story starts:** add its branch to
`superiorSoftLibrary/infantry/preflight/expected-bases.properties`, or `verifyBase` fails. The file
has no phase8 or phase9 line today.

**Deferred (not Phase 9):**

- cross-provider routing by headroom (reroute and adjust rules);
- the `in_review` column and reviewer flow;
- `quota`-kind triggers;
- webhook vendor presets, filters and restricted runs;
- per-key parallel trigger runs;
- skills and memory context (Phase 10).

---

## 9. Guessed or unverified

1. **Claude in-stream signals.** `rate_limit_event` emission in `-p` stream-json for this account, and
   the usage fields of `result`, are static evidence only.
2. **OpenCode per-step usage.** The `step_finish` usage fields are static evidence only (schema plus
   emitter).
3. **What exhaustion looks like on the stream** is unknown for OpenCode Go and for the Claude
   subscription. For OpenCode free it is a recorded observation that I did not reproduce: a silent
   retry hang (memory `opencode-cli-workers`). There is no classifier until a sample is captured.
4. **The OpenCode Go bars' source** was not found.
5. **The usage dashboard and `/api/oauth/usage`.** That the dashboard reads Claude's
   `/api/oauth/usage` is inferred from the identical labels; its code was not read.
6. **The won't-fix prerequisite rule (§1.3) and `role:` with no free agent (§2.3)** are design
   choices.
7. **R1 and R2** are taken as PM relayed them on the ticket; they were not confirmed with the Owner
   directly.
8. **Defaults are proposals,** not measurements. All of them are mine:
   coalesce window 120 s, ceiling 6/h, deadline 60 min, stall 15 min, retention 500, body 64 KB,
   digest 2 KB.

---

## 10. Wrong or odd in existing code (reported, not fixed)

1. **"Phase 9" in code comments means the protocol freeze (`73173034`), not this Phase 9.** Developers
   grepping for "Phase 9" will land in the wrong place. Locations: `protocol/Protocol.kt:31, 45, 89`,
   `daemon/DaemonMain.kt:108, 142`, `provider/AgentProvider.kt:70`, `provider/ProviderFactory.kt:16`,
   `provider/StubProvider.kt:9`, `runner/ScriptedRunner.kt:10`, `client/ClientMain.kt:231`.
2. **The version rule in `protocol/EventVocabulary.kt:17` contradicts the contract.** The KDoc says
   VERSION is "bumped only for a breaking change to a frame's meaning". `CONTRACT.md:68-71` and the
   additive 1.1.0 bump say otherwise.
3. **README drift.** `README.md:446` cites `CONTRACT.md` §2.5 for the mapping, but the mapping is §2.6
   (`CONTRACT.md:323`). `README.md:466` says a session ends `CANCELLED`, but `SessionState` has no
   CANCELLED; only `NodeState` does.
4. **A stalled scheduler leaves the pod RUNNING forever.** `Orchestrator.schedule` throws "scheduler
   stalled" (`Orchestrator.kt:349`), which only `guarded` catches (`Orchestrator.kt:105-110`). The
   result is a line on stderr and nothing else.
5. **The mail conversation ceiling reaches stdout only** (`DaemonMain.kt:452-457`). It has no frame and
   no durable record.
6. **There are two derivations of a run's outcome:** `DaemonMain.finishedEnvelope` (exit code + kill,
   `DaemonMain.kt:413`) and `RunOutcomes.of` (session state + kill, `RunOutcomes.kt:15`). This spec
   uses `RunOutcomes.of` (§2.6).
7. **Phase 8 is not on `ahq-main`.** `eab9e56` (branch `feature-agenthq-phase8`) is not an ancestor of
   `ahq-main`: `git merge-base --is-ancestor` reports it is not merged. Phase 9 nevertheless counts as
   unblocked because Phase 8 is "done". The UI stories need that merge first.
8. **Headroom routing cannot be built as ticket `ae1cd5f1` asks.** It wants work routed "to whichever
   provider has headroom". A single-provider daemon cannot do that (§2.7), and no OpenCode free-model
   headroom signal exists (§6.2).
9. **The CLI warns on newer minors.** `cli/CliMain.kt:417` warns on any contract mismatch, including a
   newer minor, so every Phase 9 bump will make old CLIs warn.
