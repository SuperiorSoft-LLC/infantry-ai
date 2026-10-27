# AgentHQ — the daemon ⇄ client contract

**Version 1.135.0.** This is the *public* boundary of AgentHQ. Everything below is stable and
versioned: a client written against it remains wire-compatible across provider changes, daemon
restarts and new AgentHQ phases, and you never need to know which agent CLI (OpenCode, Claude Code,
a stub, or one that does not exist yet) is behind the daemon.

> **Working on a ticket in this repo?** Read [`docs/AGENTHQ-EVIDENCE-BAR.md`](../../docs/AGENTHQ-EVIDENCE-BAR.md)
> before you write your first test. It is short, it needs no tools beyond `read`, and a merge will be held
> without it. It covers the RED-before-implementation rule, the mutation check, how to write a test that
> cannot pass vacuously, and the durability rules (commit by explicit path, early).

### HARNESS contract 1.3.0 (harness plugin interface, separate from the wire version)

This is NOT the wire version at the top of this file. `HARNESS_CONTRACT_VERSION` (in `harness/Harness.kt`) versions the
in-process interface a harness plugin implements; the registry loads a plugin only on an exact match. It moved 1.0.0 → 1.1.0
with HARNESS 4/4 (Pi, the second harness). No wire frame changed.

- **Additive events** on `HarnessEvent`: `SessionStarted(sessionId)`, emitted once per run when the CLI announces its session
  (the id a later request passes back as `sessionId` to resume), and `ToolResult(callId, output, isError)`, the outcome of a
  `ToolCall` with the same `callId`. A consumer that does not know them can ignore them.
- **Behaviour change in the Claude harness:** a run whose process exits 0 but never reported a result is now an `Error`
  ("ended without reporting a result: the run was cut short"), not a `Completion`. A truncated stream used to be
  indistinguishable from success. Every run still ends with exactly one `Completion` or `Error`; Pi follows the same rule
  (exit 0 without `agent_settled` is an `Error`, and a failed run that exits 0 is read from the stream, not the exit code).
- **Declared divergence, `STREAM_GRANULARITY`:** how finely text and reasoning are cut is not part of the contract. One
  harness may deliver a block as one `TextDelta`, another token by token. `HarnessParity.canonical` merges adjacent events of
  the same kind and nothing else; it is the only allowance, and any other difference between two harnesses is a defect.
- **Not changed:** `HarnessRequest.project` stays an opaque label that no harness interprets. Daemon dispatch is untouched.
- **1.2.0 (CP-4), additive:** `Harness.commands`, the harness's `/` command catalog (`HarnessCommand`: name, description, an action the
  daemon knows - set model, set effort, native - and the values it accepts). Default empty. The daemon serves it (`command_catalog`)
  and names no vendor; `/compact` is a NATIVE command, handed to the harness, never reimplemented.
- **1.3.0 (CP-4 slice d), additive:** `HarnessRequest.effort` and `HarnessCapability.EFFORT_SELECTION`, so `/effort` sets the reasoning effort of
  a session's next run. A request naming an effort needs the capability, like a model does. Claude maps it to `--effort`, Pi to `--thinking`.
- **Proven for two vendors only** (Claude, Pi); OMP, OpenCode and Ollama have not been run against it.

### What changed in role-first (1.4x) — the daemon half, in one place (ROLE-FIRST S14)

A summary of everything the role-first design ([`docs/AGENTHQ-ROLE-FIRST.md`](../../docs/AGENTHQ-ROLE-FIRST.md)) added to
the wire. It adds nothing new: each line points at the version section that defines it (the per-version sections below
stay normative). The whole series is ADDITIVE: a 1.42.0 client keeps working against a 1.47.0 daemon. HARNESS contract
stays 1.3.0 throughout (no `Harness.models()`; the catalogs are a daemon-side registry, D8). Schema: one migration,
v25 -> v26 (`25.sqm`, `agents.effort`).

| Version | Slice | Frame / field | What it is |
|---|---|---|---|
| **1.43.0** | S1 | `new` frame: `SpawnOptions.role` (optional) | A spawn naming a role attaches the session to it (with or without `agentId`; bound at creation). With no `agentId`, provider / model / effort come from the role binding (spawn > binding > daemon default). Refused: unknown role; a role that differs from the named profile's role. |
| **1.45.0** (no bump) | S4 | `models` rows for harness `pi` | Pi's catalog from `pi --list-models` (bounded child), falling back to `~/.pi/agent/models-store.json`. Rows keep the 1.34.0 shape `{provider, group, label, models, note}`: `provider` = harness id, `group` = upstream vendor, ids are `vendor/model`. A failed listing is one empty row with a `note`. |
| **1.45.0** (no bump) | S5 | `models` rows for a descriptor harness | The descriptor's optional `models` section, exactly one of `{"list":[{provider,id,label?}]}` or `{"modelsCommand":[argv]}` (never a shell; 20 s / 1 MiB bound; one `provider/model` per line). A failure is one noted empty row, never the command's output. See `docs/QUICKSTART.md`. |
| **1.45.0** (no bump) | PICKER-ALL-1 | `models` rows for harness `claude`; noted row for a harness with no catalog | Claude Code gets a built-in static row (`provider`=`claude`, `group`=`claude`, label `Claude`: aliases `opus`, `sonnet`, `haiku`, `opus[1m]`, `sonnet[1m]`, `opusplan`, then pinned full ids). A registered harness with NO source still gets ONE empty row with `note` = `this harness lists no models; type the model id its --model accepts`. A configured provider covering the same id wins (no harness appears twice). |
| **1.46.0** | S2 | `AgentProfile.effort` (optional; in `agent_save`, `agent`, each `agents` row) | Null = inherit. One of `low`, `medium`, `high`, `xhigh`, `max`, else `agent_save refused: unknown effort ...`. Resolution: spawn > profile > binding > daemon default. Schema v26. |
| **1.47.0** | S3 | `agent_swap` (client -> daemon) | `{agentId, provider, model?, effort?, session: "auto"|"keep"|"new"}`, strict keys. Changes only the agent's executor; validated as `agent_save` validates it. Refusal = `error` `agent_swap refused: <reason>`, nothing written. |
| **1.47.0** | S3 | `agent_swapped` (daemon -> every client) | `{agent, from: {provider, model, effort}, session: {outcome, sessionId?}}`. |

Session outcome of `agent_swap` (the agent's current session = its run in flight, else its latest session):

| `session` mode | Agent state | Same harness | Harness changes |
|---|---|---|---|
| `auto` | no session | `none` | `none` |
| `auto` | idle / finished | `kept` (next run uses the new model / effort) | `kept` (HARNESS-CARRY-1: the same session moves to the new harness, its next run carries the conversation; see `session_swap`) |
| `auto` | running | `kept` (the run finishes on the old executor; nothing interrupted) | `none` (the next spawn for the agent resolves to the new harness) |
| `keep` | no session | `none` | REFUSED (checked before the session lookup matters) |
| `keep` | has a session (idle, finished or running) | `kept` | REFUSED (a conversation cannot cross harnesses) |
| `new` | any (a running run is not interrupted) | `new` | `new` |

(The "harness" compared is the current session's harness when there is one, else the agent's resolved provider before the
swap; `AgentSwapping.kt` is the reference.)

Not in the wire (deliberately): the UI's "Harness / Provider / Model" labels are wire `provider` / `group` / `model` (D7,
no rename); per-provider capabilities (S13) and a cross-harness transcript handoff (S12) are not built.

### What changed in 1.78.0 (PROMPT-6) — "Always allow": remembered approval rules

ADDITIVE; a 1.77.0 client keeps working (it never sends `allow_always`, and ignores unknown frames). The `ready` frame
reports `contract=1.78.0`.
- `prompt_response` takes one more tool-approval `decision`, `allow_always` (also the `outcome` of its
  `prompt_resolved`). The call is allowed, and a rule is stored for the asking session's project. A session with no
  project stores nothing (the call is allowed once). `allow_always` does NOT also act as `allow_session`.
- `ApprovalRule` `{ruleId, projectId, tool, prefix?, createdAt}`. For a shell tool (`Bash`, `PowerShell`) `prefix` is
  the first word of the command (`git`, `npm`) and the rule matches only a command with that exact first word and no
  shell control operator (`;`, `&`, `|`, a backtick, `$(`, `<`, `>`, a line break), so `git status && rm -rf ~` still
  prompts. A shell call with a blank command stores no rule. Any other tool's rule has no `prefix` and matches every
  call of that tool.
- Before opening a `tool_approval` card, the daemon checks the asking session's project rules. A match answers allow at
  once: there is no `prompt_request` and no `prompt_resolved`, and the session gets one `stderr` line,
  `[agenthq] auto-allowed by rule <tool> [<prefix>]`. Rules are per project and never match another project's session.
  Only the `ask` mode consults them: a `deny` (or `allowEdits` / `allowAll`) run never relays a call to the prompts.
- Durable in the settings table under `toolApproval.rules`, one JSON object `{projectId: [ApprovalRule...]}`, so it
  outlives a daemon restart. It is not a `settings_get` key; it is read and written only through the frames below.
- `approval_rules` (client -> daemon) `ApprovalRulesQuery` `{projectId?}` (null, blank or an empty payload = every
  project) -> `approval_rules` `ApprovalRulesList` `{projectId?, rules[]}`, oldest first.
- `approval_rule_delete` (client -> daemon) `ApprovalRuleDelete` `{ruleId}` -> `approval_rules` listing the deleted
  rule's project. An unknown id is refused, nothing changes: `error` `approval_rule_delete refused: unknown rule '<id>'`.
### What changed in 1.87.0 (BUG-MCP-RACE-1) — `requestId` correlates a backlog request with its answer

ADDITIVE; a 1.86.0 client keeps working (it never sends `requestId`, so every answer is exactly as before).
The `ready` frame reports `contract=1.87.0`. No schema change.
- **`Envelope.requestId`** `string?`, omitted when null. A client may put any id on a request; the daemon echoes it on
  that request's DIRECT answer or `error`, and never on a push to every client.
- For the backlog requests (`ticket_save`, `ticket_get`, `ticket_list`, `ticket_move`, `ticket_link`, `ticket_unlink`,
  `ticket_reorder`, `ticket_delete`, `ticket_start_next`), a request carrying a `requestId` is ALWAYS answered directly
  with it, even when the change was also pushed. The requester therefore sees the push (no id) and then its answer
  (its id). Why: `ticket` / `tickets` are both the answer and every client's push, so a client matching by type alone
  could take another ticket's push (a started ticket's run, another client's edit) as its own answer.
- The `agenthq` CLI and `mcp serve` send a `requestId` on those requests only to a daemon whose `ready` names
  `contract` >= 1.87.0; against an older daemon (or one naming no contract) they keep matching by type.

### Note (no version bump): the `agentWatchdog` project switch (ticket-4e1d52c6)

Not a wire change. The agent watchdog is a per-project opt-in stored as `Project.experiments["agentWatchdog"] = "on"` (absent = off, the default), set with `project_config.experiments` and read by the daemon at every watchdog tick (a flip needs no restart). While off, the watchdog sends nothing for that project's sessions (no nudge, no PM mail, no attention item) and shows them IDLE. `AGENTHQ_WATCHDOG=off` stays a global hard off. Mail from `system` or a `trigger:` sender is never a pending item.

### What changed in 1.127.0 (TODO-OVERLAY-1, ticket-8bea1a4d) - the agent's own to-do list

Additive; `contract=1.127.0`. `status.todos` (`[{text, status: pending|in_progress|done, activeText?}]`) is set on the status frame of a to-do tool call (claude `TodoWrite`, opencode `todowrite`) and is the FULL list: a client REPLACES its list, an empty list clears it. Only these items are sent, never other tool arguments. Omitted otherwise; an older daemon never sends it. Replay: it rides the sequenced status frame like any other.

### What changed in 1.126.0 (EXPERIMENTS-1, ticket-c62a975a) - experiment switches and A/B session measures

Additive; `contract=1.126.0`. `Project.experiments` (key to `on`/`off`, absent = off) and `project_config.experiments` (merged; `off` removes the key). `session_stats` gains optional `tags`, `firstContextTokens`, `msToFirstEdit`, `toolCallsBeforeFirstEdit`, `toolResults`, `avgToolResultChars`, `maxToolResultChars`, `stepMsP50`, `stepMsP90`, `stepMsMean`; every one is null when the harness record does not say. An older daemon omits all of them.

### What changed in 1.125.0 (MESSAGE-ORIGIN, ticket-eeab5a16) — a turn says who it came from

Additive; `contract=1.125.0`. The `turn` frame gains an optional `origin`, a typed `MessageOrigin`. Absent means the owner, so a
frame from an older daemon, and every owner turn, is byte-for-byte what it was.

- Wire (`Envelope.origin`, omitted when null): `{"type":"owner"}`, `{"type":"agent","sessionId":..,"agentId":..,"role":..}`
  (agentId / role optional), `{"type":"external","senderLabel":..,"channel":..}`, `{"type":"system","kind":"handover"}`
  (`handover`, `slash_command`, `scheduler`, `mail`).
- The daemon sets it where the turn is created (mail: `MailSource`; handover and slash commands: `System`); a client never infers
  it from text. The `[Infantry AI COMMS] ...` header the model reads is only the harness-facing rendering, written in one place
  (`MailSource.render`); nothing downstream parses it.
- Clients render from the origin alone, in one file (`OriginRenderer.kt`, agenthqUiCore): owner as before; agent = that role's
  portrait and role name; external / system = the sender label only. CLI `logs` and the MCP session transcript print
  `[turn from architect (dd062cba)]` from the same `MessageOrigin.label()`.
- Transcripts recorded before 1.125.0 carry only the header text; one isolated client adapter (`LegacyOriginAdapter`, marked for
  removal) maps them. Nothing else matches on turn text.

### What changed in 1.124.0 (USAGE-AUTO, ticket-8d3838fd) — usage refreshes itself

Additive; `contract=1.124.0`. The daemon re-reads every live plan meter on its own and pushes a `usage_report` to every client.
- Setting `usage.refreshMinutes` (int 0..1440, default 5; 0 = off). One refresh at a time; a failed read (network, rate limit, bad response, http error) doubles the wait up to 30 min, a good read resets it. The scheduler wakes at most once a minute. Manual (typed) meters are not refreshed.
- `MeterUsage` gains `updatedAt` (epoch ms of the live read; omitted for manual / unread meters): clients show "updated Nm ago".
- `usage_get` still answers from the daemon's 5-minute read cache; with `data` = `force` it re-reads live now (the manual Refresh button).

### What changed in 1.123.0 (PROGRESS-COUNTDOWN, ticket-564d0e01) — the progress meter always counts down

Additive; `contract=1.123.0`. The meter never shows a bare elapsed timer: a running job shows percent + time left, "over estimate by N", or (no number, no history) its label + "last output Ns ago".
- `SessionProgress` gains `labelStartedAt` (when the current label / step began), `estimateMs` + `estimateSource` (`worker` | `learned`; omitted = no estimate yet), `lastOutputAt` (newest report or `--watch` heartbeat). `ProgressReport` gains `estimateMs` and the op `output` (heartbeat: sets `lastOutputAt` only).
- The daemon learns: every label that ends normally (a status label change, a finished step, `done`; a failure is not recorded; under 1 s is ignored) records its duration per project in `progress-estimates.json` (last 20 per label, median = the default estimate for the next run of that label). The worker's own `estimateMs` (marker `##infantry eta <seconds> [label]`) wins over it.
- Rule: a counter's percent wins (time left = pace so far, only after 2 s); else a time estimate (percent = elapsed/estimate capped 99, "~2m left", then "over estimate by 1m"); else nothing ("no estimate yet" + last output). `agenthq progress --watch` sends an `output` heartbeat every 5 s of output that changed nothing.

### What changed in 1.122.0 (WEB-1) — the browser client, served by the daemon

ADDITIVE; a 1.121.0 client keeps working. The `ready` frame reports `contract=1.122.0`.
- **Bundle at `/`.** The daemon serves the browser (Kotlin/WASM) client from its own port: `GET /` is `index.html`, and every
  file of the bundle is one exact `GET` route, listed once at startup (no path resolution, so `..`, encoded slashes and
  symlinks out of the folder are never served). The bundle is found at `AGENTHQ_WEB_DIR`, else `<AGENTHQ_APP_HOME>/web`
  (headless archive, Docker), else the desktop app's `resources/web`. Without a bundle `GET /` is 404 with a one-line note.
  Bundle files need no token (they hold no data); a tokenless loopback daemon still checks Host, a token daemon asks for an
  allowlisted Host, and an Origin, when sent, must be same-origin.
- **Browser admission on `/ws` only.** A request carrying an Origin is still refused everywhere except `/ws` (and the bundle).
  On `/ws` a browser is admitted only when ALL hold: the daemon has a bearer token (a tokenless loopback daemon refuses every
  browser); the Host names an allowlisted host (`localhost`, `127.0.0.1`, `::1`, the bind host or, bound to the wildcard,
  the machine's own interface addresses, plus `AGENTHQ_WEB_HOSTS`, comma-separated); the Origin is exactly
  `http(s)://<that Host>`; and the bearer matches (constant-time, as before).
- **Subprotocol bearer.** A browser WebSocket cannot set `Authorization`, so on `/ws` the token may also arrive as the
  `Sec-WebSocket-Protocol` value `agenthq.bearer.<base64url(token), no padding>`, offered together with `agenthq`. The
  daemon answers `Sec-WebSocket-Protocol: agenthq`; it never echoes the bearer protocol. The token never goes in a URL.

### What changed in 1.121.0 (USAGE-PACE S2+S3, ticket-2d954bda) — manual usage, allowance override, pacing rule; ticket starts are paced

ADDITIVE (`usage_set`) and BEHAVIOURAL (the start gate); a 1.120.0 client keeps working. The `ready` frame reports
`contract=1.121.0`. (S2 and S3 ship together under 1.121.0 because ROGUE-1 took 1.120.0.)
- A ticket hand-out whose run would spend a meter with `canStart` false (see `usage_report`) is refused when an agent
  started it (`ticket_move` to in_progress with a starter session, `agenthq_ticket_start`), or the daemon did
  (`ticket_start_next`, a trigger, a queue drain): `start.ok` false, `start.reason` `start_refused`, `start.detail`
  `usage pacing: <meter> at <pct> ..., allowance <n> (rule|override), running <n>; resets <instant>. ...`.
- The owner's own move (no starter session) still starts, with that text in `start.detail` as a warning.
- Unknown usage never refuses (fail open); `usage override <meter> none` lifts the limit until it lapses.
- **`usage_set`** (client -> daemon, `data` = a `UsageSet` `{meter?, manualPercent?, manualResetsAt?, clearManual?,
  overrideWorkers?, overrideUntil?, clearOverride?, rule?}`) is answered by `usage_report`. A manual percent (0..100)
  feeds a meter with no live read (`source` = `manual`) until `manualResetsAt`, else 5 h. An override (`overrideUntil`
  in the future, `overrideWorkers` 0..50 or null = no limit) replaces the rule's allowance until it lapses; a
  `MeterUsage` shows it as `override` next to `ruleAllowance`. `rule` is a whole `PacingRule`
  `{enabled, tiers: [{below?, workers}]}` (`below` increasing in (0, 100], only the last tier without it). A bad
  request is an `error` `usage_set refused: ...` and changes nothing.
- Three new settings keys hold them: `usage.pacing`, `usage.manual`, `usage.overrides` (also writable through `settings_set`).
- `agenthq usage set|clear|override|rule`; the MCP `agenthq_usage` stays read-only.

### What changed in 1.120.0 (ROGUE-1) — the rogue background process detector

ADDITIVE; a 1.118.0 client keeps working. The `ready` frame reports `contract=1.120.0`.
- **Process ledger.** Every 10 s the daemon walks the OS process tree under each session's harness process (kept or running) and
  under every process it already recorded, and records each process by pid AND OS start instant with its owner session
  (`process-ledger.json` in the state dir, stamped with the state dir's instance id). A process whose parent exited (a Gradle daemon
  after `gradlew` ends) stays attributed. Only a ledger process can ever be reaped; the daemon, its ancestors and anything run by the
  app's own runtime are never recorded.
- **Rogue** = a ledger process whose owner session was removed or ended (EXITED / FAILED / ORPHANED), or whose owner is idle (no
  turn, no bg job, no output for `processes.idleMinutes`) while it holds at least `processes.heavyMb`. A live session's own harness
  process is never rogue by idleness and is refused by `reap` (kill the session instead).
- **`processes`** request `ProcessesRequest {op: list|reap|reap_rogue, pid?}` → `processes` answer
  `ProcessesAnswer {report: ProcessReport, reaped: [TrackedProcess], refused?}`. `ProcessReport {freeMb?, totalMb?, floorMb,
  underPressure, autoReap, tracked: [TrackedProcess], unattributed: [TrackedProcess]}`; `TrackedProcess {pid, command, sessionId?,
  startedAt?, memoryMb?, rogue, reason?}`. `unattributed` lists heavy runtimes (java, node, docker, WSL...) the ledger cannot prove are
  ours: shown, never reaped. A reap re-checks the start instant before a tree kill. A bad op is `error` `processes refused: ...`.
- **`process_pressure`** push (a `ProcessReport`) to every client when free RAM drops under `processes.floorMb`, at most once per 10 min.
- **Settings** `processes.autoReap` (bool, default false: reap rogue processes every minute), `processes.idleMinutes` (1..1440,
  default 30), `processes.heavyMb` (default 500), `processes.floorMb` (default 4096).
- CLI `agenthq processes [--all]`, `agenthq processes reap <pid> | --all`; MCP `agenthq_processes` / `agenthq_processes_reap`.
### What changed in 1.119.0 (USAGE-PACE S1, ticket-2d954bda) — read plan usage and the pacing allowance

ADDITIVE and read-only; a 1.118.0 client keeps working. The `ready` frame reports `contract=1.119.0`.
- **`usage_get`** (client -> daemon, empty `data`) is answered by **`usage_report`**, a `UsageReport`
  `{meters: [MeterUsage], rule: PacingRule, observedAt}`. A meter is the plan a run spends, not its harness: `claude`
  (the Claude subscription, also spent by pi / opencode running an `anthropic/...` model), `opencode-go`, else the
  harness id. Each `MeterUsage` carries `percent` (the highest window), `window`, `resetsAt`, `paceDelta` (points vs an
  even burn), `source` (`live` | `none`), `windows`, `reason`, `allowance` (what the rule allows; null = no limit),
  `running` (live in_progress ticket workers on the meter) and `canStart`. Unknown usage FAILS OPEN (no allowance, a
  `warning`). The rule defaults to `<60%: 3, <80%: 2, else 1`; nothing is enforced in 1.119.0 (1.121.0 enforces it).
- `agenthq usage [show]` and the MCP tool `agenthq_usage` (read-only) print the same report.

### What changed in 1.118.0 (BASICS-KILL-1) — sessions can always be ended and removed

ADDITIVE; a 1.117.0 client keeps working. The `ready` frame reports `contract=1.118.0`.
- **`Project.autoRemoveDoneWorkers`** (default false; an older daemon omits it): when true, a ticket of the project moved to `done` ends its
  worker session (a forced kill of its process tree) and removes it from the session list, once the worker's current turn is over (a worker
  usually moves its own ticket and then mails its starter in the same turn). Stored with the project (migration 47).
- **`project_config`** gains `autoRemoveDoneWorkers?` (absent = unchanged). A request with neither it nor `laneCaps` is still a read. CLI:
  `agenthq project config set <projectId> auto-remove-done-workers on|off`; MCP: `agenthq_project_config_set` `autoRemoveDoneWorkers` (`true` /
  `false`; `role` + `cap` are now optional when it is given).
- **Stale BUSY is removable.** A `SessionRecord` served with `turnActive=false` and `state=BUSY` (an at-rest session whose leftover background
  process, e.g. a Gradle daemon, makes it read busy) decodes in the CLI / MCP as its true state, IDLE: `agenthq_session_remove` /
  `session rm` remove it without `force`. The daemon's `session_delete` also ends, instead of refusing, a record that claims a run while the
  daemon has no run for it and its pid is gone. No frame changes.
- **One session per ticket / ticket-working agent.** A start or resume that would run next to another session with a turn in flight on the
  same ticket, or of the same agent when either works a ticket, is refused (`... refused: session <id> already runs ticket|agent ...; kill it
  first`); mail to such a session stays queued until the other one rests. Ticketless sessions of one agent (pod nodes) are not restricted.
- **A ticket worker keeps its harness.** Mail addressed to the agent of a ticket session resumes that session on the ticket's provider / model;
  it no longer opens a fresh session on the agent profile's harness (BUG-S3's swap rule now applies to ticketless sessions only).
- **UI Remove** kills with `force` first when the session reads running, so a stuck turn or leftover background processes do not block it.
### What changed in 1.117.0 (PROGRESS-1, ticket-1371d44b) — an agent reports how far along its long job is

ADDITIVE; a 1.115.0 client keeps working (the new key is ignored on decode). The `ready` frame reports `contract=1.117.0`.
(1.116.0 is claimed by a parallel ticket.)

- **`SessionRecord.progress`** (omitted when none): the agent's OWN measurement of its long job; since 1.123.0 also a countdown (see that section).

| Key | Type | Meaning |
|---|---|---|
| `mode` | `percent` \| `steps` \| `bytes` \| `count` \| `indeterminate` | how the agent measures |
| `state` | `running` \| `done` \| `failed` | |
| `percent` | 0-100, omitted for `indeterminate` | derived by the daemon for `steps` / `bytes` / `count` (omitted when the total is unknown) |
| `label` | string ≤ 120 | what the job is ("gate", "download model") |
| `steps`, `stepsTotal`, `stepsDone`, `step` | `steps` only | planned names (empty for a bare count), total, complete, and the name of the step now running |
| `done`, `total` | `bytes` / `count` | units done of total |
| `bytesPerSec` | `bytes` only | smoothed speed from successive reports |
| `startedAt`, `updatedAt` | epoch ms | the job's start; the newest report (a client shows "no update for N min" against it) |

- **`progress` frame** (client -> daemon, `sessionId` = the reporting session, `data` = a `ProgressReport`
  `{op, percent?, label?, steps?, count?, step?, done?, total?}`), `op` one of `percent`, `plan` (steps or count), `step` (the named step
  completed; no name = the current one; an unplanned name is inserted where the job stands, already complete), `bytes`, `count`, `status`
  (indeterminate), `done`, `fail`, `clear`, `get`. Answered by a `progress` frame carrying the session's progress (or `null`); a bad report is
  `error` `progress refused: ...` and changes nothing. The change reaches every client as the usual `session` push (and is in the `sessions`
  list, so a late-joining client sees the current value). No separate push frame.
- **Cleared** when the session ends (`EXITED` / `ORPHANED`), when a turn ends with no report made during it (unless an `agenthq bg run` job is
  still registered), and on a daemon start. A finished job (`done` / `failed`) stays visible until then.
- **CLI** (`agenthq progress ...`, `agenthq run --label x -- <cmd>`, `agenthq progress --watch`) and **MCP** (`agenthq_progress`) build the
  same report. HARMLESS outside Infantry AI: with no `AGENTHQ_SESSION_ID` or no reachable daemon every progress command exits 0 and prints
  nothing; `run` / `--watch` still pass the output through unchanged and keep the command's exit code. See `docs/PROGRESS.md` for the marker
  grammar (`##infantry progress 40 compile`), the readers and the coalescing rules.
- **Opt-in estimate (S5, ticket-680e0b39; additive keys, same version):** a `status` report may carry `estimate: true`
  (`agenthq progress status "x" --estimate` / `agenthq progress estimate "x"`, `agenthq run --estimate`, `##infantry status --estimate x` /
  `##infantry estimate x`, MCP `estimate`; a label is required, `progress refused` otherwise). The progress then
  carries `estimated: true` and, once a run of the same label in the same project finished `done`, `estimateMs` (the median of the last 5
  such durations, kept by the daemon in `progress-estimates.json` beside its state). Both keys are omitted otherwise; any other report drops
  them. A client draws an estimated bar HATCHED with "~12 min left (estimated)", never as a measurement. Never the default.

### What changed in 1.115.0 (ASSISTANT-1) — the global Assistant

ADDITIVE; a 1.114.0 client keeps working. The `ready` frame reports `contract=1.115.0`.
- **`assistant` frame** (client -> daemon, empty `data`): answered with the Assistant's `session` frame. The first request creates the one
  Assistant of the installation (role `assistant`, no project, its own working directory `<stateDir>/assistant`, never the user's home); every
  later request, from any client, answers the same session. A failure is an `error` (`assistant refused: ...` / `assistant failed: ...`).
- **`SessionRecord.global`** (omitted when false): true for that session only. It belongs to no project, so a project switch never hides it.
- **Role fixed, model free**: `session_swap` changes the Assistant's provider / model (persisted on the session) and refuses a role change
  (`the Assistant's role is fixed`).
- **Scope**: role `assistant` is privileged like `pm` / `owner` for MCP / CLI project scope (reads across every project). Kill: with no project of
  its own it may kill any session.
- **Standing instructions** are generated at spawn: the role persona, how Infantry AI is organised, and the daemon's real `--help` text.

### What changed in 1.114.0 (KILL-SCOPE-1) — kill by role and project; one-call kill + remove

ADDITIVE; a 1.113.0 client keeps working. The `ready` frame reports `contract=1.114.0`.
- **`SessionRecord.spawnerSessionId`** (omitted when none): the session that spawned this one, recorded by the daemon and persisted.
- **Kill scope** (`agenthq_kill`, `agenthq kill`, `agenthq session rm --force`, `agenthq_session_remove force=true`): the owner (no session id)
  and `--allow-kill-any` may kill any session; a `pm` any session in ITS OWN project (another project is refused); every other role only the
  sessions it spawned (matched by `spawnerSessionId`, so it survives an app / MCP-server restart).
- **`agenthq_session_remove`** gains `force` (default false): kill a running session, then remove it. CLI: `agenthq session rm <id|prefix> --force`;
  new `agenthq kill <id|prefix> [--force]`. Refusals name the exact next call.
- **`session_restart` with force** now kills the running turn's process tree at once before restarting.

### What changed in 1.108.0 (ROLE-EDIT-1) — a role has a description, and a custom role can be deleted

ADDITIVE; a 1.107.0 client keeps working. The `ready` frame reports `contract=1.108.0`.
- **`Role` and `role_save` gain `description`** (one line, <= 200 chars; `role_save`: absent = keep, `""` = clear). Stored as a
  `description:` front-matter key of the role file. It is for the role editor and is never sent to a session; the persona
  stays the text every session of the role receives, and an edit reaches new sessions only.
- **`role_delete`** (data = role id) removes the role's custom file. A custom override of a bundled role is reset: every client
  gets the bundled definition as `role`. A pure custom role is removed: every client gets `role_removed` (data = role id).
  Refused (`error`, nothing touched) for a bundled role with no override, an unknown id, or while a live session has the role.

### What changed in 1.107.0 (USER-PORTRAIT) — the owner's portrait, stored by the daemon

ADDITIVE; no new frame. One new `settings_get` / `settings_set` key, `user.portrait`: a base64 string of a PNG or WebP at most
256 KB (clients downscale to ~256 px before saving), the picture every client shows for the human instead of the "Y" placeholder.
`settings_set` of `""` clears it. Absent from `settings` frames while unset: clients then show their bundled default. A value that
is not base64, not PNG/WebP, or over 256 KB is refused with an `error` frame.

### What changed in 1.106.0 (SCOPE-1) — MCP tools scoped to the caller's project

ADDITIVE; no frame changes (the version moves 1.104.0 → 1.107.0; 1.105.0 is the triggers rework on another branch). The `agenthq mcp serve` tools
`agenthq_board`, `agenthq_pod_state`, `agenthq_get_session`, `agenthq_ticket_get`, `agenthq_ticket_update`, `agenthq_ticket_move`,
`agenthq_ticket_delete`, `agenthq_ticket_start` and `agenthq_ticket_create` (explicit `projectId`) now default to the CALLER'S OWN project.

- Who the caller is comes from the daemon's session record for `AGENTHQ_SESSION_ID` (role, project), never from a request argument.
- `projectId='all'` (no filter) and another project's id are for `pm` / `owner` callers only; any other role is refused, naming both projects.
- A ticket or session of another project is refused for other roles, writes included.
- Answers carry `projectId` and `scope` (`project:<id>` or `all`); with `all`, board lines are prefixed `[projectId]`.
- A call with no session id (owner / CLI) is unchanged. Inbox/notes scoping is not part of 1.107.0.


### What changed in 1.105.0 (TRIGGER-NUDGE) — a recurring prompt to a role, as mail

ADDITIVE; a 1.104.0 client keeps working (it ignores the new keys). The `ready` frame reports `contract=1.105.0`. Stored in
`triggers.project_id` / `last_fired_at` / `next_fire_at` / `pending_mail_id` (`45.sqm`, schema v46); an older row reads back
with no project, never fired, and its next slot recomputed from its interval. No new frame.

- **`TriggerSave.projectId`** `string?`: the project a `prompt` action mails into; refused on any other action
  (`trigger_save refused: projectId is only valid on action 'prompt'`).
- **`TriggerView.projectId`** `string?`, **`lastFiredAt`** `long?` (the last ACCEPTED event: started or folded; null =
  never), **`nextFireAt`** `long?` (a schedule's next slot; null = not timed).
- **The `prompt` action is now MAIL.** Each fire mails the prompt (subject `Trigger: <name>`, from `trigger:<id>`, its own
  thread `trigger:<id>:<ms>`) to the target's session in the trigger's project: for `role:<role>`, the project's
  session of that role (the one in flight first, else the newest); for a profile id, that agent's session. COMMS
  queues it while the session is mid-turn and delivers it when the session comes to rest. Only when there is NO such
  session is one started with the prompt as its first turn (in the trigger's project). The run is over once the mail is
  queued or the session started: **a busy target never trips the trigger**.
- **At most one pending per trigger.** While the trigger's last prompt mail is still `QUEUED`, a new event is recorded
  as `coalesced_pending` and sends nothing. The pending mail id is persisted, so this holds across a restart.
- **New dispositions** (`TriggerEventView.disposition`): `mailed` (the prompt mail was queued), `coalesced_pending`
  (see above), `deferred_busy` (no session to mail and the role's agents are all at capacity elsewhere; nothing was sent,
  the next slot tries again; never a trip).
- **Restart.** `nextFireAt` is persisted. A slot that fell due while the daemon was down fires ONCE on the first pump
  after boot (several missed slots fold into that one), then the schedule resumes one interval later. An edit that
  keeps a schedule's interval keeps its slot; a new interval restarts it from the save.
- **`trigger_arm` also enables.** On a `disabled` trigger it sets `enabled=true` and `armed` (the UI's enable toggle);
  on `tripped` it re-arms as before; on `armed` / `running` it is refused (`… is armed, not tripped or disabled`).
- **The hourly ceiling counts session STARTS only.** A prompt fire that was mailed (or `deferred_busy`) started no
  session and does not count; one that had to start a session does. Mail is bounded by one pending per trigger instead,
  so any interval, a one-minute schedule included, runs all hour without tripping.
- Unchanged: `start_next` triggers, the `manual` / `ship` / `webhook` kinds, the coalescing window and the one-minute
  interval floor (`intervalMs >= 60000`).
### What changed in 1.104.0 (VOICE-1) — spoken attention alerts

ADDITIVE; a 1.103.0 client ignores the push. The `ready` frame reports `contract=1.104.0`. No schema change: a spoken alert is
ephemeral (not stored, not replayed to a client that connects later).

- `notify` (client → daemon): `{say, level?, projectId?, ticketId?}`. `say` is the short text an agent wrote (whitespace and
  control characters collapsed, capped at 200 characters); `level` is `info` (default), `needs-owner` or `urgent`. A blank
  `say` or an unknown `level` is answered by `error` (`notify refused: …`); otherwise there is no direct reply.
- `spoken` (daemon → every client, the requester included): one `SpokenNotice`
  `{id, at, say, level, projectId?, projectName?, ticketId?}`. Clients speak `projectName` first, then `say`, and nothing else.
- CLI: `agenthq notify --say "<short text>" [--level info|needs-owner|urgent]` (project from `--project` / `AGENTHQ_PROJECT_ID`).
### What changed in 1.128.0 (ROUTING-1) — per-project task routing rules pick a ticket's executor

ADDITIVE; a 1.118.0 client keeps working (it never sends `routingRules` and ignores the new keys). The `ready` frame reports
`contract=1.128.0`. Stored in `projects.routing_rules` (`49.sqm`, schema v50); an older store reads back every project with no rules.

- **`Project.routingRules`** `[RoutingRule]`, in order; empty (default) = no rules, today's resolution unchanged.
- **`RoutingRule`** `{name, weights?: [int], ticketTypes?: [string], role?, provider, model, effort?, setRole?}`.
  MATCH: `weights` (1..5; empty = any weight, unweighted included), `ticketTypes` (the ticket types; empty = any), `role` (the
  ticket's role; absent = any). A rule matches when every present match field does. RESULT: `provider` + `model` (both
  required), `effort` (optional), `setRole`: the role a ticket with NO role of its own is started as (never replaces one).
- **`project_config.routingRules`** REPLACES the whole list (`[]` clears it; absent = unchanged). Refused (`project_config
  refused: rule '<name>': ...`, nothing written) when: a name is blank, longer than 60 or repeated; more than 32 rules; a
  weight is outside 1..5; a type or role is unknown; the provider does not resolve, the model is not one its harness lists
  or the effort is unknown (the check a profile save uses); a model naming Opus is not exactly provider `pi` with model
  `anthropic/claude-opus-5-5`; the LAST rule is not a default row (no match field); or a default row is not last.
- **Precedence at `ticket_start`**: a ticket that pins its own provider or model never goes through the rules. Otherwise, in
  a project with rules, the FIRST matching rule decides provider / model / effort; it beats the profile / role binding and
  `tickets.weightExecutors` (which then no longer applies in that project). A project with no rules is unchanged.
- **No fallback**: the rule's executor is checked again at start (provider resolves, model listed, Opus line). A refusal
  answers `start.reason = "start_refused"` with `detail` `rule '<name>': <provider>/<model> refused: <why>; ...`, leaves the
  ticket in `todo`, and raises ONE attention item (`launch_blocked`, outcome `routing_refused`) per ticket and reason. No
  other rule, the default row or another harness is ever tried.
- **Why**: `effectiveExecutor.provider|model.source` is `rule` and the new `effectiveExecutor.rule` names the rule; a start's
  `detail` reads `rule '<name>': <provider>/<model>`.
- CLI: `project routing <projectId> [--file <rules.json> | --clear]`; `ticket show` prints `EFFECTIVE: ... via rule '<name>'`
  and flags an unweighted ticket. MCP: `agenthq_project_config_set` takes `routingRules` (a JSON array as text),
  `agenthq_project_config_get` returns them; `agenthq_ticket_get` returns `effectiveRule`.

### What changed in 1.103.0 (TICKET-WEIGHT) — ticket weights pick the executor

ADDITIVE; a 1.102.0 client keeps working (it ignores the new keys). The `ready` frame reports `contract=1.103.0`. Stored in
`tickets.weight` (`44.sqm`, schema v45); an older store reads back every ticket unweighted (`weight` absent).

- **`Ticket.weight`** `int?`: 1 (light) .. 5 (heavy), or absent = unweighted (current behaviour).
- **`TicketSave.weight`** `int?`: 1..5 sets, `0` clears, absent or null keeps it. Anything else is refused.
- **Setting `tickets.weightExecutors`**: JSON object keyed `"1"`..`"5"`, each `{provider, model}` (either may be null, not both).
  Defaults: 1-2 `claude/claude-haiku-5-5`, 3 `claude/claude-sonnet-5-5`, 4-5 `claude/claude-opus-5-5`. Keys left out keep the default.
- **Precedence at `ticket_start`**: the mapping applies ONLY when the ticket has a weight, pins no provider and no model, and its
  profile / role binding pins neither. An explicit role, provider or model always wins.
- **Why**: `effectiveExecutor.provider|model.source` is `weight` when the mapping chose it, and a start's `detail` reads
  `weight <n>: <provider>/<model> from tickets.weightExecutors`.
- CLI: `ticket create|update --weight 1-5` (`0` clears); `ticket show` prints `WEIGHT:` and `EFFECTIVE:`. MCP: `agenthq_ticket_create` /
  `agenthq_ticket_update` take `weight`; `agenthq_ticket_get` returns `weight` and the effective provider / model with their sources.

### What changed in 1.100.0 (HARNESS-INSTALL-1) — a built-in installer for the harness CLIs (claude, opencode, pi)

ADDITIVE; a 1.99.0 client keeps working and a 1.99.0 daemon simply has no installer. The daemon used to assume the harness
CLIs were installed. Now a client (the CLI, an MCP agent, a remote client) can detect and install them on THAT daemon's
machine, local or remote, through two frames:

- **`harness_install_status`** `{checkLatest?}` (empty data = defaults) → answered by `harness_install_status`, a
  `HarnessInstallReport {harnesses:[HarnessInstallStatus], prefixDir, npmPath?, guidance?}`. Per harness: `state`
  (`installed` | `missing` | `outdated` | `unknown` = found but `--version` unreadable), `installedVersion`, `path`,
  `managed` (it lives in the app's own prefix), `wanted` (the pin, else null), `latest` (only when `checkLatest`: it
  runs `npm view`, so it is slower). `npmPath` null means Node/npm was not found and `guidance` says how to get it.
- **`harness_install`** `{ids?, update?}` → answered by `harness_install_result`, a `HarnessInstallResult {outcomes, log, report}`.
  `ids` empty = every missing harness (plus every outdated one with `update`); naming an id acts on exactly that one.
  Each outcome is `installed` | `updated` | `skipped` | `failed` (+ `version`, `message`). A failed install is an outcome,
  not an `error`; an unknown id is an `error` `harness_install refused: ...`. It can take minutes: clients wait for it
  (the CLI and MCP tool allow 15 minutes). Runs are serialised on the daemon.

Both answers go only to the requesting client. CLI: `agenthq harness-status [--check]`, `agenthq harness-install [id...]
[--update]` (exit 1 when any harness failed). MCP: `agenthq_harness_status`, `agenthq_harness_install`.

**How it installs.** `npm install -g --prefix <AGENTHQ_HOME>/harness-tools <package>@<version|latest>` with packages
`@anthropic-ai/claude-code`, `opencode-ai`, `@earendil-works/pi-coding-agent`. The prefix is the app's own, so no admin
rights are needed and no global npm prefix, user file or existing install is touched. The launch resolver lists that
prefix FIRST among a tool's default locations (a Settings override or `AGENTHQ_<TOOL>_EXE` still beats it). Installs are
idempotent: an installed CLI is skipped without running npm unless `update` is set. Node/npm is required only for these
CLIs, never to build or run AgentHQ; npm is found via `AGENTHQ_NPM_EXE`, next to node's install locations, then PATH.

**Pinned or latest?** Latest by default: these CLIs follow fast-moving provider APIs and a stale pin stops working.
A pin is opt-in per harness with the environment variable `AGENTHQ_<ID>_VERSION` on the daemon (e.g.
`AGENTHQ_CLAUDE_VERSION=2.0.0`); a pinned CLI at any other version is `outdated`, and `update` installs exactly the pin.

**Credentials.** The installer never handles any. Sign-in stays with each CLI (`claude` first-run login, `opencode auth
login`, `pi`, or the provider's API-key environment variable, set by the user); AgentHQ stores no key and passes none to npm.

### What changed in 1.101.0 (ISO-1) — cross-project mail is refused unless overridden

ADDITIVE; a 1.100.0 client keeps working. The `ready` frame reports `contract=1.101.0`.
- SESSION-ID-1 (MCP output only, no wire change, so no bump): the session objects of `agenthq_list_sessions` and `agenthq_get_session` also carry `sessionId`, the same value as `id` (the key every tool takes as input). `id` stays; nothing is renamed or removed. The `sessions` frame still names it `id`.
- ISO-2 (no wire change, so no bump): the MCP `agenthq_list_sessions` summary adds `projectId` per session and takes an optional
  `projectId` filter. DEFAULT: a session caller (AGENTHQ_SESSION_ID) sees only its OWN project; `projectId:"all"` lists every project; owner/user callers see all. The CLI `pods` session lines print `project=<id>`. The `sessions` frame already carried `projectId`.
- `mail_send` (`MailSendRequest`) takes an optional `allowCrossProject` (default false; CLI `agenthq send --cross-project`,
  MCP `agenthq_message {allowCrossProject:true}`). When the sender is a SESSION bound to a project and the recipient (a
  session id or an agent id) is bound to a DIFFERENT one, the daemon answers `mail_refused` with a reason naming both
  projects, and nothing is stored. With the flag the message is sent and delivered.
- Exempt without the flag: senders that are not sessions (`user`, `owner`, `system`), and a worker mailing the session that
  started its ticket (or the reverse), as before. An override message that is still queued when the daemon restarts is
  undeliverable (visible; resend it).
- A role-name recipient resolves among the sessions of the SENDER's project only. None, or more than one, is an error
  (`no <role> agent in this project` / `multiple <role> agents in this project: ...`); it never falls back to another
  project, and `allowCrossProject` does not widen it (address the other project's agent by id).

### What changed in 1.102.0 (SECRETS-1) — the daemon-owned keystore

ADDITIVE; a 1.101.0 client keeps working (it never sends the new frames). The `ready` frame reports `contract=1.102.0`.

- The daemon keeps secrets (the update token first) AES-256-GCM sealed in `<stateDir>/keystore.json`. The master key is
  protected, in order, by: a mounted/env key (`AGENTHQ_KEYSTORE_KEY` or `AGENTHQ_KEYSTORE_KEY_FILE`, for headless, remote and
  Docker daemons); the OS facility (Windows DPAPI, macOS Keychain, Linux Secret Service); else `<stateDir>/keystore.key`
  with owner-only permissions (the documented fallback: encrypted, but only as safe as the state dir). Never plaintext.
- `secret_set` `{name, value}`, `secret_clear` `{name}`, `secret_import` `{name}` (copy the older environment variable,
  e.g. `AGENTHQ_UPDATE_TOKEN`, into the keystore; no file is ever deleted) and `secret_status` `{}` are each answered by one
  `secret_state` `{protection: external-key|os|key-file, secrets:[{name, label, set, tail, importable, overriddenByEnv}]}`,
  and a change is pushed to every client. `tail` is `••••` plus the last 4 characters of a value of 16+ characters, else `••••`.
- **No frame ever returns a stored value**, and a refusal (`secret_set refused: ...`) never echoes the frame's data. The value
  is not written to logs, transcripts, mail or crash reports.
- Known names: `update-token` (env `AGENTHQ_UPDATE_TOKEN` still overrides it). Other names are refused.
- Not covered: the Android release signing keystore (owner-held, CI secrets).

### What changed in 1.99.0 (PIPE-2) — the pipeline is pushed to clients and drawn in the Git tab

ADDITIVE; a 1.98.0 client keeps working (it ignores the push). The `ready` frame reports `contract=1.99.0`.
(1.98.0, NOTES-1, added memory note scopes; see the `memory_*` rows.) The PIPE-1 frames (`pipeline_config`, `pipeline_show`,
`pipeline_record`, `pipeline_check`, answered by `pipeline_state`) are unchanged. What is new:
- After every `pipeline_config`, `pipeline_record` or `pipeline_check` that succeeds, the daemon PUSHES the resulting
  `pipeline_state` (`{projectId, configured, config?, commits[], history[], checks[]}`) to EVERY connected client, not
  only the requester; the requester also gets its own answer. `pipeline_show` is answered to the asker only. A refused
  request pushes nothing. A client replaces only the slot of the `projectId` it names.
- `configured:false` is the default for a project with no pipeline; a client shows an empty state with a way to configure.
- The desktop and mobile UI (Git tab, "PIPELINE") shows per commit its stage chip, evidence text, who moved it and when,
  each running check with the age of its last output, and a small modal to set or remove the stages (`pipeline_config`).
  It asks `pipeline_show` only from contract 1.99.0.

### What changed in 1.97.0 (ASK-1) — owner questions: a NON-BLOCKING prompt with options and a recommended one

ADDITIVE; a 1.96.0 client keeps working (it never sends the new frames and ignores the `question` / `questions` pushes).
The `ready` frame reports `contract=1.97.0`. Schema: `41.sqm` adds the `owner_questions` table.

An agent asks the owner to choose WITHOUT waiting: `question_ask` (CLI `agenthq ask --recommend <label> --reason ..`, MCP
`agenthq_ask`) stores the question and is answered at once with `question_asked {questionId}`. The owner's pick reaches
the asking agent as COMMS mail from `owner` in thread `question:<id>` (subject `Answer to question <id>: <label>`),
addressed to the asker's agent id (its session id when it has no agent), so it resumes on its own. PROMPT-5's blocking
`prompt_ask` and the harness prompts (PROMPT-1..6) are unchanged.

`OwnerQuestion`:

| Key | Type | Meaning |
|---|---|---|
| `id` | string | the question id |
| `projectId`, `sessionId`, `agentId`, `role` | string (only `sessionId` always set) | who asked, frozen at ask time |
| `question` | string | at most 2000 characters |
| `options` | `[{label, description}]` | 2..8, unique labels; `description` = the one-line consequence |
| `recommended` | string | the label of exactly one option |
| `reason` | string | why it is recommended, at most 200 characters |
| `allowOther` | boolean | the owner may type `otherText` |
| `defaultAfterMin` | int, optional | 1..1440; null = wait for the owner (the default) |
| `state` | `open` \| `answered` \| `auto` \| `cancelled` | `auto` = the daemon picked `recommended` after `defaultAfterMin`; still overridable |
| `answer`, `otherText` | string, optional | the chosen label / the owner's own text |
| `resolvedBy` | `owner` \| `auto` \| `asker`, optional | who ended it |
| `createdAt`, `resolvedAt` | epoch ms | |
| `overridden` | boolean | the owner replaced an auto-pick (a second mail, marked `OVERRIDE`) |

Rules: questions are stored and survive a daemon restart; rows are never deleted (resolved ones are the history). The
daemon sweeps `defaultAfterMin` every 15 s (and once at start). Per asker (agent id, else session id) at most
`questions.maxOpenPerAgent` (default 3) open questions and `questions.maxPerHour` (default 6) asks in any rolling hour;
both are settings (1..100). Only a frame with no envelope `sessionId` (the owner's client) may `question_answer`. An
owner `question_cancel` mails the asker "cancelled"; the asker's own cancel mails nobody.

**Ticket exit behavior.** The setting `tickets.exitBehavior` (`"leave"` default, or `"done"`; set with `settings_set`)
decides what a cleanly finished worker run does to its in_progress ticket. `leave`: the ticket stays in_progress, its
`lastRun` shows `SUCCEEDED`, and the PM or another agent moves it with `ticket move`. `done`: the ticket moves to done.
A failed, killed or orphaned run never moves a ticket in either mode, and the starter's finish mail is sent in both.

### What changed in 1.96.0 (ticket-b5aa01bc) — a typed `run_in_flight` refusal for a `prompt` to a busy session

ADDITIVE; a 1.95.0 client keeps working (the refusal is still a session-less `error`; only its text changed). The
`ready` frame reports `contract=1.96.0`.

A `prompt` naming a session that already has a run in flight (another client's turn, or mail delivered the moment the
session came to rest) is refused with:

```json
{ "type": "error", "data": "run_in_flight <sessionId>: session <sessionId> already has a run in flight", "requestId": "queue-1" }
```

- `data` starts with `run_in_flight <sessionId>: `; the reason after the colon is for people, not parsing.
- `requestId` is the prompt's own, echoed when the prompt carried one (the 1.87.0 rule), so a client knows WHICH
  message was refused. Nothing of the prompt ran or reached the stream (no `turn` frame): it can be sent again once the
  run in flight ends.
- The shipped client sends a message it queued for a busy agent with a `requestId` (to a daemon whose `ready` names
  `contract` >= 1.96.0); on this refusal the message goes back to the front of that agent's queue, still visibly
  queued, and is sent at the next turn end. It is not echoed as sent and raises no notice.

### What changed in 1.95.0 (1c24fdc4) — `status.runActivity`: one harness-neutral "what is the run doing"

ADDITIVE; a 1.94.0 client keeps working (`runActivity` is a new optional key on `status`, ignored on decode). The
`ready` frame reports `contract=1.95.0`.

A `status` frame now carries `runActivity` whenever the run's phase or label changes (never on every text delta):

| Key | Type | Meaning |
|---|---|---|
| `state` | `idle` \| `thinking` \| `tool_running` \| `compacting` \| `waiting_for_input` \| `finished` \| `error` | what the run is doing |
| `label` | string, only while `tool_running` | the newest tool call in flight, as `tool` or `tool: activity` (`Bash: ls -la`) |
| `startedAt` | epoch ms | when `state` began |
| `turnStartedAt` | epoch ms | when the turn opened |
| `lastEventAt` | epoch ms | the newest run event the daemon had seen when the frame was made |

How the daemon derives it (`protocol/RunActivity.kt`, the same for every harness):

- an accepted `turn`, or the session going `STARTING`/`BUSY` with no turn open → `thinking`;
- a tool call starts (`EventNormalizer.Line.Tool`) → `tool_running`, label = that call;
- every started call has ended (`Line.ToolEnd`), or the model speaks again (text/reasoning) → `thinking`;
- the harness reports it is compacting its context (claude `system` `status: compacting`, pi `compaction_start`) → `compacting`; its end (claude `compact_boundary` / a non-compacting `status`, pi `compaction_end`) → `thinking`. Additive, no version bump: a client that does not know `compacting` still reads the session as busy;
- the session goes `WAITING_INPUT` → `waiting_for_input`;
- the turn ends → `finished` (clean, or killed by request) or `error` (failed); the session leaving flight → `idle`.

`thinking` comes from the TURN, never from a reasoning event: a harness that streams no thinking still reads `thinking`
between its tool calls. A UI shows its working indicator while the SESSION is in flight (the `session` frame's state,
the same as its "working" badge) and uses `runActivity` only for the label; it names no harness.

**Adding a harness:** map its tool START to `Line.Tool` and its tool END to `Line.ToolEnd` in `EventNormalizer`;
nothing else. Today's mapping:

| Harness | tool start | tool end |
|---|---|---|
| claude | `assistant` content block `tool_use` | `user` content block `tool_result` (one end per block) |
| opencode | `tool` / `tool_use` part | the same part when `part.state.status` is `completed` or `error` (`opencode run` reports only these, so start and end arrive together) |
| pi | `tool_execution_start` | `tool_execution_end` |

### What changed in 1.94.0 (RUN-CMD-1) — `command_save` / `command_delete`: a client edits the saved Run commands

ADDITIVE; a 1.93.0 client keeps working (it never sends the new frames, and the two new `CommandSpec` keys are ignored
on decode). The `ready` frame reports `contract=1.94.0`. No schema change: the commands stay in
`<AGENTHQ_HOME>/commands.json`, which the daemon now writes as well as reads.

| Frame | Payload | Direction | Meaning |
|---|---|---|---|
| `command_save` | `{id, name?, argv: [..], cwd?: relative \| null, graceMillis?}` | client → daemon | Upsert by `id`. `name` defaults to the id, `cwd` to the repo root, `graceMillis` to 5000. Decoded strictly (an unknown key is refused). |
| `command_delete` | `{id}` | client → daemon | Remove that command. An unknown id is refused. |

- **The answer is a `commands` frame pushed to EVERY client** (the requester included), carrying the whole updated
  list. A refusal is an `error` `command_save refused: <reason>` / `command_delete refused: <reason>`; nothing is
  written then. A reason names the id, never an argv element.
- **Validation is the load's, per entry**: a non-blank id; a non-empty `argv` of non-blank strings, and it must be a
  JSON array (a shell string is refused; the argv goes to the process as a list, never through a shell); `cwd` relative
  and canonicalised inside `AGENTHQ_REPO` (an absolute path, any `..` segment, or a symlink out is refused);
  `0 < graceMillis <= 600000`. With no `AGENTHQ_REPO` every save is refused.
- **Never under a run**: a save or delete of a command whose run is in flight is refused (`... is running; stop it
  first`); other commands stay editable. The check and `run_start` share one lock, so no run of it starts halfway.
- **Write**: the whole file is re-validated, written to `commands.json.tmp` beside it, then moved over it atomically, and
  the daemon's list is reloaded from it; the next `run_start` uses the new argv. A `commands.json` the daemon refused
  at load (a bad hand edit) is never overwritten: every save and delete is refused until it is fixed or removed.
- **`CommandSpec` gains `argv` and `graceMillis`** (owner decision, RUN-CMD-1): the editor shows what it edits. The
  bearer token is already the whole credential (section 5), so a client that can run a command may read it. The 1.33.0
  rule "no argv on the wire" is lifted for `commands` only; `run`, `run_output` and `run_finished` still carry none,
  and the daemon never writes an argv to its log. Chosen over a separate `command_get` frame as the smaller change: one
  list already has every row the editor needs.

### What changed in 1.93.0 (ROLE-ICON-1) — a role carries an icon key

ADDITIVE; a 1.92.0 client keeps working. The `ready` frame reports `contract=1.93.0`.
- **`Role` gains `icon`** (string, default `""`): the key of the role's icon in the client's own bundled icon set
  (`[a-z0-9][a-z0-9-]{0,31}`, e.g. `shield`). `""` means none was chosen, and each client paints its default for the
  role's kind. The daemon stores the key and never interprets it, so a client that does not know a key paints that
  same default.
- **`RoleSave` gains an optional `icon`**: absent or `null` keeps the role's current icon (an older client editing a
  title never wipes it), `""` clears it, anything else must be an icon key or the save is refused
  (`role_save refused: icon '…' is not an icon key`). The key is written as an `icon:` front-matter line of the role
  file, so it survives a restart and reaches every client through `role` / `roles`. The bundled manifest may carry
  `icon` too; none does today.
- **Older daemons**: `RoleSave` is decoded strictly, so a daemon below 1.93.0 REFUSES a save that carries `icon`.
  The shipped client sends `icon` only to a daemon whose `ready` names `contract` >= 1.93.0.

### What changed in 1.92.0 (STATS-1) — `session_stats`: one session's statistics, from its harness's own record

ADDITIVE; a 1.91.0 client keeps working (it never sends `session_stats_get`). The `ready` frame reports
`contract=1.92.0`. No schema change. Data only; the panel that paints it is a later slice.

| Frame | Payload | Direction | Meaning |
|---|---|---|---|
| `session_stats_get` | `{sessionId}` | client → daemon | Ask for one AgentHQ session's statistics. Blank or unknown id: `error` `session_stats_get refused: ...`. |
| `session_stats` | `SessionStats` | daemon → requesting client | The answer, computed on demand (no push, no polling). |

- **Source.** Read from the session's harness's OWN local record, picked by the session's `provider`:
  `claude` = `~/.claude/projects/*/<providerSessionId>.jsonl`; `pi` = `~/.pi/agent/sessions/*/*_<providerSessionId>.jsonl`;
  `opencode` = `~/.local/share/opencode/opencode.db` (sqlite, opened read-only with a busy timeout). Read only; no
  credential file is opened; nothing leaves the machine. Files are streamed line by line; a session's resolved path is
  cached by the daemon.
- **`SessionStats`** `{sessionId, harness, providerSessionId?, status?, startedAt?, endedAt?, durationMs?, userPrompts?,
  assistantTurns?, toolUses?, models?, lastContextTokens?, modelContextLimit?, billedTokens?, series?, topSources?,
  unattributedToolTokens?}`. `series` = `[{at?, contextTokens, cumulativeBilledTokens}]`, one per model request;
  `topSources` = `[{tool, calls, tokensAdded?}]`, largest first.
- **Null means "this harness's record does not say"**, never zero and never estimated. Any other harness, a session
  whose CLI has not revealed its id yet, or a missing / locked / unreadable record gives a `session_stats` whose harness
  fields are all null (it is not an error). `status` is the daemon's own session state, lowercase.
- **Definitions.** A request's context = input + cache read + cache write; billed = context + output (reasoning is not
  added). `modelContextLimit` comes only from a local catalog that states it (Pi `~/.pi/agent/models-store.json`,
  OpenCode `~/.cache/opencode/models.json`); Claude Code's is always null. `tokensAdded` = context growth after a step
  where that tool was the ONLY tool run; a multi-tool step's growth goes to `unattributedToolTokens`, never split.

### What changed in 1.91.0 (ORIGIN-SCRUB-1b) — the ticket type set renamed, retired types still accepted

The ticket types are AgentHQ's own. All nine changed, one for one: `feature` -> `enhancement`, `bug` -> `defect`,
`refactor` -> `restructure`, `chore` -> `upkeep`, `docs` -> `documentation`, `style` -> `cosmetic`, `perf` ->
`performance`, `test` -> `testing`, `security` -> `hardening`. The `ready` frame reports `contract=1.91.0`. No schema change.
- **The daemon publishes only the new types** (`ticket`, `tickets`, and the refusal listing the legal types).
- **A retired type is still accepted** on `ticket_save` (case-insensitive, like any type) and stored under its new name.
- **Stored tickets migrate when the database opens**: every `ticket_type` holding a retired type is rewritten, in one
  transaction, idempotently (a read-only probe runs first, so a clean file takes no write lock).
- **Older daemons**: a client that meets a daemon below 1.91.0 receives the retired types; the UI shows each under its
  new name. A client that SENDS a new type to such a daemon is refused (`is not a ticket type`); no shipped client sends
  a type on its own, so only a hand-written `ticket_save` can meet this.

### What changed in 1.90.0 (ORIGIN-SCRUB-1a) — five role ids renamed, retired ids still accepted

The role catalog's ids are AgentHQ's own. Five changed: `git` -> `version-control`, `i18n` -> `localization`,
`brainstormer` -> `ideation`, `bot` -> `automation`, `chief-of-staff` -> `assistant`. The other eleven and `custom` are
unchanged. The `ready` frame reports `contract=1.90.0`. No schema change. A 1.89.0 client keeps working: role ids are
opaque strings it reads from `role_list`; an older UI only paints the custom portrait for `version-control` and `automation`.
- **The daemon publishes only the new ids** (`role_list`, profiles, sessions, tickets' `role:<id>`, bindings, traits).
- **A retired id is still accepted on every request** that names a role (`agent_save`, a spawn's `role`, `session_swap`,
  a ticket's `agentId: "role:<id>"`, role bindings and traits, a trait check's `reportTo`) and is stored under its new id.
- **Stored rows migrate when the database opens**: every role-bearing column holding a retired id is rewritten to the
  new one, in one transaction, idempotently. A binding or trait row under both ids keeps the new id's row.
- **Older daemons**: a client that meets a daemon below 1.90.0 receives the retired ids; the UI maps both spellings to
  the same portrait, and a UI avatar override stored under either spelling resolves for both.

### What changed in 1.89.0 (ATTACH-INLINE-1) — `image_upload` purpose `file`: any file for the chat

ADDITIVE; a 1.88.0 client keeps working (it never sends purpose `file`, and `chat` / `ticket` are unchanged). The `ready`
frame reports `contract=1.89.0`. No schema change.
- **`ImageUpload.purpose`** gains `file`: a chat attachment of ANY type. No magic-byte check; at most 20 MB decoded
  (`chat` / `ticket` keep 10 MB); stored at `<Project.directory>/.agenthq/uploads/file/<sha256>.<ext>`, `<ext>` being
  the `fileName`'s extension when it is 1-10 letters or digits (lowercased), else `bin`. The answer is the usual `image`
  `ImageStored`; its `mimeType` echoes the declared one, or `application/octet-stream` when none was given.
- The chat retention sweep (`images.chatImageRetentionDays`) covers `uploads/file/` exactly as `uploads/chat/`.
- An agent is handed such a file as a `[file: <absolute path>]` reference in its prompt (images stay `[image: ...]`).
### What changed in 1.88.0 (UX-CHANGES-1) — the project's worktrees, read-only

ADDITIVE; a 1.86.0 client keeps working (it never sends the new frames or `ProjectGitDiffRequest.worktree`, and a
`project_git_patch` it asked for is byte-identical). The `ready` frame reports `contract=1.88.0`. No schema change.
Everything below is READ-ONLY: nothing is fetched, pulled, pushed, committed or checked out, and no index lock is taken.
The honesty rule of `project_git` holds: `available = false` with a `reason` is unknown, never clean.
- **`project_git_worktrees`** (client -> daemon) `ProjectGitWorktreesRequest` `{projectId, sessionId?}` ->
  `project_git_worktree_list` `ProjectGitWorktreeList` `{projectId, available, reason, worktrees[], selected?}`.
  `worktrees` is `git worktree list` of the project's repository, the main worktree first; each `GitWorktree` is
  `{path, branch?, head?, main, project, session, detached, bare, prunable}`. `project` marks the worktree holding the
  project's directory, `session` the one holding the directory `sessionId` runs in ("holding" = the deepest worktree
  that contains it, since a linked worktree often sits inside the main one). `selected` is the session's worktree
  when there is one, else the project's. An unknown or absent `sessionId` marks no worktree `session`; it is not
  refused.
- **`project_git_worktree_status`** (client -> daemon) `ProjectGitWorktreeStatusRequest`
  `{projectId, path, historyLimit = 30}` -> `project_git_worktree` `ProjectGitWorktreeStatus`
  `{projectId, path, available, reason, branch?, head?, base, ahead?, behind?, baseReason, uncommitted, changes[],
  changesTruncated, commits[], commitsTruncated}`. `path` must be one of the `GitWorktree.path`s the project's
  repository lists, or the frame is refused: `error` `project_git_worktree_status refused: not a worktree of this
  project's repository: '<path>'`. `base` is resolved as the 1.65.0 default base (the branch's recorded base, then
  `feature-agenthq-mvp`, origin/HEAD, `init.defaultBranch`, `main`, `master`, never the branch itself); `ahead` /
  `behind` count `base...HEAD` and are null with a `baseReason` when there is no base (detached HEAD, no commit, no
  base branch). `uncommitted` counts distinct paths with staged, unstaged or untracked changes, measured before the
  `changes` cap. `commits` are HEAD's newest `historyLimit` (1..200) commits, the same `GitCommit` as
  `project_git_history`.
- **`ProjectGitDiffRequest.worktree`** `string?`: read the diff in that worktree (a `GitWorktree.path`) instead of the
  project's directory; the same refusal applies to a path the repository does not list. **`ProjectGitPatch.worktree`**
  echoes it, and is absent from the frame when the request named none.

### What changed in 1.86.0 (SWAP-AGENT-1) — `session_swap` swaps the role in place

ADDITIVE; a 1.85.0 client keeps working (it never sends `SessionSwap.role` and ignores the new `SessionSwapped` keys).
The `ready` frame reports `contract=1.86.0`. No schema change.
- **`SessionSwap.role`** `string?`: absent or null keeps the session's role; blank means no role; otherwise a role id
  the daemon knows. The session keeps its id and transcript; its frozen role instance is replaced with the new role's,
  the provider CLI conversation is not resumed (`providerSessionId` cleared), and the next turn is a first turn under
  the new role that carries the earlier turns (HARNESS-CARRY-1). Nothing in the prompt says the agent was switched.
  Combines with `provider` / `model` in the same frame. Refused, changing nothing (neither role nor harness), when the
  role id is malformed or unknown, or when the session is live and the role would change:
  `error` `session_swap refused: ...` naming the role.
- **`SessionSwapped.role`** / **`SessionSwapped.fromRole`** `string?`: the role the session runs as after the swap,
  and the one it ran as before (equal when the role did not change).

### What changed in 1.85.0 (IMG-3) — tickets carry image attachments

ADDITIVE; a 1.83.0 client keeps working (it ignores the new `Ticket.images` key and never sends `TicketSave.images`).
The `ready` frame reports `contract=1.85.0`. Stored in `tickets.images` (`40.sqm`, schema v41); an older store reads
back every ticket with `images: []`.
- **`Ticket.images`** `string[]`: the ticket's images, in attach order, each the `relPath` an `image_upload` with
  purpose `ticket` answered (`.agenthq/uploads/ticket/<sha256>.<ext>`, relative to the ticket's `Project.directory`).
- **`TicketSave.images`** `string[]?`: absent or null keeps the stored list, `[]` clears it. Each entry is a relPath or
  an absolute path naming an EXISTING file directly under the ticket's project's `.agenthq/uploads/ticket/`; it is
  stored as its relPath, duplicates dropped. Anything else (a chat upload, a path outside uploads, a `..` escape, a
  missing file, a ticket with no project directory) refuses the whole save and changes nothing:
  `error` `ticket_save refused: image '<path>' is not under the project's .agenthq/uploads/ticket/ ...`.
- **Lifetime**: an image lives as long as a ticket references it. `ticket_delete` (UI, CLI and
  `agenthq_ticket_delete` alike) and a `ticket_save` that drops an image delete its file, unless another remaining
  ticket still references the same file (uploads are deduped by sha256). Best effort, after the commit.
- **Worker brief**: a started ticket's first prompt lists each image, right after the ticket core, as
  `[image: <absolute path>]` under the heading `Attached images (read each file to see it):` (context source `images`).
- MCP: `agenthq_ticket_create` and `agenthq_ticket_update` take an `images` array of paths (update replaces the list);
  `agenthq_ticket_get` and both answers list `images`.

### What changed in 1.83.0 (IMG-1) — `image_upload`: the daemon stores an image and answers its path

ADDITIVE; a 1.82.0 client keeps working (it never sends `image_upload`). The `ready` frame reports `contract=1.83.0`.
An agent is handed an image as a plain file PATH, so it works for every harness (no native image blocks).
- **`image_upload`** (client → daemon) `ImageUpload` `{projectId, purpose, fileName, mimeType, base64}`, answered (to the
  uploading connection only) by **`image`** `ImageStored` `{path, relPath, sha256, bytes, mimeType}`:
  `path` is absolute on the daemon's machine, `relPath` is `.agenthq/uploads/<purpose>/<sha256>.<ext>` (`/`-separated, relative
  to `Project.directory`), `sha256` is lowercase hex of the stored bytes, `mimeType` is what the bytes are.
- **`purpose`** is REQUIRED: `chat` (an image pasted into agent chat) or `ticket` (an image attached to a ticket). Any
  other value, or no `purpose` key, is refused. Each purpose has its own folder; dedupe is per purpose, so the same
  bytes uploaded as `chat` and as `ticket` are two files.
- **Storage**: `<Project.directory>/.agenthq/uploads/<purpose>/<sha256>.<ext>` (`png`, `jpg`, `gif`, `webp`). The
  first accepted upload creates `.agenthq/uploads/` with ONE `.gitignore` of `*` (covering both purpose folders), so
  uploads are never committed. The same bytes and purpose again answer the same path; nothing is rewritten.
  `fileName` is informational only. IMG-1 deletes nothing (cleanup is IMG-3 / IMG-5).
- **Checks**, all before anything touches the disk (a refusal writes nothing, not even the folder): png, jpeg, gif or
  webp by their MAGIC BYTES; a non-blank `mimeType` must name one of the four AND agree with the bytes (blank = take
  the bytes' type); at most 10 MB (10485760 bytes) decoded; valid base64; the project exists and has a directory that
  exists. Refusal: `error` `image_upload refused: <why>`.
- CLI: `agenthq image upload <projectId> <file> [--purpose chat|ticket]` (default `ticket`) prints the stored absolute
  path (exit 1 with the daemon's refusal; exit 2 for an unknown `--purpose`, refused before connecting).
### What changed in 1.84.0 (UX-HARNESS-2) — `session_swap`: switch a session that has no agent to another harness

ADDITIVE; a 1.82.0 client keeps working (it never sends `session_swap` and ignores `session_swapped`). The `ready`
frame reports `contract=1.84.0`. No schema change.
- `session_swap` (client → daemon) `SessionSwap` `{sessionId, provider, model?, effort?}`, decoded strictly. For a
  session with NO `agentId` (a quick chat, a pod session): the SAME session (same id, project, working directory,
  history) is restarted on `provider`; a blank `model` / `effort` = that harness's default. Nothing is saved as an
  agent. Across harnesses the provider session id is dropped and the session becomes never-run, so its next turn is
  a first run on the new CLI. HARNESS-CARRY-1 (no wire change): that first run is seeded with the conversation so
  far (the daemon's own transcript: prompts and the visible reply text, tool use summarised), through the target
  harness's `ConversationCarry` (default: a replayed history block opening the run, never shown as a `turn`; over
  48,000 characters the newest exchanges are kept and older ones summarised to what was asked). The agent is not told
  it was switched. The same harness only changes model / effort. The kept process of the old harness is stopped.
- Answered by `session_swapped` pushed to EVERY client: `SessionSwapped` `{sessionId, provider, model?, defaultModel?,
  from{provider, model, effort}, newConversation}`, plus the session's own `session` frame. `newConversation` is
  always `false` since HARNESS-CARRY-1 (kept for older clients).
- A refusal is an `error` `session_swap refused: <reason>` and nothing changes: unknown session, a session bound to an
  agent (use `agent_swap`), a turn in flight, an unconfigured provider, a bad model / effort.

### What changed in 1.82.0 (BASE-1c) — `epic_base`: an epic's own base branch, overriding the project's

ADDITIVE; a 1.81.0 client keeps working (it never sends `epic_base` and ignores the new `Epic.baseBranch` key). The
`ready` frame reports `contract=1.82.0`.
- **`Epic.baseBranch`** (frames `epic`, `epics`): the branch a ticket start for a ticket filed in this epic cuts its
  `ticket/` branch from; `""` = none set. Persisted (schema v40, migration `39.sqm`; existing epics get `""`).
  `epic_save` never sets it.
- **`epic_base`** (client → daemon) `EpicBaseRequest` `{epicId, branch?, clear?}`, answered by `epic` (the whole epic).
  `branch` sets the setting, `clear: true` removes it, neither is a read; both together are refused.
- **Precedence** when a ticket start cuts its branch: the ticket's epic's `baseBranch` > its project's `baseBranch` >
  the daemon's `AGENTHQ_BASE_BRANCH`; with none of them a fresh cut is refused (BUG-BASE-087, below). A cut chosen by
  the epic's setting has the note `cut from <ref> <sha8> (epic <epicId>)`.
- **Set-time check**: exactly `project_base`'s (same resolver, same repository, against the epic's project), plus an
  unknown epic. Nothing is written on a refusal (`error` `epic_base refused: <why>`).
- **Started tickets keep their recorded base**; the setting affects new cuts only.
- CLI: `agenthq epic base <epicId> [<branch>|--clear]`. MCP (`agenthq mcp serve`): `agenthq_epic_base`
  `{epicId, branch?, clear?}`.

### What changed in 1.81.0 (BASE-1a) — `project_base`: the branch a project's ticket starts cut from

ADDITIVE; a 1.80.0 client keeps working (it never sends `project_base` and ignores the new `Project.baseBranch` key). The
`ready` frame reports `contract=1.81.0`.
- **`Project.baseBranch`** (frames `project`, `projects`): the branch a ticket start in this project cuts its `ticket/`
  branch from; `""` = none set. Persisted (schema v39, migration `38.sqm`; existing projects get `""`). `project_save`
  never sets it.
- **`project_base`** (client → daemon) `ProjectBaseRequest` `{projectId, branch?, clear?}`, answered by `project` (the
  whole project). `branch` sets the setting, `clear: true` removes it, neither is a read; both together are refused.
- **Precedence** when a ticket start cuts its branch: the project's `baseBranch` > the daemon's `AGENTHQ_BASE_BRANCH`.
  The start note reads `cut from <ref> <sha8> (<setting>)` and `Ticket.base` records the commit.
- **BUG-BASE-087 (no contract bump)**: the daemon no longer falls back to `HEAD` of the main checkout. A fresh cut
  with no epic, project or `AGENTHQ_BASE_BRANCH` base is refused (`start.ok=false`, reason `no base branch configured
  for project <id>; set one with ...`); a ticket that already holds its branch reuses it. The note's `(<setting>)`
  names what chose the base: `project base`, `AGENTHQ_BASE_BRANCH` or `epic <epicId>`.
- **Set-time check**, nothing written on a refusal (`error` `project_base refused: <why>`): an unknown project; a blank
  name, one starting with `-`, or one with whitespace or a control character; no repository to cut in; a project whose
  `directory` is not in the repository ticket starts cut in (compared by `git rev-parse --git-common-dir`); a branch
  that does not resolve to a commit there (`base branch '<b>' does not resolve to a commit in <repo>`). It is resolved
  with the SAME resolver a cut runs.
- **Where the cut happens is unchanged**: the daemon's repository (`AGENTHQ_REPO`, else the checkout its sessions run
  in), main checkout root even from a linked worktree. A project's `directory` does not choose the repository.
- **Started tickets keep their recorded base**; the setting affects new cuts only.
- The branch list for a picker is the existing `project_git_branches` (1.65.0), rooted at the project's `directory`.
- CLI: `agenthq project base <projectId> [<branch>|--clear]` and `agenthq project branches <projectId>` (prints `repo
  <directory>`, then one branch per line, `*` = checked out).

### What changed in 1.80.0 (UX-WRITE-1) — `status.write`: what a file-writing tool call wrote

ADDITIVE, no new frame; a 1.79.0 client keeps working (it ignores the unknown key). The `ready` frame reports
`contract=1.80.0`.
- A `status` frame for a FILE-WRITING tool call gains `write` `FileWrite` `{path, content?, edits?[{oldText, newText}],
  patch?, truncated?}`. Exactly one of `content` (the whole file a `Write` wrote), `edits` (the replacements of an `Edit`
  / `MultiEdit`) or `patch` (the patch text of opencode's `patch` / `apply_patch`) is set. `path` is the file written; for
  a patch, the first file it names (`*** Update File: x`, `*** Add File: x`, `+++ b/x`), else `patch`.
- Which calls carry it, by tool name (case-insensitive): `write`, `edit`, `multiedit`, `patch`, `apply_patch` -- claude's
  `Write` / `Edit` / `MultiEdit`, opencode's `write` / `edit` / `patch`. An opencode call whose part errored carries none
  (it wrote nothing). Every other status frame, and every other tool, is byte-for-byte what 1.79.0 published.
- Each text is capped at 256,000 characters; `truncated` is true when one was cut.
- Only the agent's OWN output reaches the wire here: what it is writing. What a run READS is never sent, and `activity`
  still never carries file contents.
- A client shows it as a change box in the chat, in the turn where the call happened (the AgentHQ UI does).
- Pi's tool calls are not normalised onto `status` at all yet, so a Pi write carries no `write`.

### What changed in 1.79.0 (PROMPT-5) — `prompt_ask`: any harness asks the owner through the `agenthq` CLI

ADDITIVE; a 1.77.0 client keeps working (it never sends `prompt_ask` and never receives `prompt_answer`). The `ready`
frame reported `contract=1.79.0`. 1.78.0 is PROMPT-6's (allow_always).
- `prompt_ask` (client -> daemon) `PromptAsk` `{question, options[{label, description}], multiSelect?, allowOther?,
  timeoutSec?}`. The envelope `sessionId` is the ASKING session and must be a session this daemon knows. The daemon
  opens an ordinary 1.77.0 `question` prompt for it: the `prompt_request` card goes to every client, a
  `prompt_response` answers it, and `prompt_resolved` closes it. `timeoutSec` (1..86400) replaces `prompts.timeoutSec`
  for this one prompt. No direct reply until the prompt resolves.
- `prompt_answer` (daemon -> the asking connection ONLY; envelope `sessionId` = the asker) `PromptAnswer` `{answered,
  answers[], otherText?}`, once the prompt resolves. `answered` false (timeout, session end, daemon stop) carries no
  answers and a null `otherText`.
- Refused, nothing opened: `error` `prompt_ask refused: <reason>`: no `sessionId`, an unknown session, a blank or
  over-long (> 2000 chars) question, no options, a blank or repeated label, a `timeoutSec` outside 1..86400.
- Closing the asking connection withdraws its open asks: the card closes with `resolvedBy` = `cancelled`. A client
  never re-sends a `prompt_ask` after a reconnect.
- The `agenthq` CLI: `agenthq ask --question "..." --option "label|description" [--option ...] [--multi] [--other]
  [--timeout <sec>]` asks for `AGENTHQ_SESSION_ID`'s session, blocks, and prints `{"answers":[...],"otherText":...}`
  on stdout. Exit 0 answered, 3 no answer / timeout, 2 usage error (no `AGENTHQ_SESSION_ID` included) or refused, 1
  the connection dropped. Every ticket start's Team block carries one line telling the agent so. It works in every
  Tool approval mode, since every harness allows the `agenthq` CLI (BUG-PERM-5).

### What changed in 1.77.0 (PROMPT-1) — prompts: tool approval and questions

ADDITIVE; a 1.76.0 client keeps working (it never sees a prompt unless it is opened, and ignores unknown frames). The
`ready` frame reports `contract=1.77.0`. This is the daemon core only: no harness relays a prompt yet (PROMPT-3/4 do),
and no UI shows one yet (PROMPT-2).
- Tool approval gains the value `ask` (`choices` = `deny`, `allowEdits`, `allowAll`, `ask`). It is NOT the default; it
  resolves in the same order as the others (session, setting, `AGENTHQ_TOOL_APPROVAL`, `deny`). A harness that cannot
  prompt runs `ask` exactly as `deny`, and its denial notice reads `... (Tool approval = ask) (this harness can't
  prompt). Change it in Settings > Tool approval.`
- `prompt_request` (daemon -> every client; the envelope `sessionId` is the asking session) `PromptRequest`
  `{promptId, sessionId, kind, createdAt, tool?, input?, question?, options[{label, description}], multiSelect,
  allowOther}`. `kind` `tool_approval` carries `tool` and `input` (a one-line summary, at most 200 characters); `kind`
  `question` carries `question`, `options`, `multiSelect`, `allowOther`. Every prompt still open is also sent to a
  client right after its connect burst (`ready`, `sessions`, `worktrees`), so a reconnecting client shows the card.
- `prompt_response` (client -> daemon) `PromptResponse` `{promptId, decision?, answers[], otherText?}`. A tool approval
  takes `decision` = `allow_once` | `allow_session` | `deny`; a question takes `answers` (option labels, at most one
  unless `multiSelect`) and/or `otherText` (only when `allowOther`). There is no direct reply: the answer is the
  `prompt_resolved` push. Refused, nothing changes: `error` `prompt_response refused: unknown prompt '<id>'`,
  `... prompt '<id>' is already resolved`, or a reason naming the field that does not fit.
- `prompt_resolved` (daemon -> every client) `PromptResolved` `{promptId, resolvedBy, outcome}`: the card closes on every
  client. `resolvedBy` = `client:<n>` (the answering connection), `timeout`, `session_end`, `daemon_stop` or
  `cancelled` (the asking run went away). `outcome` = the decision for a tool approval, `answered` / `no_answer` for a
  question.
- A prompt unanswered after the setting `prompts.timeoutSec` (int 1..86400, default 600; through `settings_get` /
  `settings_set`) resolves as `deny` / no answer. A session whose turn ends, or that reaches `exited` / `orphaned` /
  `failed`, resolves its open prompts the same way. `allow_session` lets that tool run for the rest of the session with
  no further prompt; the memory is dropped when the session ends.
- Internal (not wire): harness adapters call `PromptAsker.requestApproval(session, tool, input): Decision` and
  `askQuestion(session, spec): Answer` on the daemon's `PendingPrompts`; both suspend until resolved and never throw for
  a timeout or a session end.

### What changed in 1.76.0 (BUG-PERM-1) — Tool approval

ADDITIVE; a 1.75.0 client keeps working (it never asks, and its spawns get the global value). The daemon's `ready` frame
now reports `contract=1.76.0`. No harness can relay an approval prompt yet (Claude runs in `-p` mode; OpenCode serve
rejects every `permission.asked`), so a tool outside what the session allows is DENIED. "Tool approval" decides how much
is allowed, for every harness:
- Values: `deny` (today's behaviour: only what the CLI's own configuration allows), `allowEdits` (file edits run without
  approval), `allowAll` (every tool runs).
- New request `tool_approval` (no data) -> `tool_approval` `ToolApprovalInfo` `{mode?, effective, source, choices[]}`:
  `mode` is the stored global value (null = none), `effective` what a run with no session override gets now, `source`
  `setting` | `env` | `default`, `choices` = `deny`, `allowEdits`, `allowAll`.
- New request `tool_approval_set {mode?}` stores the global value; null or blank CLEARS it. Answered by `tool_approval`.
  An unknown value is refused, nothing stored: `error` `tool_approval_set refused: unknown tool approval '<m>' (known: ...)`.
- `new` (`SpawnOptions`) gains `toolApproval`: that session's own value over the global one. An unknown value refuses the
  spawn. It is held in daemon memory: after a daemon restart a resumed session gets the global value.
- Each run resolves: the session's own value, then the global value, then `AGENTHQ_TOOL_APPROVAL` (JVM system property,
  then environment), then `deny`. A change applies to the next run.
- Harness hook (harness contract 1.5.0): `HarnessRequest.toolApproval` / `RunRequest.toolApproval`; each harness maps it.
  Claude: `deny` = no flag, `allowEdits` = `--permission-mode acceptEdits`, `allowAll` = `--permission-mode
  bypassPermissions`; the legacy `AGENTHQ_CLAUDE_PERMISSION_MODE` still wins for Claude only. Other harnesses: own tickets.
- A harness denial (Claude: `permission_denials` in its `result` record) adds one additive `stderr` line per distinct
  denied tool to the session stream: `[agenthq] <harness> denied tool <name> (Tool approval = <mode>). Change it in
  Settings > Tool approval.` No new frame type.
- Stored in the daemon's `settings` table under `toolApproval.mode`; `settings_get` does not list it.

### What changed in 1.75.0 (CLIPATH-2) — `node` is a resolved CLI

ADDITIVE, no new frame; a 1.74.0 client keeps working (it renders whatever ids `cli_locations` sends). The daemon's `ready` frame now reports `contract=1.75.0`.
- **`cli_locations`** lists a fifth built-in tool after `git`: `{id: "node", label: "Node.js", ...}`, the runtime Pi's `cli.js` entry point is started with. Same fields and the same resolution order as every other tool: Settings override, then `AGENTHQ_NODE_EXE`, then the known install locations (Windows `%ProgramFiles%\nodejs\node.exe`, then nvm-windows' `%NVM_SYMLINK%`; macOS/Linux `/usr/local/bin/node`, Homebrew, nvm's active version), then PATH.
- **`cli_location_set`** accepts `"node"` as an `id`, validated like the others.
- Not on the wire: the installed app's `agenthq` CLI launcher (sessions get its directory first on PATH and as `AGENTHQ_BIN`; docs/AGENTHQ-INSTALL.md).

### What changed in 1.74.0 (BRANCH-SWITCH-1) — switch a project's checked-out branch

ADDITIVE; a 1.73.0 client keeps working (it never sends the new frame). The daemon's `ready` frame now reports `contract=1.74.0`.
- **`project_git_checkout`** (data = `{projectId, branch, create?}`) → **`project_git_checkout_result`**: `{projectId, branch, create, switched, code, reason, previous?, current?, dirtyCount, worktreePath?}`. Runs `git switch <branch>` (`git switch -c <branch>` from the current HEAD when `create`) in the repository top level of the project's directory. It NEVER forces, stashes or discards.
- A switch the daemon does not make is a result with `switched = false` and a stable `code`, never an `error` frame: `bad_name` (the daemon's name rules or `git check-ref-format` reject it), `unavailable` (no directory / not a repository; `reason` says which), `exists` (create, branch already there), `no_branch` (switch, no such local branch), `held_by_worktree` (another worktree has it; `worktreePath`), `dirty` (tracked files have uncommitted changes; `dirtyCount`), `git_failed` (git itself refused; `reason` carries its first line). Untracked files do not count as dirty: git carries them, and refuses by itself if the target branch would overwrite one.
- Switching to the branch already checked out answers `switched = true` with `reason = "already on '<branch>'"`.
- After `switched = true`, the daemon also pushes a fresh **`project_git`** status for that project to every connected client.
- Only an unknown project or a malformed payload is an `error` `"project_git_checkout refused: ..."`.

### What changed in 1.73.0 (CLIPATH-1) — where each CLI is launched from

ADDITIVE. Every CLI the daemon launches (Claude Code, OpenCode, Pi, a descriptor harness's command, and `git`) is
resolved by ONE resolver, in this order: (1) the user's override from Settings, (2) the CLI's environment variable
(`AGENTHQ_CLAUDE_EXE`, `AGENTHQ_OPENCODE_EXE`, `AGENTHQ_PI_EXE`, new `AGENTHQ_GIT_EXE`), (3) the CLI's known default
install locations for the OS (one table per CLI: `executables/CliTools.kt`), (4) PATH (Windows: PATHEXT names only).

- New request `cli_locations` (no data) -> `cli_locations` `{"tools":[CliLocation]}`; one row per CLI, built-ins first
  (`claude`, `opencode`, `pi`, `git`) then descriptor harnesses. `CliLocation` = `{id, label, path?, source?, override?,
  tried[], reason?}`; `source` is `override` | `env` | `default` | `path`, null with `path` when not found; then `reason`
  says why and `tried` lists every path looked at.
- New request `cli_location_set {id, path?}` stores `path` as that CLI's override, or CLEARS it when `path` is null or
  blank; answered by `cli_locations`. A path that is not absolute, does not exist, is a directory or is not executable
  (Windows: `.exe`/`.cmd`/`.bat`/`.com`; Pi also takes its `cli.js`) is refused, nothing stored:
  `error` `cli_location_set refused: <label>: '<path>' <why>`. An unknown id is refused the same way.
- A stored override that later stops being an executable file is NOT skipped: that CLI reports not found with the reason.
- `ProviderInfo` (the `providers` frame) gains `available` (default true) and `unavailableReason` (null): a provider or
  harness whose CLI cannot be found is listed as unavailable with the reason and the tried paths, instead of looking
  usable and failing at spawn. The `models` frame's rows for such a harness carry the same reason in their note.
- Overrides are stored in the daemon's `settings` table under `cli.executable.<id>`; `settings_get` does not list them.

### What changed in 1.72.0 (BUG-M2-11f) — idle stop, live keep-alive, kept-process reaping

ADDITIVE; a 1.68.0 client keeps working (it ignores the new field, and no frame was added or removed). The daemon's `ready` frame now reports `contract=1.72.0` (it was `1.68.0` on this base; 1.69.0-1.71.0 belong to other tickets).
- **`SessionRecord.keptProcess`** (the `session` / `sessions` frames, `sessions.json`): a new optional object `{pid, startedAt?, label}`. It is set while a harness keeps a persistent process for the session, cleared when that process ends, and omitted when null. A record saved before 1.72.0 decodes to null.
  - `pid` is the kept OS process.
  - `startedAt` is its start instant in epoch milliseconds, absent when the OS did not report one.
  - `label` is the harness that owns it (e.g. `opencode`).
  - It exists for the daemon itself: a client reads liveness from `isProcessAlive` / `live`, as before.
- **Idle stop:** a kept process idle for `session.keepAliveMinutes` minutes is stopped by the daemon. A turn in flight is never idle, and idle time counts from the end of the last turn.
  - The stop is the daemon's own decision, so it sends **no `exit` frame and no chat line**, only an Activity line. The record stays `IDLE` with `isProcessAlive=false`.
  - The next prompt relaunches the process, resumes the provider session and shows `↻ session resumed`.
- **Live re-evaluation:** a `settings_set` of `session.keepAliveMinutes` applies at once.
  - Lowering it stops the kept processes already idle longer than the new value.
  - `0` stops every kept process. A busy session's process stops at the end of its turn.
- **Reaping on daemon start:** a kept process left behind by a daemon that died is tree-killed only when its `pid` is alive AND its start instant equals `startedAt`. A reused pid, or a record with no `startedAt`, is never killed.
  - The record comes back `IDLE` and resumable, even when it was mid-turn, and the reap is reported as an Activity line `reaped stale <label> process pid <pid>`.

### What changed in 1.68.0 (BUG-M2-11b-2) — the session keep-alive setting

ADDITIVE; a 1.65.0 client keeps working (unknown setting keys are ignored). The daemon's `ready` frame now reports `contract=1.68.0` (it was `1.65.0` on this base). No new frame, no schema change.
- **`session.keepAliveMinutes`**: a JSON integer `0..1440`, default `30`, returned by `settings_get` and set by `settings_set`. Any other value (a string, a fraction, a boolean, out of range) is an `error` and nothing is stored. It is read on every launch decision.
  - `0` = keep-alive off: every harness runs one-shot (one process per turn), still through the session host, so the chat and Activity rules of 1.64.0 do not change.
  - `N > 0` = a harness with a persistent mode (today: opencode, one `opencode serve` per session) keeps its process between turns, until the session is closed or deleted or the daemon stops. The idle stop after `N` minutes is not built yet (BUG-M2-11f); a change applies at the session's next launch.
- **Removed:** the `AGENTHQ_OPENCODE_SERVE` environment toggle. It was a daemon configuration, not a frame; the setting replaces it.


### What changed in 1.66.0 (E1-b) — `ticket_list` filters by epic

ADDITIVE; a 1.62.0 client keeps working (empty data and a bare project id answer exactly as before). The daemon's `ready` frame now reports `contract=1.66.0` (it was 1.62.0 on this base; 1.63.0-1.65.0 belong to other tickets). No new frame, no schema change.
- `ticket_list` data may now be a JSON object `{ "projectId"?, "epicId"? }`. `epicId` lists only the tickets whose `epicId` is that epic, in board order; `projectId` filters as the bare-id form does; both given means both must match. A blank or absent field is no filter.
- An epic no ticket is filed under (including one that does not exist) answers an empty `tickets` list, never an `error`. A malformed object or an unknown key is refused as `ticket_list refused: ...`.
- A client that wants the daemon to filter checks `contract >= 1.66.0` first: an older daemon reads the object as a project id and answers an empty board.
### What changed in 1.65.0 (BRANCH-VIEW-1) — local branches and a branch's changes vs its base

ADDITIVE; a 1.64.0 client keeps working (it never sends the new frames, and unknown keys are ignored). The daemon's `ready` frame now reports `contract=1.65.0` (it was `1.64.0`). No schema change.
- **`project_git_branches`** (data = `{projectId}`) → **`project_git_branch_list`**: `{projectId, available, reason, current?, branches[{name, head, upstream?, isWorktree, worktreePath?}], truncated}`. Local branches only (`refs/heads/`), capped at 300 (`truncated`). `current` is the branch checked out in the project's directory, null on a detached HEAD. `isWorktree`/`worktreePath` say which branches a git worktree has checked out.
- **`project_git_branch_changes`** (data = `{projectId, branch, base?}`) → **`project_git_branch_diffstat`**: `{projectId, branch, base, mergeBase, available, reason, changes[{path, status, fromPath?, added, removed, binary}], truncated}`. The diff is `mergeBase..branch` (rename detection on), where `mergeBase` is `git merge-base <base> <branch>`: what the BRANCH changed since it forked, never the working tree, and never what the base did after the fork. `status` uses the `ChangeKind` names (`ADDED`, `MODIFIED`, `DELETED`, `RENAMED`); `fromPath` is set on a rename. `added`/`removed` are line counts, `0` with `binary = true` for a binary file. Capped at 500 (`truncated`).
  - `base` is a local branch name or a full 40-hex commit id. When absent the daemon picks, in order: the branch's RECORDED base (its `<branch>=<sha>` line in `superiorSoftLibrary/agentHq/preflight/expected-bases.properties`, as committed on the branch, else as on disk in the repository), then `feature-agenthq-mvp`, then the repository's default branch (`origin/HEAD`, `init.defaultBranch`, `main`, `master`); never the branch itself. The answer's `base` names what was used.
- **`project_git_branch_diff`** (data = `{projectId, branch, base?, path}`) → **`project_git_patch`**, the 1.36.0 shape plus **`branch`** (the branch this patch belongs to; `null` on a working-tree `project_git_diff` answer). `staged` is always false. Same `binary` and 256 000-character `truncated` rules.
- **Honesty rule, as in 1.35.0:** `available = false` with a `reason` is UNKNOWN and must never be rendered as clean: no directory, not a repository, no base could be resolved, or the branch and base share no history (no merge-base). `available = true` with an empty list is a branch that changed nothing.
- **Refusals** are `error` frames `<frame> refused: <why>`, nothing run: an unknown project; a `path` that is blank, absolute, has a `..` segment or a control character; a `branch` (or a non-sha `base`) that is blank, starts with `-` or `/` or `refs/`, contains `..`, `@{`, a backslash or a control character, or is not an existing local branch (checked with `git check-ref-format refs/heads/<name>` and a lookup). A name reaches git only as `refs/heads/<name>`; every later command gets the resolved commit ids.
- Read-only: refs and objects only, `--no-optional-locks`, no checkout, the working tree is never read or touched.

### What changed in 1.64.0 (BUG-M2-11b-1) — one session lifecycle: one boot line, `exit` means a crash

No frame was added or removed and every frame keeps its shape; the daemon's `ready` frame now reports `contract=1.64.0` (it was `1.62.0`). BEHAVIOUR CHANGES on `exit` and on the daemon's `stderr` boot lines, for every harness alike (`docs/AGENTHQ-SESSION-HOST-PLAN.md`, section 10). Every session now runs through one daemon-owned session host; a harness with no persistent mode (descriptor harnesses, the stub, any provider with no session adapter) runs one process per reply behind it.
- **`exit` (raw stream) now means the session's hosted process ended without the daemon asking**, during or between turns: a crash. A turn's end and its exit code are in `finished`. **One-shot sessions no longer send `exit` per turn** (≤ 1.62.0 sent `exit` then `finished` for every reply). A kept process that dies between turns sends `exit` alone, with no `finished`, because no turn ended. A client that closes on `exit` must also close on `finished`, which every turn still sends (an interrupt still sends none).
- **One chat boot line per launch.** A session's stream carries one `stderr` line `[agenthq] ↻ session <started | resumed | restarted | restarted after a crash> (<harness> · <model>)` when its process is (re)launched, and none for a turn that reuses it. A one-shot session launches once, on its first turn, so it shows ONE line however many replies it gets. The per-turn lines `starting <harness> model <model> in <dir>`, `resuming provider session <id>`, `Resuming session <id>...` and `process started (pid N)` moved to the Activity box: a `status` frame (state `BUSY`) whose `tool` is the provider id and whose `activity` is the line.
- Failure explanations stay in chat as before: `run failed (exit N). If this is an auth problem …`, `turn interrupted by the owner; …`, and, for a harness with a kept process, `the <harness> process did not start: <stderr tail>` and `the <harness> process exited (code N) during the turn; the next message restarts it`.
- **Harness contract 1.4.0** (`HARNESS_CONTRACT_VERSION`, additive): `CliHarness.sessionAdapter()`, null by default (= one-shot), is the seam a plugin harness uses to offer a persistent mode. The handshake is exact-match, so a plugin built against 1.3.0 is refused, as on every harness version change.
- Supersedes UI-BOOT-1's per-turn boot lines, UI-LOG-1's per-turn exit notice and RESUME-1's per-resume line. A continuation stays visibly distinct from a fresh start: `↻ session resumed` vs `↻ session started`.

### What changed in 1.62.0 (E1-c) — the story frames are DEPRECATED shims over the ticket board

No frame was added or removed; the daemon's `ready` frame now reports `contract=1.62.0` (it was `1.61.0`). No schema change. There is one work model: a story IS a ticket. The `story_*` frames stay for one release and then go; use the `ticket_*` frames (`ticket_list` takes `epicId` since 1.66.0).
- **DEPRECATED:** `story_save`, `story_get`, `story_move`, `story_list`. Their `story` / `stories` answers are the ticket board seen through the old shape: `Story.id` IS the ticket id, `projectId` is the ticket's (`""` when it has none), and the status maps `backlog`/`todo`/`pending` -> `todo`, `in_progress` -> `doing`, `done` -> `done`.
- `story_save` with no id creates a TICKET in `todo` (unassigned, carrying the request's `projectId` and `epicId`); it is pushed as the usual `ticket` frame and is listed by `ticket_list`. With an id it edits that ticket's title/project.
- `story_get` / `story_move` take a ticket id or a pre-migration story id; the latter resolves through the `story_migration` table (E1-a). An id that is neither is refused (`<frame> refused: no story '<id>'`).
- `story_move` `doing` puts the ticket in `in_progress` as a plain column change: unlike `ticket_move`, it never hands the ticket out or starts a run.
- **`Epic.storyIds` is derived**: the ids of the tickets whose `epicId` is the epic, in board order. It stays on the `epic` / `epics` frames for one release, filled with ticket ids. A `story_save` create that names an epic still pushes that epic's `epic` frame.

### What changed in 1.61.0 (BUG-M2-15b) — bind an unbound session to a project

ADDITIVE; a 1.60.0 client keeps working (it never sends `session_project`). The daemon's `ready` frame now reports `contract=1.61.0` (it was `1.60.0`). No schema change.
- `session_project` (client → daemon, data `{"sessionId","projectId"}`) binds a session whose `projectId` is null (a record written before spawns carried a project) to a known project. There is no direct answer: the changed `session` record is pushed to every client, like any other record change.
- Refused as an `error` frame `session_project refused: <reason>`, nothing written: an unknown session, a blank or unknown project, or a session already bound to ANOTHER project (re-binding to its own project is accepted and changes nothing). Nothing is ever assigned automatically.

### What changed in 1.60.0 (M2-17) — clearing a ticket's epic

BEHAVIOUR CHANGE on one field, no new frame, no schema change. The daemon's `ready` frame now reports `contract=1.60.0` (it was `1.59.0`).
- `ticket_save` on an edit (an `id` is given): an empty `epicId` (`""`, or blank) now CLEARS the ticket's epic, so `Ticket.epicId` comes back null. Before 1.60.0 a blank `epicId` on an edit kept the current epic; there was no way to clear it.
- An absent or null `epicId` still keeps the current epic, and on a create a blank one still means no epic. The rule now matches `description`, `model` and `worktreeBranch` (null keeps, empty clears).
- A client that must clear an epic checks `contract >= 1.60.0` first: an older daemon would silently keep it.

### What changed in 1.59.0 (U1) — plan-limit meters

ADDITIVE; a 1.58.0 client keeps working (it never asks for `limits` and ignores a pushed one). The daemon's `ready` frame now reports `contract=1.59.0` (it was `1.58.0`). No schema change.
- `limits` (client → daemon, empty `data`) is answered by `limits`: a list of `ProviderLimits`, one per live source (`claude`, `opencode-go`). The same frame is pushed to every client after a run's `usage` frame.
- `ProviderLimits`: `{provider, status, windows, reason, observedAt}`. `status` is `ok` (windows read) or `unknown` (no windows; `reason` says why). No signal is never 0%.
- `LimitWindow`: `{provider, label, windowMinutes, percent, resetsAt, observedAt, source, paceDelta}`. `percent` null = unknown; `resetsAt` / `observedAt` are epoch millis; `source` is `live`. `paceDelta` = `percent` minus the share of the window already elapsed (`elapsed / window × 100`), one decimal: positive = ahead of pace, negative = under; null when `percent` or `resetsAt` is unknown.
  - `claude`: `GET https://api.anthropic.com/api/oauth/usage` with Claude Code's own OAuth access token (`$CLAUDE_CONFIG_DIR` or `~/.claude`, `.credentials.json` → `claudeAiOauth.accessToken`). `five_hour` → "Current session" (300 min), `seven_day` → "Current week" (10080 min); a `limits` array is read too.
  - `opencode-go`: `GET https://opencode.ai/zen/go/v1/usage` with the `opencode-go` key of opencode's `auth.json` (`$XDG_DATA_HOME/opencode`, else `~/.local/share/opencode`). `usage.rolling` → "5h limit" (300), `usage.weekly` → "Weekly limit" (10080), `usage.monthly` → "Monthly limit" (43200).
- `reason` is one fixed code: `no_credentials`, `expired` (401), `no_subscription` (403), `rate_limited` (429), `http_<code>`, `network`, `bad_response`, `source_failed`, `live_sources_off`.
- Reads are refreshed on request at most once per 5 minutes (12 s timeout per call); in between, the last read is answered. `AGENTHQ_LIMITS_LIVE=false` (or `0` / `off` / `no`) turns the live reads off: no credential is read, no call is made, every provider reads `unknown` / `live_sources_off`.
- A credential is sent only to its own provider's endpoint. It is never logged, stored, or put in a frame or a reason.

### What changed in 1.58.0 (BUG-G2d) — a session's spawns are bounded

ADDITIVE; a 1.57.0 client keeps working (no frame was added or removed, and a frame without `sessionId` behaves as before). The daemon's `ready` frame now reports `contract=1.58.0` (it was `1.57.0`). No schema change.
- `pod_submit` and `new` now read the envelope's `sessionId` as the session the spawn comes FROM (a SESSION-ORIGINATED spawn). `agenthq submit` and `agenthq mcp serve`'s `agenthq_spawn` set it from `AGENTHQ_SESSION_ID`, which the daemon exports into every session. A `sessionId` the daemon does not hold, or none at all (the user at a terminal, the UI), is not charged.
- A session nobody spawned from inside a session is a pod ROOT. Every session a session-originated spawn starts joins the spawning session's pod, one level deeper. Two rules, checked before anything is created:
  - **Depth.** A spawn whose sessions would sit deeper than `AGENTHQ_POD_SPAWN_DEPTH` (default `1`: only a root may spawn) is refused with `error` `spawn refused: depth limit. …`.
  - **Pod cap.** A pod may hold at most `AGENTHQ_POD_SPAWN_CAP` (default `4`) live sessions spawned from inside it: not EXITED / FAILED / ORPHANED (an IDLE one still counts), plus pod nodes not yet started. A `pod_submit` asks for one session per node. Past the cap: `error` `spawn refused: pod cap. …`.
- A refused `pod_submit` writes no pod and starts no session. A refused `new` is answered `new refused: spawn refused: …`, binds nothing and clears the connection's spawn options, as any refused `new` does; a later session created by a `prompt` after a `new` is checked again under the same rules.
- The lineage is held in memory: after a daemon restart, every surviving session is a root again. It bounds a runaway cascade within one daemon's life; it is not an access control.
- CLI (no wire change): an explicit `--host` / `--port` now beats `AGENTHQ_URL` (section 7.4). The G2 cascade was a test suite run inside a session: its `agenthq submit --port <fixture>` calls were sent to the live daemon by the `AGENTHQ_URL` that session inherited.

### What changed in 1.57.0 (BUG-G2b) — a pod's project, and its first turn

ADDITIVE; a 1.56.0 client keeps working (one new optional field; no frame was added or removed). The daemon's `ready` frame now reports `contract=1.57.0` (it was `1.56.0`). No schema change.
- `PodSubmitRequest` (the `pod_submit` payload) gains `projectId` (string, optional). Every session the pod spawns carries it; absent or blank = the daemon's default project (the oldest, as `agent_save` uses). `agenthq submit --project <id>` sends it (else `AGENTHQ_PROJECT_ID` inside a spawned agent).
- Each node's FIRST attempt now gets a composed first turn, like a `prompt` on a new session: the project's `knowledge` section (P10-K9), then the `skills` section (P10-K15; a pod session is anonymous, so its attached skills are the project's), then the node's prompt. A retry is unchanged: `RetryFeedback` over the original node prompt.

### What changed in 1.56.0 (P10-K12) — skill attachment on an agent, and `attachedOnly`

ADDITIVE; a 1.55.0 client keeps working (new optional fields only; no frame was added or removed). The daemon's `ready` frame now reports `contract=1.56.0` (it was `1.55.0`). Schema v34 -> v35 (`34.sqm`: `agents.skill_ids` (default `[]`) and `skills.attached_only` (default 0), so every existing agent attaches nothing and every existing skill stays visible). Design: [`docs/AGENTHQ-PHASE10-KNOWLEDGE.md`](../../docs/AGENTHQ-PHASE10-KNOWLEDGE.md) K-D9. Nothing renders skills yet (K13 resolves and freezes the effective set, K15 renders it).
- `AgentProfile` gains `skillIds` (attach order), the agent's always-on toolkit. On `agent_save`, absent or null **keeps** the stored list and `[]` clears it (the `traitIds` rule); every id must name an existing skill and appear once, or the whole save is refused (`skillIds: unknown skill '<id>'` / `listed twice`). `agent_swap` keeps the list. `agent` / `agents` always carry it (`[]` when none). A skill deleted later stays listed.
- `SkillSave` gains `attachedOnly` (boolean, optional): absent or null **keeps** the stored flag on an update (a new skill gets `false`), so an older client re-saving a skill cannot un-hide it. `SkillView` always carries `attachedOnly`. `skill_list` still returns every skill, `attachedOnly` ones included; readers that list skills to an agent (`agenthq skill list`, the K15 index) leave them out.
- `skill_get` data may now be an exact skill **name** as well as an id (ids are tried first). An unknown one is still `skill_get refused: unknown skill '<x>'`.

### What changed in 1.55.0 (P10-K11) — skill attachment on a ticket and the project

ADDITIVE; a 1.54.0 client keeps working (new optional fields only; no frame was added or removed). The daemon's `ready` frame now reports `contract=1.55.0` (it was `1.54.0`). Schema v33 -> v34 (`33.sqm`: `tickets.skill_ids` (default `[]`), `tickets.skills_mode` (default `merge`) and `tickets.include_skills_context` (default NULL), so every existing ticket attaches nothing, merges and inherits). Design: [`docs/AGENTHQ-PHASE10-KNOWLEDGE.md`](../../docs/AGENTHQ-PHASE10-KNOWLEDGE.md) K-D9. Nothing renders skills yet (K13 resolves and freezes the effective set).
- `Ticket` gains `skillIds` (attach order), `skillsMode` (`merge` | `replace`) and `includeSkillsContext` (nullable).
- `ticket_save` takes the same three, optionally. `skillIds`: absent or null **keeps** the stored list, `[]` clears it; every id must name an existing skill and appear once, or the whole save is refused (`unknown skill '<id>'` / `listed twice`). A skill deleted later stays on the ticket. `skillsMode`: absent keeps (a new ticket gets `merge`); any other value is refused. `includeSkillsContext`: absent or null keeps, exactly like `includeLinkedTasksContext`.
- `effectiveContext` gains `includeSkillsContext: {value, source}`, resolved like the linked-tasks switch: the ticket's value (`ticket`), else the `context.includeSkillsContext` setting (`project`), else `true` (`default`).
- Settings gain `context.includeSkillsContext` (JSON boolean, default true) and `context.skillIds` (JSON array of existing skill ids, no duplicates, default `[]`), both returned by `settings_get`. A wrong type or an unknown id is refused and nothing is stored.
- Fix: a setting written a second time now keeps the second value. Before 1.55.0 the daemon stored only the FIRST `settings_set` of each key and silently dropped later ones (this affected `context.includeLinkedTasksContext` too).

### What changed in 1.54.0 (P10-K6) — the issues log

ADDITIVE; a 1.53.0 client keeps working (six new request frames and two new answer frames; nothing existing changed). The daemon's `ready` frame now reports `contract=1.54.0` (it was `1.53.0`). Schema v32 -> v33 (`32.sqm`: the new `issues` and `issue_events` tables; no existing row is touched). Design: [`docs/AGENTHQ-PHASE10-KNOWLEDGE.md`](../../docs/AGENTHQ-PHASE10-KNOWLEDGE.md) K-D8.
- An **issue** is `{projectId, number, ref, summary, location, ticketId?, implicit, status, openedBySession?, openedByAgent?, createdAt, events}`. `number` counts from 1 per project and `ref` is `I<number>`. `status` is derived: `fixed` once `events` holds a fix, `open` otherwise. Each event is `{seq, kind, summary, outcome?, location?, sessionId?, agentId?, createdAt}`, `kind` `attempt` (with `outcome` `worked` | `failed` | `partial`) or `fix` (no outcome), `seq` from 1 per issue. Times are Unix epoch milliseconds.
- **Append-only.** There is no edit and no delete frame, and the database aborts any `UPDATE` or `DELETE` on either table (raw SQL included). One fix per issue, and nothing after it: a recurrence is a new issue.
- **The active issue is per session.** It is the open issue the declared session most recently opened or named in an attempt. Another session's issues are never a candidate, and a frame with no `sessionId` has no active issue.
- `issue_open {projectId, sessionId?, summary, location, ticketId?}`: `summary` is one non-blank line of at most 200 chars; `location` (at most 500 chars) is required; `ticketId` is a free link, not checked. The issue becomes the session's active issue.
- `issue_attempt {projectId, sessionId?, summary, outcome? = "failed", location?, issue?}`: `summary` at most 4 000 chars; any other `outcome` is refused. Without `issue` (`"I3"` or `"3"`), the attempt goes to the session's active issue; with none, an issue is opened from the attempt's first line (its location the attempt's, or `""`), flagged `implicit: true`, so an attempt is never lost.
- `issue_fix {projectId, sessionId?, summary, issue?}`: closes the named issue or the session's active one.
- Each of the three is answered by the whole issue, **pushed** as `issue_item` to every connected client, the one that asked included (for it, the push IS the answer). The author fields come from the declared session (its agent).
- `issue_list {projectId, includeFixed = false, location?}` → `issues`: number order, open only unless `includeFixed`, only those at or under `location` when given. `issue_get {projectId, ref}` → `issue_item`. `issue_precheck {projectId, path}` → `issues`: the issues whose location is `path` or lies under it, open ones first. Paths are compared by segment after mapping `\` to `/` and dropping a `:line` suffix, so `src/a` matches `src/a/b.kt:12` and never `src/ab`.
- Refusals put the code first: `issue_not_found` (the project has no such number), `no_active_issue` (a fix with no `issue` and no active issue), `already_fixed` (a fix or an attempt on a fixed issue), `session_not_found` (the declared session is unknown). A blank, multi-line or oversized summary, a missing location, an unknown outcome and a malformed ref are refused without a code.

### What changed in 1.53.0 (P10-K4) — the decisions log

ADDITIVE; a 1.52.0 client keeps working (three new request frames and two new answer frames; nothing existing changed). The daemon's `ready` frame now reports `contract=1.53.0` (it was `1.52.0`). Schema v31 -> v32 (`31.sqm`: the new `decisions` table; no existing row is touched). Design: [`docs/AGENTHQ-PHASE10-KNOWLEDGE.md`](../../docs/AGENTHQ-PHASE10-KNOWLEDGE.md) K-D7.
- A **decision** is `{projectId, number, ref, summary, body, supersedes?, supersededBy?, authorSessionId?, authorAgentId?, authorRole?, createdAt}`. `number` counts from 1 per project and `ref` is `D<number>`. `supersededBy` is derived; a decision is *active* when it is null. `createdAt` is Unix epoch milliseconds.
- **Append-only.** There is no edit and no delete frame, and the database aborts any `UPDATE` or `DELETE` on the table (raw SQL included). A wrong decision is corrected by a new one that supersedes it.
- `decision_add {projectId, sessionId?, summary, body?, supersedes?}`: `summary` is one non-blank line of at most 200 chars; `body` at most 16 000 chars; `supersedes` is a ref (`"D1"` or `"1"`, a string) naming an active decision of the same project. The author fields come from the declared `sessionId` (its agent and role); none declared leaves them null. The new decision is **pushed** as `decision_item` to every connected client, the one that asked included (for it, the push IS the answer).
- `decision_list {projectId, includeSuperseded = false}` → `decisions`: the project's decisions in number order, active ones only unless `includeSuperseded`.
- `decision_get {projectId, ref}` → `decision_item`; `ref` is `D7` or `7`.
- Refusals put the code first: `decision_add refused: decision_not_found: ...` (the project has no such number; another project's decision is never found), `already_superseded` (supersede the head of the chain instead), `session_not_found` (the declared session is unknown); `decision_get refused: decision_not_found: ...`. A blank or multi-line or oversized summary, an oversized body and a malformed ref are refused without a code.

### What changed in 1.52.0 (TRAITS-T6) — REPORT BACK enforcement

ADDITIVE; a 1.51.0 client keeps working (an unknown `report` key on a session record is ignored by a lenient decoder, and an unknown attention kind is already rendered generically). The daemon's `ready` frame now reports `contract=1.52.0` (it was `1.51.0`). Schema v30 -> v31 (`30.sqm`: the `attention` table is rebuilt to extend its `kind` CHECK; every existing row is kept). Design: [`docs/AGENTHQ-ROLE-TRAITS.md`](../../docs/AGENTHQ-ROLE-TRAITS.md) section 5, D7-D10. The doc named 1.51.0 for this slice; P10-K2 took it, so TRAITS-T6 is 1.52.0.
- `SessionRecord` (the `session` / `sessions` frames, `sessions.json`) gains an optional `report`: `{status, traitId, reprompted, checkedRun, reportMessageId?, attentionId?}`. It is null when the session owes no report: an interactive session, an anonymous one, or one whose frozen role instance has no trait with a `report` check. An unattended session (ticket hand-out, trigger) with such a trait starts `pending`.
- **Detection.** When a run ends BY ITSELF (an operator kill is never checked), the session has reported once its outbox holds a mail sent since spawn, addressed to the check's `reportTo` role (the role name, an agent id with that role, or a session id with that role), whose body has every required field as a line `label: value` (label ignoring case, optional leading `-` / `*`; the value non-blank and not an unfilled `<placeholder>`; `NOT RUN`, `none` and `unknown` are valid). Found: `status` = `reported` and `reportMessageId` names the mail. A run that ends while the session has an open needs-input ask is not checked. One run is checked at most once (`checkedRun`).
- **On a miss.** `status` = `unreported`. If the check allows a re-prompt, it is unused and the run exited 0, the daemon sends ONE mail to the session from `system`, subject `REPORT BACK missing`, naming the missing fields and the exact `agenthq send <reportTo>` command (`reprompted` = true); no attention yet. Otherwise (re-prompt off, a failed run, the re-prompt already used, or refused by the mailbox) ONE attention item of the new kind **`unreported`** is raised (`sessionId`, `ticketId`, `outcome` `unreported`, a `detail` naming the role, agent, run number and missing fields) and `attentionId` names it. At most one per session. A later qualifying mail still flips `status` to `reported`; acking the attention item never changes `status`.

### What changed in 1.51.0 (P10-K2) — memory_save modes, memory_get, folder on the index

ADDITIVE; a 1.50.0 client keeps working (a four-field `memory_save` still replaces, and the new `folder` key on `memory_notes` rows is ignored by a lenient decoder). The daemon's `ready` frame now reports `contract=1.51.0` (it was `1.50.0`). No schema change (notes already stored `folder`). Design: [`docs/AGENTHQ-PHASE10-KNOWLEDGE.md`](../../docs/AGENTHQ-PHASE10-KNOWLEDGE.md) K-D5 and K-D6. The doc's K-D13 named 1.52.0 for this slice; the PM allocated 1.51.0 (the next free number after TRAITS-T3), and TRAITS-T6 moves to the next one.
- `memory_save` gains optional `mode` (`replace`, the default and the old behaviour; `append`; `edit`), `find`, `content` and `folder`.
  - `replace` takes `description` and `body` (both required, as before) and creates a missing note.
  - `append` takes `content` and adds it after the stored body, separated by exactly one blank line (trailing line breaks of the body fold into that separator; an empty body takes none).
  - `edit` takes a non-empty `find` and a `content` (`""` removes the passage) and replaces the single occurrence of `find`. `find` is matched byte for byte, with no trimming, case folding or line-ending normalisation, and occurrences are counted **with overlap** (`aa` in `aaa` is two).
  - `append` and `edit` **never create**; `description` is optional there and a null one keeps the stored cue. In every mode an omitted `folder` keeps the note where it is; a new note goes to `""`.
  - Sending a field of another mode is refused (`replace` with `content` or `find`; `append` with `body` or `find`; `edit` with `body`), as is an unknown `mode`.
- Refusals put a **code first**: `memory_save refused: note_not_found: ...` (append/edit on a missing note), `anchor_not_found: ...` (0 matches), `anchor_ambiguous: 'find' occurs 3 times in note 'x'; pass a longer passage` (2+). A refused save writes nothing: body, cue, folder and `updatedAt` stay byte-identical.
- The `memory_note` answer still carries the whole note (the UI relies on it).
- New `memory_get` `{projectId, name}` → `memory_note`, or `memory_get refused: note_not_found: ...` (another project's note is not found either).
- `memory_notes` rows gain `folder` (`""` = the project root). The list stays in name order; the CLI orders by folder then name.
- The `agenthq memory index | show | search | save` CLI verbs (P10-K3) ride on these frames; they add no frame.

### What changed in 1.50.0 (TRAITS-T3) — agent traits

ADDITIVE; a 1.49.0 client keeps working (an unknown `agent` key is ignored, and a save without the new field changes nothing). The daemon's `ready` frame now reports `contract=1.50.0` (it was `1.49.0`). Schema v28 -> v29 (`28.sqm`, column `agents.trait_ids`, existing rows `[]`). Design: [`docs/AGENTHQ-ROLE-TRAITS.md`](../../docs/AGENTHQ-ROLE-TRAITS.md) section 2.
- `AgentProfile` gains an optional `traitIds` (string array): the traits this agent **adds** to its role's list. An agent cannot remove a role trait in v1.
- On `agent_save`, **absent or `null` keeps the stored list**, and `[]` clears it. `agent_save` overwrites the whole profile, so a client that never heard of traits must not strip them silently. A new profile without the field starts with `[]`.
- `agent` / `agents` (and `agent_swapped.agent`) frames always carry the stored list (`[]` when none). `agent_swap` keeps it unchanged.
- Refused as `agent_save refused: ...`, nothing written: a trait id not in the library; more than 8 ids; the same id twice (ids are trimmed).
- **Effective set** (what a session will run under, rendered from TRAITS-T4 on): the role's `role_traits` list in order, then the agent's `traitIds` in order, duplicates dropped (first wins). Daemon-side, one pure function (`TraitBindings.effective`); not a frame.
- `trait_delete` is now also **refused** while an agent's `traitIds` lists the trait (the reason names `agent '<id>'`).
- HARNESS contract stays 1.3.0.

### What changed in 1.49.0 (TRAITS-T2) — role traits

ADDITIVE; a 1.48.0 client keeps working (unknown frames are ignored, no existing payload changed). The daemon's `ready` frame now reports `contract=1.49.0` (it was `1.48.0`). Schema v27 -> v28 (`27.sqm`, table `role_traits`). Design: [`docs/AGENTHQ-ROLE-TRAITS.md`](../../docs/AGENTHQ-ROLE-TRAITS.md) section 2 (D2). It mirrors 1.40.0 `role_binding_*`.
- `role_traits_set` (data = `RoleTraitsSave` JSON `{roleId, traitIds[]}`, strict keys, both required) replaces the role's whole ordered list and pushes the stored `role_traits` frame (`RoleTraits` = `{roleId, traitIds, updatedAt}`) to EVERY client. `traitIds: []` clears the list.
- `role_traits_get` (data = role id) answers `role_traits`; a role with no list answers `{roleId, traitIds: [], updatedAt: 0}` (never an error). `role_traits_list` (data ignored) answers `role_traits_all` (a `RoleTraits` array of every role with a stored list, ordered by role id).
- Refused as `error` frames `role_traits_set refused: ...`, nothing written: a role not in the catalog; a trait id not in the library; more than 8 ids; the same id twice (ids are trimmed). The trait-exists check runs in the write's transaction.
- `trait_delete` is now **refused** (`trait_delete refused: trait '<id>' is still bound to role '<role>', ...`, nothing deleted) while a role lists the trait, so no id dangles. Unbind it with `role_traits_set` first.
- HARNESS contract stays 1.3.0.

### What changed in 1.48.0 (TRAITS-T1) — the trait library

ADDITIVE; a 1.47.0 client keeps working (unknown frames are ignored, no existing payload changed). The daemon's `ready` frame now reports `contract=1.48.0` (it was `1.47.0`). Schema v26 -> v27 (`26.sqm`, table `traits`). Design: [`docs/AGENTHQ-ROLE-TRAITS.md`](../../docs/AGENTHQ-ROLE-TRAITS.md) section 2 (D1, D11).
- A **trait** is a small, versioned, always-on rule: `TraitView` = `{id, name, version, body, check?, createdAt, updatedAt}`. Binding a trait to a role or an agent, and rendering it into a session, are later bumps (1.49.0 / 1.50.0); 1.48.0 is the library only.
- `trait_save` (data = `TraitSave` JSON `{id, name, body, check?}`, strict keys, the nested `check` included) pushes the stored `trait_item` frame (`TraitView`) to EVERY client.
  Save-as-update is by **`id`** (a slug chosen by the author), NOT by name: an unknown id creates the trait at `version` 1; a known id keeps its `createdAt` and moves `version` +1 (and `updatedAt`) only when `name`, `body` or `check` differs from what is stored. An identical save writes nothing and answers the stored trait unchanged.
- `check` (optional, `TraitCheck`): `{kind, reportTo?, fields[], reprompt?}`. v1 knows one kind, `report`; `reportTo` defaults to `pm` and must be a role id in the catalog; `fields` are the 1..12 labels the report must carry; `reprompt` defaults to `true`. Stored and validated only: enforcement arrives with 1.51.0. Absent / null = no check; dropping a check is a change (new version).
- `trait_list` (data ignored) answers `traits` (a `TraitView` array ordered by id). `trait_get` (data = id) answers `trait_item`. `trait_delete` (data = id) pushes the remaining `traits` list to every client.
- Limits (refused as `error` frames `trait_save refused: ...`, nothing written): `id` matches `[a-z][a-z0-9-]{0,31}` (the role-id pattern); `name` non-blank and <= 80 chars (trimmed); `body` non-blank and <= 2048 UTF-8 bytes (anything longer belongs in a skill); check `kind` other than `report`; `reportTo` not in the role catalog; `fields` empty or more than 12; a label not matching `[A-Za-z][A-Za-z0-9 _-]{0,31}` (trimmed); duplicate labels ignoring case.
  Unknown id on `trait_get` / `trait_delete` is `trait_get refused` / `trait_delete refused`.
- HARNESS contract stays 1.3.0.

### What changed in 1.47.0 (ROLE-FIRST S3) — `agent_swap` / `agent_swapped`

ADDITIVE; a 1.46.0 client keeps working (it never sends `agent_swap`, and it ignores a frame type it does not know). The daemon's `ready` frame now reports `contract=1.47.0` (it was `1.46.0`). No schema change (stays v26).
- `agent_swap` (client → daemon), data `{"agentId":"<id>","provider":"<harness>","model":"<model>?","effort":"<level>?","session":"auto"|"keep"|"new"}`, decoded strictly (an unknown key is refused). `provider` is required; a blank `model` / `effort` = inherit. `session` defaults to `auto`. Only the agent's `provider` / `model` / `effort` change; its name, role, persona and project are kept as stored.
- The executor is validated exactly as `agent_save` validates it (one shared check): an unconfigured provider, a model that could change a command line or that the provider does not know, an effort not in `low`, `medium`, `high`, `xhigh`, `max`.
- Answered by `agent_swapped` pushed to EVERY client (the same fan-out as `role_binding_set`): `{"agent":<AgentProfile as stored>,"from":{"provider","model","effort"},"session":{"outcome":"kept"|"new"|"none","sessionId":"<id>?"}}`. `from` is the executor the agent resolved to before the swap (provider never null: the daemon default is named; a null model / effort = the harness default).
- The agent's current session is its run in flight, else its latest session. Outcome with `session:"auto"`: no session → `none`; idle / finished on the same harness → `kept` (the session's NEXT run uses the new model / effort, as `/model` and `/effort` do); idle / finished with a harness change → `new` (a fresh session bound to the agent, at rest, on the new harness; the old one stays in history, resumable on its own harness); running → the run finishes on the old executor, nothing is interrupted, outcome `kept` (same harness) or `none` (harness change: the next spawn for the agent resolves to the new harness).
- Mail after a cross-harness swap (BUG-S3, no wire change): a message addressed to the AGENT (its profile id or its role) whose resolved session is at rest on a harness other than the agent's stored provider is delivered to a fresh session bound to the agent on its current harness (its first turn carries persona + identity, like any first turn); the old session stays in history, untouched. Mail addressed to a session id still resumes that exact session.
- `session:"keep"` is refused when the harness changes (a conversation cannot cross harnesses); with no session it answers `none`. `session:"new"` always answers `new`.
- A refusal is an `error` frame `agent_swap refused: <reason>` (unknown agent, missing or unconfigured provider, bad model, bad effort, bad session mode, `keep` across harnesses) and NOTHING is written: the profile and every session stay as they were.
- HARNESS contract stays 1.3.0.

### What changed in 1.46.0 (ROLE-FIRST S2) — `AgentProfile.effort`

ADDITIVE; a 1.45.0 client keeps working (it never sends the new field, and an unknown key is ignored on decode). The daemon's `ready` frame now reports `contract=1.46.0` (it was `1.45.0`). Schema v25 -> v26 (`25.sqm`, `ALTER TABLE agents ADD COLUMN effort TEXT`; existing rows keep `effort` null).
- `AgentProfile` (the JSON of `agent_save`, `agent` and each `agents` row) gains one optional field, `effort`. Null or absent = inherit.
- `agent_save` with an effort that is not one of the daemon's effort levels (`low`, `medium`, `high`, `xhigh`, `max`) is refused as `agent_save refused: unknown effort '<x>': one of [...]`; nothing is written.
- Resolution per field, first non-null wins: the spawn's own choice, then the profile, then the role binding, then the daemon default (`effort ?: profile.effort ?: binding.effort`).
- HARNESS contract stays 1.3.0.

### What changed in 1.45.0 (P10-B) — the skills library

ADDITIVE; a 1.44.0 client keeps working (unknown frames are ignored, no existing payload changed). The daemon's `ready` frame now reports `contract=1.45.0` (it was `1.44.0`). Schema v24 -> v25 (`24.sqm`, table `skills`).
- `skill_save` (data = `SkillSave` JSON `{name, description?, body, tags[]}`, strict keys) stores a reusable skill and pushes the stored `skill_item` frame (`SkillView`: `id, name, description, body, tags, createdAt, updatedAt`) to EVERY client.
  `name` is unique per project (one daemon = one project): saving an existing name UPDATES that skill in place (same `id` and `createdAt`, `updatedAt` moves).
- `skill_list` (data = optional tag; empty = all) answers `skills` (a `SkillView` array ordered by name). With a tag, only skills carrying exactly that (normalised, case-insensitive) tag.
- `skill_get` (data = id, or since 1.56.0 an exact name) answers `skill_item`. `skill_delete` (data = id) pushes the remaining `skills` list to every client.
- Limits (refused as `error` frames `skill_save refused: ...`, nothing written): name non-blank and <= 120 chars; description <= 500 chars (may be empty); body non-blank and <= 64 KB (65536 UTF-8 bytes); tags trimmed, lowercased and de-duplicated, each non-blank and <= 32 chars, at most 16 tags.
  Unknown id on `skill_get` / `skill_delete` is `skill_get refused` / `skill_delete refused`.
- HARNESS contract stays 1.3.0.

### What changed in 1.44.0 (P10-A) — the prompt library

ADDITIVE; a 1.43.0 client keeps working (unknown frames are ignored, no existing payload changed). The daemon's `ready` frame now reports `contract=1.44.0` (it was `1.43.0`). Schema v23 -> v24 (`23.sqm`, table `prompts`).
- `prompt_save` (data = `PromptSave` JSON `{name, body, tags[]}`, strict keys) stores a reusable prompt and pushes the stored `prompt_item` frame (NOT `prompt`: that name is the client-to-daemon send-a-prompt frame) (`PromptView`: `id, name, body, tags, createdAt, updatedAt`) to EVERY client.
  `name` is unique per project (one daemon = one project): saving an existing name UPDATES that prompt in place (same `id` and `createdAt`, `updatedAt` moves).
- `prompt_list` (data = optional tag; empty = all) answers `prompts` (a `PromptView` array ordered by name). With a tag, only prompts carrying exactly that (normalised, case-insensitive) tag.
- `prompt_get` (data = id) answers `prompt_item`. `prompt_delete` (data = id) pushes the remaining `prompts` list to every client.
- Limits (refused as `error` frames `prompt_save refused: ...`, nothing written): name non-blank and <= 120 chars; body non-blank and <= 64 KB (65536 UTF-8 bytes); tags trimmed, lowercased and de-duplicated, each non-blank and <= 32 chars, at most 16 tags.
  Unknown id on `prompt_get` / `prompt_delete` is `prompt_get refused` / `prompt_delete refused`.
- HARNESS contract stays 1.3.0.

### What changed in 1.43.0 (ROLE-FIRST S1) — `SpawnOptions.role`

ADDITIVE; a 1.42.0 client keeps working (it never sends the new field). The daemon's `ready` frame now reports `contract=1.43.0` (it was `1.42.0`). No schema change.
- `SpawnOptions` (the JSON of a `new` frame) gains one optional field, `role`. A spawn naming it attaches the session to that role: the session's `role` is set,
  with or without an `agentId`, and a console opened with only a `role` gets its session at creation (BIND AT CREATION), as one opened with an `agentId` does.
- With no `agentId`, provider, model and effort come from the role's binding (`role_binding_*`): the spawn's own choice, then the binding, then the daemon default.
  With an `agentId`, resolution is unchanged (spawn > profile > binding).
- Refused as an `error` frame, nothing started: a role the catalog does not list (`unknown role '<id>'`); a `role` that differs from the named profile's role.
- HARNESS contract stays 1.3.0.

### What changed in 1.42.0 (P9.6) — signature-admitted webhook triggers

ADDITIVE; a 1.41.0 client keeps working (it never sends the new field). The daemon's `ready` frame now reports `contract=1.42.0` (it was `1.41.0`).
- NEW ROUTE `POST /hooks/{triggerId}`. It is admitted WITHOUT the daemon bearer (Owner decision 2026-10-18) but only with a valid per-hook signature;
  no other route changes. Only the exact path `/hooks/[A-Za-z0-9_-]{1,64}` is exempt, so a dot-segment, nested or encoded path never borrows it.
  The Origin check, and without a token the Host check, still apply.
- Headers: `X-AgentHQ-Signature: v1=<hex HMAC-SHA256 of "v1:<timestamp>:<delivery>:<raw body>">` keyed by the trigger's secret,
  `X-AgentHQ-Timestamp` (epoch seconds) and `X-AgentHQ-Delivery` (`[A-Za-z0-9._-]{1,128}`).
- Answers: `200 {"status":"ok","disposition":"<started|folded|duplicate|suppressed_*|...>"}`; `401` for a missing/invalid signature, unknown trigger,
  non-webhook trigger or malformed header (all indistinguishable), or, once authenticated, a timestamp outside the 300 s replay window;
  `413` for a body over 64 KB (checked before authentication). A delivery id seen before is answered 200 `duplicate` and never runs again.
- `TriggerSave` gains `webhookSecret` (optional): kind `webhook` is now SERVED and REQUIRES it on create (32..256 characters, no whitespace or
  control characters); on edit, absent keeps the stored one. It is WRITE-ONLY: never in a `TriggerView`, an event or a log line. Refused on any other kind.
  Changing a webhook trigger to another kind drops its secret.
- Events: a webhook event has `source: "webhook"` and carries `deliveryId` and `digest` (SHA-256 hex of the body). The body is hashed, never
  interpreted. Quoting it into a `prompt` action is not served yet.
- Durable: the secret is stored in `trigger_webhooks` (migration `22.sqm`, schema v23), in plaintext because the HMAC needs it.

### What changed in 1.41.0 (P9.5d) — the trigger `prompt` action

ADDITIVE; a 1.40.0 client keeps working (it never sends the new field, and decodes the stored one with `ignoreUnknownKeys`). The daemon's `ready` frame now
reports `contract=1.41.0` (it was `1.40.0`). Nothing else changed.
- `TriggerAction` gains one optional field, `prompt`. Action kind `prompt` is now SERVED: firing the trigger starts an UNATTENDED session for `agentId`
  (a profile id or `role:<role>`) whose first message is the profile's instructions (if any) followed by `prompt`, with NO ticket (the board does not
  move). It takes the launch path `start_next` takes (assignment, role binding, spawn check, launch gate) but never queues: an unknown or busy agent, or a
  refused gate, is a refused start, which trips the trigger like any non-success. The session runs in the daemon's shared checkout.
- `trigger_save` refuses (`trigger_save refused: …`, nothing stored): a `prompt` action with no or blank `agentId`; with a missing, blank or longer than 4000
  characters `prompt`; a `prompt` on any other action kind; and any action kind other than `start_next` / `prompt` (`pod` stays unserved), naming it.
- Durable: the prompt is stored in `triggers.action_prompt` (migration `21.sqm`, schema v22) and survives a daemon restart.

### What changed in 1.40.0 (ROLE-1c) — a role's binding over the wire

ADDITIVE; a 1.39.0 client keeps working. `AgentProfile` is NOT touched (it is strict-decoded). A role's binding is what it runs on: `provider`, `model`,
`effort`, each optional (null = no pin, the daemon default applies at spawn time).
- `role_binding_list` (empty data) → `role_bindings`: JSON list of `RoleBinding` `{roleId, provider, model, effort, updatedAt}`, only roles that have one.
- `role_binding_get` (data = role id) → `role_binding`: the stored binding, or `{roleId}` with every pin null when the role has none yet.
- `role_binding_set` (data = `{roleId, provider?, model?, effort?}`, strict: an unknown key is refused) → the stored `role_binding` pushed to EVERY client. A blank
  field clears that pin. Refused with an `error` (nothing written) for an unknown role, a provider the live `providers` frame does not list (code plugins and
  JSON descriptors alike), or an effort outside `low, medium, high, xhigh, max`.
- Changing a binding affects FUTURE sessions only: a session copied its provider/model/effort at spawn (ROLE-1b).

### What changed in 1.39.0 (CP-4, slice d) — `/model`, `/effort` and `/compact` run against the session

ADDITIVE; a 1.38.0 client keeps working (unknown keys are ignored).
- A `prompt` starting with `/` is still never a prompt. A KNOWN command now runs against the session (the envelope's `sessionId`, else the connection's
  active one) and is answered with a `command_result` frame (envelope `sessionId` = the session), data `{ok, command, message}`. A refused command is
  still an `error` frame.
  - `/model <id>`: the session's next run names that model (same syntax check as a spawn's model); `SessionRecord.model` changes at once. No run starts.
  - `/effort <level>`: the level must be one of the catalog's `values` for the session's provider (case-insensitive), else an `error` listing them and
    nothing changes. New field `SessionRecord.effort` (string or null, default null; a record saved before 1.39.0 decodes to null). The next run
    carries it (`claude --effort`, `pi --thinking`).
  - `/compact`: the harness's OWN `/compact` is handed to it as the session's next turn, resuming the same provider conversation; the daemon summarises
    nothing itself. Refused while a run is in flight, or when the session has never run a turn.
- A command needs an existing session and never starts one. OpenCode lists no commands (no verified flags), so every `/command` there is unknown.

### What changed in 1.38.0 (CP-4, slices a+b) — one frame, `command_catalog`, lists the `/` commands a harness offers

ADDITIVE; a 1.37.0 client keeps working.
- `command_catalog` request, data `{"provider"?: "...", "prefix"?: "..."}` (empty data = defaults). The provider is the named one, else the envelope's
  `sessionId` session's, else the daemon's default. `prefix` is what the user typed after the slash: a bare word filters command names
  (case-insensitive, a leading `/` tolerated); `"effort hi"` names one command and narrows its `values`. The answer is a `command_catalog` frame,
  data `{provider, prefix, commands: [{name, description, action, argument, values, native}]}`, `action` one of `SET_MODEL`, `SET_EFFORT`, `NATIVE`.
  No match is an empty list. An unknown provider or session is an `error` naming it.
- The catalog comes from the harness plugin registered under the provider's id, else from the provider itself (`AgentProvider.commands`).
- **A `prompt` whose text starts with `/` is a command and is never sent to the harness as a prompt.** An unknown command is refused with an
  `error` listing the known `/commands`; a command with no session to act on is refused and starts none. Executing `/model`, `/effort` and
  `/compact` arrived in 1.39.0 (above); in 1.38.0 a known command answered an `error` saying it was not available yet.

### What changed in 1.37.0 (CP-3) — an agent profile belongs to exactly one project

ADDITIVE; a 1.36.0 client keeps working (unknown keys are ignored).
- `AgentProfile` gains optional `projectId`. A NEW profile saved without one is bound to the default project (the oldest); a known profile keeps its project, and naming a different one is refused (`agent_save refused`).
- `agent_list` may carry `{"projectId": "..."}` and then answers only that project's agents; empty data is the unscoped list.
- `new` with a profile takes the profile's project; a `projectId` naming another project is refused. Mail never crosses projects: a recipient bound to another project than the sender's is undeliverable.
- Storage: migration 19 (`agents.project_id`), existing agents backfilled to their latest ticket's project, else the default project.
### What changed in 1.36.0 (CP-2) — a project has a `directory`, and the daemon reads its git tree, changes and history

ADDITIVE; a 1.35.0 client keeps working unchanged (unknown keys are ignored, a daemon that predates this omits `directory`, and the new frames are never sent unasked).

- **`Project.directory`** (frames `project`, `projects`): the absolute path the project lives in, on the DAEMON's machine; `""` = none configured. **`ProjectSave.directory`** (frame `project_save`): `null`/absent keeps the current value (create: none), `""` clears it, anything else sets it. A set value must be an absolute path to a directory that exists when it is saved; otherwise the save is an `error` frame `project_save refused: directory ...` and nothing is written. Persisted (schema v19, migration `18.sqm`, existing rows get `""`).
- **Three request frames, three answers** (all take a project id, so the daemon — never the client — decides which directory is read):
  - `project_git_status` (data = the project id) → `project_git`: `{projectId, directory, available, reason, repoRoot, branch?, head?, changes[], changesTruncated}`. `changes` uses the same neutral `{path, status, staged, fromPath?}` entries as `worktree.changes` (1.35.0), capped at 500 (`changesTruncated`). `branch` is null on a detached HEAD, `head` is null before the first commit.
  - `project_git_diff` (data = `{projectId, path, staged?}`) → `project_git_patch`: `{projectId, path, staged, available, reason, patch, binary, truncated}`. `path` is repository-relative (as `changes` reports it). An untracked file diffs as all additions. `binary = true` carries no patch text. `patch` is cut at 256 000 characters (`truncated`).
  - `project_git_log` (data = `{projectId, limit?}`, default 50, max 200) → `project_git_history`: `{projectId, available, reason, commits[{hash, shortHash, author, timestamp (epoch seconds), subject}], truncated}`, newest first. A repository with no commit answers available with an empty list.
- **Honesty rule, as in 1.35.0:** `available = false` with a `reason` means the read could NOT happen (no directory configured, directory gone, not a git repository, git failed). It is UNKNOWN and must never be rendered as clean; `available = true` with an empty list is known clean/empty.
- **Refusals** are `error` frames `<frame> refused: <why>`: an unknown project id, or a diff `path` that is blank, absolute (including a drive letter or leading slash/backslash), contains a `..` segment or a control character. Any other failure is `<frame> failed: ...`.
- **`repoRoot` may be ABOVE `directory`:** git is run from the repository top level, so a project that lives in a subfolder of a repository sees the whole repository's changes, and every path in `changes` is relative to `repoRoot`.
- Reads are read-only and take no index lock (`--no-optional-locks`).

### What changed in 1.35.0 (GIT VIEW) — the worktree status frame carries the changed files

ADDITIVE; a 1.34.0 client keeps working unchanged (unknown keys are ignored, and a daemon that predates this omits them).

- `WorktreeStatus` (frames `worktree` and `worktrees`) gains `changes`: `null`/absent = UNKNOWN (directory gone, git unreadable, or an older daemon), `[]` = known clean, otherwise one `{path, status, staged, fromPath?}` per changed path. `status` is a neutral enum: `ADDED MODIFIED DELETED RENAMED UNTRACKED CONFLICTED`; nothing in the schema is git-specific. `staged` says which side of the index `status` describes; a path changed on both sides appears once per side. `fromPath` is the old path of a rename. A client must never render `null` as clean.
- `changesTruncated` is `true` when the daemon cut the list at 500 entries.
- Request `worktrees` with a session id in `data` and the daemon answers ONE `worktree` frame for that session (one read instead of one per worktree). An unknown id is an `error`. Empty data is the old behaviour.
- The list is read from the session's own worktree, never the project root. `dirty` is derived from the same read, so the two cannot disagree.

### What changed in 1.34.0 (MODEL + PROVIDER PICKER) — one frame, `models`, listing what every configured provider can run

ADDITIVE. A client sends `models` (empty data) and gets back a `models` frame whose data is a JSON list of
`{provider, group, label, models, note}`. `provider` is an id exactly as the `providers` frame lists it;
`group` is the upstream vendor inside it (opencode fronts many: `anthropic`, `openai`, ...; blank when none);
`models` are the full ids a `new` frame's `model` accepts. The list is read from the configured providers,
never hardcoded. A provider with no models, or whose listing failed, is NOT hidden: it appears with an empty
`models` and a `note` saying why. Picking is the existing `new` frame (`{"provider":..,"model":..}`).

Reply order (BUG-ModelsHeadOfLine, no bump): `models` is answered off the connection's request loop, since
building it may run bounded host listings (`pi --list-models`, `opencode models`, a descriptor's `modelsCommand`;
up to 20 s each). Its reply may therefore arrive AFTER the replies to requests the client sent later on the same
connection. Replies to successive `models` requests on one connection still arrive in the order they were sent.
Every other request keeps its existing reply order.

### CLI note (no wire change, no version bump): ticket, epic and story short ids are SUFFIXES

`tickets`, `ticket create`, `epic create`, `epic show` and the story tables print the LAST 8 characters of a ticket-scheme id (`shortTicketId`), not the first 8: every such id starts with a constant (`ticket-`), so its head carries one random character and two ids collided about 1 time in 16. `ticket show|move|link` and `--epic` resolve an argument as an id SUFFIX (a full id is a suffix of itself); two matches are still refused and listed. Session, message, run, pod and agent ids keep their own display. Nothing on the wire changed.

### What changed in 1.33.0 (IN-APP BUILD) — seven frames to list saved commands and run one, with its output streamed

**Additive (a MINOR bump): seven new frames, no existing frame or rule moved, so a 1.32.1 client never receives one and keeps working unchanged.** Design: `docs/AGENTHQ-IN-APP-BUILD-DESIGN.md` section 3.

- **Frames.** `command_list`, `commands`, `run_start`, `run`, `run_output`, `run_finished`, `run_stop`; constants `Envelope.COMMAND_LIST`, `COMMANDS`, `RUN_START`, `RUN`, `RUN_OUTPUT`, `RUN_FINISHED`, `RUN_STOP`. Each has a row in the frame tables.
- **No argv on the wire.** `commands` carries `{id, name, cwd}` only: a client learns what it can run, never how. `run_start` names a command by id.
- **Stop is a kill, not an interrupt.** Unlike `interrupt`, nothing is kept: the outcome is `KILLED_GRACEFUL` or `KILLED_FORCED`.
- **Exactly one `run_finished` per run**, including after a stop and after a spawn failure (`FAILED`, `exitCode: null`, reason in `detail`). `exitCode` is `null` only when no process ran or the kill took the code away.
- **Line handling.** Lines over 8 KB are cut and sent with `truncated: true`; the carriage return counts as a line break as well as the newline. At most 10,000 `run_output` lines per run, then one `[output truncated]` line and the rest dropped; `run_finished` still follows.
- **Errors** use the existing `error` frame: unknown `commandId`, a cwd outside the repo, a second `run_start` while one is in flight.
- **Status: documented, not yet implemented.** The daemon answers none of these frames until the follow-up tickets (T2 onward) land.

### What changed in 1.32.1 (INTERRUPT) — the interrupt frame: stop a session's current turn without ending the session

**Additive (a patch on 1.32.0): one new client → daemon frame, `interrupt`. No existing frame or rule moved, so a 1.32.0 client keeps working unchanged.**

- **Frame.** `interrupt`, with `data` = the session id (a bare string, trimmed by the daemon). The constant is `Envelope.INTERRUPT`. Its name is spelled `interrupt`, not `interrupted`: `INTERRUPTED` is the *pod* state after a daemon restart and is unrelated to this frame.
- **Semantics: it ends the CURRENT turn and KEEPS the session.** The session's run is stopped (its whole process tree is killed, not sent a graceful stdin close) and the session returns to rest (`IDLE`) with the same session id, the same provider conversation and the same transcript. The next `resume` continues that conversation. This is the difference from `kill`, which ends the session. A reader cannot guess this from the name, so it is written here.
- **Deliberate omission: an interrupted turn emits NO `finished` frame.** A `finished` carries the run's exit outcome, and an interrupt has no exit code, so a `finished` with a null exit would read as FAILED. An interrupt is not a run outcome. Clients learn that the turn is over from the `session` frame that returns the session to `IDLE` (its `lastExitCode` is `null`), and the transcript gets a visible line saying the turn was interrupted. Do not file a bug about the missing `finished` frame; it is the contract.
- **Refusals.** An empty `data` answers `error` (`interrupt expects '<sessionId>'`). A session with no run in flight answers `error` naming its state (`nothing to interrupt`). Interrupting a turn that is already being interrupted is accepted without a second effect.

### What changed in 1.32.0 (BRANCH-PER-TASK 2)

**1.32.0 is additive: the `ticket` and `ticket_get` frames carry `branch` and `base`, the ticket's own branch and the commit it was cut from.**

### What changed in 1.31.0 — the contract bump for the ROSTER / MAIL / AGENT-CLI / CLI-T series: tickets file under an epic, and `agent_roster` lists a project's agents

**Additive (a MINOR bump, allocated by the PM at the series' acceptance).** No frame or rule moved, so a
1.30.0 client keeps working unchanged. The series' slices did not bump on their own; this version covers all
of them. Three things a client can observe:

- **`Ticket.epicId` and `TicketSave.epicId` (CLI-T3 patch).** `Ticket` gains `epicId`: the full id of the epic
  the ticket is filed under, or `null`. Every field of a `Ticket` is always present, so an unfiled ticket
  carries `"epicId": null`. `TicketSave` gains an optional `epicId`, which a create files the ticket under.
  On an edit a blank or absent `epicId` keeps the epic the ticket already has, the same rule `projectId`
  follows. The daemon echoes the id it is given and does not check that the epic exists.
- **`agent_roster` and `roster` (ROSTER-2).** `agent_roster` carries `{"projectId": "<id>"}` and is answered by
  a `roster` frame: a JSON list of `RosterRow`, one per agent of that project and never another project's. The
  answer goes only to the client that asked. A blank `projectId` is refused as `agent_roster refused: projectId
  is blank`. Each `RosterRow` is `{ agentId, name, role, status, sessionId, title, pending }`, where `status` is
  `BUSY` (a run is in flight), `IDLE` (at rest, and a queued mail can wake it) or `OFFLINE` (no session, or one
  that cannot come back). `sessionId` and `title` are `null` when there is none; `pending` counts the mail
  queued for the agent or for its current session. An agent belongs to a project when a ticket of that project is
  assigned to it, or a live session is tagged with that project and an agent id. This frame was implemented
  before 1.30.0 and was not written down until now.
- **The `agenthq` CLI surfaces (CLI-T series).** `epic show`, `epic create`, the `EPIC:` line of `ticket show`,
  and `ticket create` / `move` / `link` are client-side commands on the frames above. They add no frame.

### What changed in 1.30.0 (BIND) — a profile-bound session is bound at `new`, and `SpawnOptions` carries `projectId`

**Additive (one optional field).** `SpawnOptions` (section 2.2.1) gains `projectId`, the project the
console belongs to; the daemon records it on the session, so the roster and role-based mail can scope
by project. Absent or null keeps the old behaviour. Every payload decodes with `ignoreUnknownKeys`, so
a client written against 1.29.0 is unaffected.

**Behavioural (this is the part a client author must not miss).** A `new` that names an agent profile
now creates **the** session immediately, bound to that profile's id, role, project and persona, instead
of creating one at the first prompt. So:

- The sentence "the next prompt starts a fresh session" (section 2, the `new` row) is **no longer true
  for a profile-bound `new`**. It still holds for a `new` that names no profile.
- "A `new` that names a profile spawns sessions from it" (section 2.2.2) is now singular: it spawns
  **the** session, at `new`, and later prompts run that session rather than starting another.
- Each bound console is given its own worktree when the console **opens**, not at its first prompt.

`SessionRecord` carries `projectId` beside the `agentId` and `role` it already gained in 1.4.0; all
three are null on any record an earlier daemon wrote.

### What changed in 1.29.0 (EPIC-6) — stories hang off epics, `Project.storyIds` retired

**Breaking (field removal).** `Project` no longer carries `storyIds`; `project` / `projects` frames
are `{id, name, goal}`. The daemon no longer appends a created story's id onto its project — only onto
its epic (`Epic.storyIds`) when the `story_save` create names an `epicId`. Nothing read the project
list (`story_list` orders by creation; the lead brief never used it), so a client that ignored the
field sees no change. The `projects.story_ids` DB column is left in place, unwritten (the SQLite
dialect cannot `DROP COLUMN`); no schema migration.

### Unreleased (AGENT-UX-11) — delete a session's durable record

**Additive: a new client request frame, `session_delete`.** The version number is **PM-allocated** and
is **1.28.0** (the next after 1.27.0, AGENT-UX-10, merged as `4d34ae68`), pinned in
`EventVocabulary.VERSION`.

- `session_delete` carries a plain `sessionId` string as its payload. On success there is no direct
  reply to the requester: the daemon removes the session's `SessionRecord` (in-memory and, on the
  next write, its row in `sessions.json`) and pushes a freshly computed `sessions` frame to **every**
  connected client, exactly like any other session-list mutation — that push is what tells every
  client the row is gone.
- Refused, without touching anything: an unknown session id, or a session that is currently `live`
  (a run in flight, per the `live` field AGENT-UX-4 added in 1.24.0). Both answer `error` with
  `"session_delete refused: <reason>"`.
- Deliberately out of scope: the git worktree on disk (`worktree_remove`'s own command) and the
  provider CLI's own conversation store (the CLI's data, never the daemon's). There is no separate
  on-disk transcript/history file kept per session on the daemon side to begin with, so nothing else
  is retained or deleted beyond the `sessions.json` row itself.
- No existing frame or field changed meaning or shape. A pre-1.28.0 client that never sends
  `session_delete` sees no change.

### What changed in 1.23.0

**1.23.0 is additive (THINK-1): the model's reasoning is its own frame.** The daemon's `ready` frame
now reports `contract=1.23.0` (it was `1.22.0` in the previous release of this document). Nothing
else changed.

- A new session-stream frame `reasoning` carries the model's own chain of thought as a plain string,
  exactly like `text_delta` carries the answer. It is sequenced and replayable on the session stream
  like every other rendered event, so a reconnecting client rebuilds it with `replay_request`.
- It is produced from two provider shapes: the opencode `reasoning` part (`{"type":"reasoning",
  "text":"…"}` or `{"type":"reasoning","part":{"text":"…"}}`) and claude's `assistant` content block
  of type `thinking` (`{"type":"thinking","thinking":"…"}`). A claude `text` block keeps its existing
  behaviour; a `thinking` block is no longer dropped.
- A UI can therefore render thinking apart from the answer — collapsed under a "Thinking" affordance,
  for instance — instead of leaking it into the transcript as the assistant speaking.

### What changed in 1.22.0

**1.22.0 is additive (STORY-EPIC-1): `story_save` may name an epic to join** (it was `1.21.0`). The
daemon's `ready` frame now reports `contract=1.22.0` (it was `1.21.0` in the previous release of this
document). Nothing else changed.

- `StorySave` gains an optional `epicId`. On a CREATE (no `id`) the new story is appended to that
  epic's ordered `storyIds` as well as to its project's — see §2.2.5. An `epicId` that names no epic
  is a no-op, never a refusal. An edit (an `id` is present) ignores `epicId`.
- When the append changes an epic, the daemon pushes the updated epic as an `epic` frame to every
  connected client, the client whose `story_save` caused it included — the same frame an `epic_save`
  answers with, so a UI's epics list refreshes without a re-fetch.

### What changed in 1.21.0

**1.21.0 is additive (MEM-8): project-memory notes are written and listed** (it was `1.20.0`). The
daemon's `ready` frame now reports `contract=1.21.0` (it was `1.20.0` in the previous release of this
document). Nothing else changed.

- `memory_save` (`{"projectId","name","description","body"}`) writes a note inside a project and is
  answered by a `memory_note` frame: the stored note (`{id, projectId, name, folder, description, body,
  updatedAt}`). A note of the same project and name is updated in place rather than duplicated. A blank
  or over-long name, or a malformed payload, is refused: `memory_save refused: ...`.
- `memory_list` (raw project id as payload, like `epic_list`) is answered by a `memory_notes` frame: the
  project's notes as `{name, description}`, in name order; a project with none, or another project,
  lists `[]`. The body is deliberately withheld -- it is what a reader fetches after the index.
- Memory notes are stored in the daemon database with the same durability as projects and epics.

### What changed in 1.20.0

**1.20.0 is additive (MEM-5): project-memory search** (it was `1.19.0`). The daemon's `ready` frame
now reports `contract=1.20.0` (it was `1.18.0` in the previous release of this document). Nothing else
changed.

- `memory_search` (`{"projectId","query"}`) searches the project's memory notes and is answered by a
  `memory_hits` frame: the ranked hits, each `{name, description, snippet}`. A note matches when its
  name, description or body contains the query (case-insensitive); a name match outranks a
  description match, which outranks a body-only match. `snippet` is the fragment of the body around
  the match. A blank query, or a project with no matching note, answers `[]`.
- A malformed payload is refused: `memory_search refused: ...`.

### What changed in 1.19.0

**1.19.0 is additive (EPIC-4): epic frames.** The daemon's `ready` frame reported `contract=1.19.0`
at this version (it was `1.18.0`). Nothing else changed.

- `epic_save` (`{"projectId","name","goal"?}`) creates an epic inside a project and is answered by an
  `epic` frame (`{id, projectId, name, goal, storyIds}`). An unknown key is refused:
  `epic_save refused: ...`.
- `epic_list` (raw project id as payload, like `project_get`) is answered by an `epics` frame: the
  project's epics in creation order; a project with none, or another project, lists `[]`.
- **There is NO all-projects epic listing, and this is not symmetric with `story_list`.** `epic_list`
  filters on an EXACT project match, so an empty payload matches nothing and answers `[]` — it does
  not mean "every project". `story_list` (§2.2.5) with an empty payload DOES mean every project's
  board. A client must therefore always send an explicit project id to `epic_list`, and must not
  infer one behaviour from the other. The `agenthq epics` CLI command refuses a missing project id
  as a usage error for exactly this reason (AGENT-CLI-2).
- **An `epic` frame can arrive UNSOLICITED.** A `story_save` whose create names an `epicId` makes the
  daemon append the story id to that epic and push the changed `epic` to EVERY connected client
  (contract 1.22.0). So a socket waiting for an `epics` answer may be handed an `epic` frame it never
  asked for: match the reply on the `epics` frame TYPE, never on "an epic-ish frame".
- Epics are stored in the daemon database with the same durability as projects; `storyIds` is the
  epic's ordered story list (empty on creation).

### What changed in 1.18.0

**1.18.0 is additive (PW-5): workspace frames.** The daemon's `ready` frame now reports `contract=1.18.0`
(it was `1.17.0`). Nothing else changed.

- `workspace_save` (`{"projectId","name","path"?}`) creates a workspace inside a project and is answered by
  a `workspace` frame (`{id, projectId, name, path}`). An unknown project, a blank name or an unknown key is
  refused: `workspace_save refused: ...`.
- `workspace_list` (raw project id as payload, like `project_get`) is answered by a `workspaces` frame: the
  project's workspaces in creation order; a project with none, or another project, lists `[]`.
- Workspaces are stored in the daemon database with the same durability as projects.

### What changed in 1.17.0

**1.17.0 is additive (P9.5 H13b/H13c): `TriggerView` echoes `intervalMs`.** The daemon's `ready` frame now
reports `contract=1.17.0` (it was `1.16.0`). Nothing else changed.

- `TriggerView.intervalMs` (`Long?`, default `null`): echoed on `TriggerView` (`trigger` / `triggers`, and
  the value returned by `trigger_save`) so a client can show and edit a schedule's period. It is the stored
  period for a `schedule` trigger and `null` otherwise. Additive: an older client ignores the field.

### What changed in 1.16.0

**1.16.0 is additive (P9.5 H8): one optional field on `TriggerSave`.** The daemon's `ready` frame now
reports `contract=1.16.0` (it was `1.15.0`). Nothing else changed.

- `TriggerSave.intervalMs` (`Long?`, default `null`, must be `>= 60000` (one minute) when given; the floor was `1000` before the owner's 2026-10-23 change, the wire unit is still ms and rows already stored keep their period; a smaller value is refused with "a schedule interval must be at least 1 minute"): the period of a
  `schedule` trigger. Each elapsed interval fires the trigger once, recorded as a `trigger_events` row
  with `source: "schedule"`, disposition `started` (or whatever its guard decides, as for a manual fire).
  A `schedule` trigger saved without `intervalMs` never fires.
- A clock-driven `schedule` fire goes through the same guard, ceiling and trip rules as a manual fire.

### What changed in 1.15.0

**1.15.0 is additive: the contract version bump only (P9.5d, H2).** No frame, field or rule changed.
The daemon's `ready` frame, sent on connect, now reports `contract=1.15.0` (it was `1.14.0`). A 1.14.0
client keeps working unchanged; it only sees a newer version string.

### What changed in 1.14.0

**1.14.0 is additive: manual triggers are stored and served (story 9.5 wiring).**

| Request (`data`) | Answer |
| --- | --- |
| `trigger_save` (`TriggerSave`, strict JSON) | `trigger`, pushed to every client |
| `trigger_list` | `triggers` (oldest first) |
| `trigger_fire` (`"<triggerId>"`) | `trigger` to the sender (and pushed when its state changed) |
| `trigger_arm` / `trigger_disable` (`"<triggerId>"`) | `trigger`, pushed to every client; since 1.105.0 `trigger_arm` also enables a `disabled` trigger |
| `trigger_delete` (`"<triggerId>"`) | `triggers`, pushed to every client |
| `trigger_events` (`"<triggerId>"`) | `trigger_events`: that trigger's `TriggerEventView` rows, oldest first |

- `TriggerSave`: `{id?, name, kind:"manual", enabled:true, coalesceMs:120000, ceilingRuns:6,
  ceilingWindowMs:3600000, action:{kind:"start_next", agentId?}}`. Served: kinds `manual`, `schedule`
  and `ship`, action `start_next` (hand out the top startable todo ticket, optionally only one assigned
  to `agentId`, a profile id or `role:<role>`) and, since 1.41.0, `prompt` (see "What changed in 1.41.0"). Since
  1.42.0 the `webhook` kind is served too (see "What changed in 1.42.0"). The `pod` action is
  specified (PHASE9-SPEC §4) but refused until served: `trigger_save refused: … is not served yet`. The
  `schedule` kind fires every optional `intervalMs` (added in 1.16.0, see above), and the `ship` kind
  fires when a ticket enters `done`, once per ticket id.
- `TriggerView`: `{id, name, kind, action, enabled, state, coalesceMs, ceilingRuns, ceilingWindowMs,
  createdAt, trippedAt?, trippedReason?, intervalMs?}`; `state` is `armed`, `running`, `tripped` or `disabled`.
  `intervalMs` was added to the trigger in 1.16.0 (`TriggerSave`) and echoed here in 1.17.0 (see above).
- Rules: one run in flight; events inside the fixed window fold into one run; events mid-run fold into one
  follow-up that runs only after a success; past the hourly ceiling an event is refused and the trigger
  trips. ANY non-success (failed, killed, refused start) trips the trigger: a `trigger_tripped` attention
  item is raised, later events are recorded `suppressed_tripped`, nothing is retried, and only
  `trigger_arm` re-arms it. An empty board is `nothing_to_start`, never a trip. A trigger's start is
  unattended, so the launch gate applies (`refused_launch_gate`).
- Every event gets a durable `TriggerEventView` row `{eventId, triggerId, receivedAt, source, disposition,
  deliveryId?, digest?}`; the last 500 per trigger are kept. Dispositions are lower snake case (§ Trigger
  domain contract).
- Restart: a trigger that was `running` when the daemon stopped comes back `tripped` ("daemon restarted
  mid-run") with an attention item; nothing starts on boot.
- `GET /health` adds `triggersTripped`.

### What changed in 1.13.0

**1.13.0 is additive: observed usage per run, observed limit state per provider, the operator budget and
the launch gate (story 9.7b) are served.**

- `usage` (daemon → client, session-tagged and **sequenced** on the session stream, so replay includes it):
  `data` is a `RunUsage` JSON object, sent when a run whose stream reported usage ends. A run that
  reported no usage sends **no** `usage` frame: no signal is never zeros. `finished` is unchanged.
- `provider_state` (daemon → client, pushed to every client): `data` is a `ProviderState`, sent when a
  provider's observed limit state changes (a `rate_limit_event` on the stream, or an unattended run of that
  provider stalling).
- `provider_states` (client → daemon, empty `data`) is answered by `provider_states`: a list with the latest
  `ProviderState` of every provider this daemon serves. A provider never observed is
  `{"provider":"…","status":"unknown","utilization":null,…,"source":"none"}` — never 0%.
- The launch gate: an **unattended** start (a drained capacity queue; triggers when they land) is refused
  when the provider is `exhausted` or its operator budget is spent — the ticket start answers
  `start_refused` with `launch gate refused: …`, and a `launch_blocked` attention item is raised. A
  **human** start (a `prompt` that starts or continues a session, `ticket_move`, `ticket_start_next`) is
  warned and still runs: the requesting client gets `error` `launch warning: …; the run starts anyway`, and
  a ticket start's `start.detail` carries `launch warning: …` (after the `cut from <ref> <sha>` note, when the
  start cut a branch: BUG-REGRESS-1, see `start` in section 2.2.4).
- `GET /health` adds `providerStatus`: each served provider's status (`unknown`, `stalled` or `exhausted`).
- Nothing older moved.

### What changed in 1.12.0

**1.12.0 is additive: the role catalog is on the wire.**

- `role_list` (client → daemon, empty `data`) is answered by `roles`: every role of the daemon's catalog,
  the canonical role ids first in contract order, then any other id sorted (§2.2.6).
- `role_save` (client → daemon, a `RoleSave` JSON object) writes a custom role definition and pushes the
  stored `role` to **every** client, the sender included — for the sender, that push is the answer. A
  refusal is `error` (`role_save refused: …`) and nothing is written or pushed.
- Nothing older moved. Agent profiles keep their own `instructions`; a role's `persona` is not applied
  to a spawn by the daemon (a client may offer it as a profile's starting instructions).

### What changed in 1.11.0

**1.11.0 is additive: both sides of a conversation are on the session stream, and `ready` names the daemon process.**

- New session-stream frame `turn` (daemon → client): `data` is the prompt a run was started with, exactly as
  the provider received it (a persona's instructions or a ticket's composed context included). It is
  sequenced like `text_delta` and is emitted when the run is accepted — before the provider is spawned — so
  it precedes every frame that run produces. `replay_request` therefore rebuilds the whole conversation,
  and every connected client sees what was asked, not only what was answered. A run that cannot start
  still shows its `turn`, followed by the failure.
- Talking to an agent is one session continued turn after turn: send `prompt` with the session's id in
  the envelope `sessionId` and the daemon continues that session's provider conversation (§2.2). A turn is
  refused with `error` while the session has a run in flight; nothing is queued.
- `ready` now reads `provider=<id> contract=<version> instance=<id> since=<epoch millis>`. `instance`
  identifies the daemon *process*: sequence numbers restart with it, so a replay cursor is valid only
  against the instance that issued it. `since` is when that process's replay history begins: a session
  whose `lastHeartbeat` is older than `since` has nothing to replay. Parse `ready` as space-separated
  `key=value` pairs and ignore keys you do not know.
- Nothing older moved: no existing frame, field or meaning changed.

### What changed in 1.10.0

**1.10.0 is additive: manual triggers coalesce events and expose their run state.**

- A manual trigger has at most one run in flight. The default fixed coalescing window is 120,000 ms;
  events during the window are folded into one run summary containing the event count and first/last ids.
- Events received during a run are folded into one follow-up slot, regardless of count. The follow-up
  opens a fresh fixed window after success and is dropped after failure. No further run is queued.
- `coalesceMs: 0` starts an idle trigger immediately while preserving the one-in-flight/follow-up rule.
- Trigger frames are new additive wire surfaces; clients that do not use them are unaffected.

### Trigger domain contract (Story 9.5c)

The trigger domain model defines schedule catch-up, ship triggers and complete event dispositions.
Daemon wire routing and durable trigger persistence are implemented in later integration slices.

- Schedule events use the existing trigger coalescing policy. Downtime catch-up folds elapsed
  schedule slots into one event summary; with `catchUp: false`, slots older than ten minutes are
  retained as `missed` dispositions.
- A ship trigger accepts a ticket entering `done` once per ticket id. Won't-fix tickets are recorded
  as `wont_fix` and do not start a run. Manual replay can run the last shipped ticket again.
- Trigger event records carry source, optional delivery id and digest, receipt time, and disposition.
  Dispositions include `started`, `folded`,
  `followup_folded`, `dropped_after_failure`, `suppressed_tripped`, `refused_ceiling`, `duplicate`,
  `missed`, `nothing_to_start`, `refused_launch_gate`, and `wont_fix`.
- Trigger event history is independent from run history. Durable storage and retention policy belong
  to the daemon integration slice. These domain rows do not add daemon frames and did not change the
  wire version.

### Usage and limit-state contract (Story 9.7b, served since 1.13.0)

| Frame | `data` |
| --- | --- |
| push `usage` (session-tagged, sequenced) | `RunUsage`: `{runId, provider, model, input, output, reasoning, cacheRead, cacheWrite, costUsd?}` |
| push `provider_state` | `ProviderState`: `{provider, status, window?, utilization?, resetsAt?, observedAt?, source}` |
| `provider_states` → `provider_states` | list of the latest `ProviderState` per served provider |

- `runId` is `<sessionId>:<runCount>`. `model` is the session's model, else the provider's configured
  default, else `default`. Token counts sum every `step_finish` of the run (OpenCode); `costUsd` is null
  when the provider reported none.
- `status` is `unknown`, `stalled` or `exhausted`. A provider with no signal is `unknown` with null
  `utilization`: never 0%. `exhausted` is declared only by an explicit classifier built from captured
  samples; none exists yet, so a `rate_limit_event` is recorded as `unknown` with its fields
  (`window` = `rateLimitType`). A stalled unattended run makes its provider `stalled`, never `exhausted`.
- The operator budget (runs and tokens per UTC day, per provider; `AGENTHQ_BUDGET_RUNS_PER_DAY`,
  `AGENTHQ_BUDGET_TOKENS_PER_DAY`, unset = no limit) is called "budget", never "quota". A run that
  reported no usage counts as a run with no tokens. Today's totals are recounted from stored runs after a
  restart.
- Launch gate: an unattended start is refused when the provider is `exhausted` or its budget is spent.
  It raises a `launch_blocked` attention item. A human start gets a warning and still runs. Account usage
  bars are not part of this contract.

Read this file and you can build the UI. You do **not** need to read `ProcessRunner`, any provider
class, `SessionManager`, `Mailbox` or the orchestrator — those are implementation, and they are deliberately not part
of the contract.

### What changed in 1.8.0

**1.8.0 is additive: the daemon now exposes the MVP project and story board surfaces.**

- Projects use `project_save`, `project_get` and `project_list`, answered by `project` and `projects`.
- Stories use `story_save`, `story_get`, `story_move` and `story_list`, answered by `story` and `stories`. DEPRECATED in 1.62.0: they are shims over the ticket board (see "What changed in 1.62.0").
- Tickets gain an optional `projectId`. The project owns its ordered `storyIds`; stories remain in their
  own store and do not gain a position or timestamps.
- See section 2.2.5 for the complete shapes and semantics.

### What changed in 1.9.0

**1.9.0 is additive: the daemon stores attention history and shares updates with every client.**

- `attention_list` returns the oldest-first history in `attention_items`; `GET /attention` returns the same JSON list.
- `attention_ack` carries an attention id as plain text. The daemon timestamps the acknowledgement and pushes the updated `attention` item to every connected client; an unknown id answers `error`.
- New `attention` records carry `id`, Unix-millisecond `at`, `kind`, optional `triggerId` / `ticketId` / `podId` / `sessionId`, `outcome`, `detail` and nullable `ackedAt`. Kinds are lower snake case: `trigger_tripped`, `deadline`, `stalled`, `ceiling`, `launch_blocked`, `prerequisite_declined`, `queue_cleared` and `unreported` (1.52.0).
- `GET /health` adds `attentionOpen`, the count of unacknowledged records. Existing frames and fields retain their meanings.

### What changed in 1.27.0

**1.27.0 is additive (AGENT-UX-10): the `providers` frame carries each provider's model list.** The
daemon's `ready` frame now reports `contract=1.27.0` (it was `1.26.0` in the previous release of this
document). Nothing else changed.

- `ProviderInfo` (one entry of the `providers` answer) gains an optional `models` array: the model ids
  the provider itself reports, read through AGENT-UX-7's canonical `knownModels()` seam (`opencode models`
  for the `opencode` provider). `null` means the provider cannot list models, so a picker offers only
  `defaultModel`. A client renders the ids verbatim; it maintains no list of its own.
- `ProviderInfo` also gains an optional `modelsStale` boolean, currently always `false`: the canonical
  seam exposes no staleness signal yet, so a client must not read it as proof the list is fresh.
- Both fields are unknown to a 1.26.0 client and are ignored by it (`ignoreUnknownKeys`), so its frames
  are unchanged.
- *Clarified by CP-1, no wire change and no version bump:* the lists now come from the user's own CLI
  configuration files, not from a built-in list. `opencode` reads `opencode.json[c]`, `auth.json` and
  opencode's cached models.dev catalogue (falling back to `opencode models` only when those name no
  model); `claude` reads `settings.json` (`model`, `availableModels`, `modelOverrides`, `modelSettings`,
  the model env pins) on top of the CLI's own aliases. A provider that does not validate models
  (`knownModels()` null, e.g. `claude`) still fills `models` with what it OFFERS (`models()`), so the
  profile picker shows it. A broken config file is reported in the `models` answer's `note`, never
  silently dropped.

### Unreleased (AGENT-UX-12) — the `status` frame carries the running tool's ARGUMENT

**Additive: `StatusPayload` gains an optional `activity` field**, the tool call's salient argument (the
path being searched, the command being run). No frame moved and no field changed meaning. The version
number is **PM-allocated** and is **1.26.0** (the next after 1.25.0), pinned in `EventVocabulary.VERSION`.

- `activity` is a short, **redacted** line produced by the daemon from a narrow allow-list of tool-
  argument keys (`command`, `file_path` / `filePath` / `path`, `pattern`, `url`, `query`), clamped to
  100 characters. It never carries file CONTENTS or the environment. A tool call with no salient
  argument carries `tool` alone; a tool call with neither carries nothing.
- It is produced from the same provider events as `tool` (§2.6): opencode's `tool` part (`part.state.input`)
  and claude's `tool_use` block (`input`).
- It is **omitted from the JSON when absent** (`@EncodeDefault(NEVER)`), so every existing `status`
  frame is byte-for-byte unchanged and a 1.25.0 client decodes an activity-bearing `status` unchanged.

### What changed in 1.25.0

**1.25.0 is additive (AGENT-UX-9): the `status` frame carries the name of the tool a run is calling.** The daemon's `ready` frame now reports `contract=1.25.0` (it was `1.24.0` in the previous release of this document). Nothing else changed.

- `StatusPayload` gains an optional `tool` field: the tool's name (`Read`, `Bash`, …), present only when the status is about a tool call. This is the one field that lets a UI show "Running read" instead of a bare spinner.
- It is produced from a provider's tool event: opencode's `tool` part (`part.tool`) and claude's `tool_use` block (`name`) — see §2.6.
- It is **omitted from the JSON when absent**, so every existing `status` frame is byte-for-byte what 1.24.0 published; a 1.24.0 client decodes a tool-bearing `status` unchanged (`ignoreUnknownKeys`).

### What changed in 1.24.0

**1.24.0 is additive: `SessionRecord` gains `isProcessAlive` and `live` fields for accurate liveness reporting.**

- `SessionRecord.isProcessAlive` (boolean, default false) records whether the child process was observed alive the last time the daemon probed it (heartbeat, reconciliation, kill).
- `SessionRecord.live` (computed boolean) is true when the session is in a running state (STARTING/BUSY/WAITING_INPUT) OR when `childPid != null && isProcessAlive == true`. This lets a client distinguish truly live sessions from idle/exited history records.
- A 1.23.0 client works unchanged: it ignores unknown fields, and the new fields are optional with defaults.

### What changed in 1.23.0

**1.23.0 is additive: `SessionRecord` gains `attentionId` and `attentionAt` fields; daemon publishes `attention_list` and `attention` frames; `replay_request` accepts an optional `attentionId` filter; `MailRouter` routes attention signals via the mailbox.**

- `SessionRecord.attentionId` and `attentionAt` track attention history per session.
- `attention_list` / `attention` frames expose attention history.
- `replay_request` accepts optional `attentionId` filter.
- A 1.22.0 client works unchanged.

### What changed in 1.22.0

**1.22.0 is additive: new `epic_save` / `epic_get` / `epic_list` / `epic_delete` requests answered by `epic` / `epics`; `StorySave` gains `epicId`; `Story` gains `epicId` and `epicAt`.**

- `epic` / `epics` frames for epic CRUD.
- `Story.epicId` and `Story.epicAt` link stories to epics.
- A 1.21.0 client works unchanged.

### What changed in 1.21.0

**1.21.0 is additive: new `role_save` / `role_list` / `role_delete` requests answered by `role` / `roles`; `AgentProfile` gains `customRoleFile`; daemon serves bundled manifest via `role_catalog`.**

- `role` / `roles` frames for role catalog CRUD.
- `AgentProfile.customRoleFile` for custom role files.
- `role_catalog` frame for bundled manifest.
- A 1.20.0 client works unchanged.

### What changed in 1.20.0

**1.20.0 is additive: new `ticket_save` / `ticket_get` / `ticket_list` / `ticket_move` / `ticket_link` / `ticket_unlink` / `ticket_reorder` / `ticket_delete` requests, answered by `ticket` / `tickets`, which are also pushed to every client when the board changes, a 1.19.0 client included when another client edits the backlog.**

- `ticket` / `tickets` frames for backlog CRUD.
- Board changes pushed to all clients.
- A 1.19.0 client works unchanged.

### What changed in 1.19.0

**1.19.0 is additive: new `agent_save` / `agent_list` / `agent_delete` requests answered by `agent` / `agents`; `SpawnOptions.agentId` spawns from a profile; `SessionRecord.agentId` and `.role` are new fields.**

- `agent` / `agents` frames for agent profile CRUD.
- `SpawnOptions.agentId` for profile-based spawns.
- `SessionRecord.agentId` and `.role` for attribution.
- A 1.18.0 client works unchanged.

### What changed in 1.18.0

**1.18.0 is additive: a `new` frame may carry `SpawnOptions` (provider and model) for the sessions a connection creates; a new `providers` request lists what the daemon can spawn; `SessionRecord.model` and `/health.providers` are new fields.**

- `SpawnOptions` in `new` frame.
- `providers` request/response.
- `SessionRecord.model` and `/health.providers`.
- A 1.17.0 client works unchanged.

### What changed in 1.17.0

**1.17.0 is additive on the frames. Client frames (`prompt`, `mail_send`, `mail_inbox`, `mail_outbox`) now honour an optional `sessionId`: a client that names its session continues or addresses that session, so a reconnecting client resumes without holding state of its own.**

- `sessionId` in client frames for explicit routing.
- Bearer-token requirement for remote use.
- A 1.16.0 client works unchanged.

### What changed in 1.16.0

**1.16.0 is additive and changed nothing that 1.15.0 froze. It adds the pod command frames ([POD_SUBMIT], [POD_QUERY], [POD_CANCEL]) and their single answer frame ([POD]), and two counts on `/health`. The four event frames below are byte-for-byte what 1.15.0 published: same names, same payload types, same meaning.**

- Pod command surface (POD_SUBMIT, POD_QUERY, POD_CANCEL, POD).
- `/health` gains `pods` and `runsInFlight` counts.
- A 1.15.0 client works unchanged.

### What changed in 1.7.0

**1.7.0 is additive: session-stream frames are sequenced, and retained output can be replayed after a socket reconnect.**

- `Envelope.sequence` numbers session-stream frames per session.
- `replay_request` retrieves retained frames after an exclusive sequence cursor; `replay_gap` explicitly reports evicted frames.
- The daemon keeps at most 256 frames per session. This buffer is volatile: replay is available only while the same daemon process retains it. A daemon restart clears replay history; restart-persistent output replay is not part of this contract.
- A client must not resubmit the original `submit` after reconnect or a no-replay-history response; the original request may already have created the pod.
- See sections 7.6 and 8 for the complete wire behavior and finding.

### What changed in 1.6.0

**1.6.0 is additive: tickets can be handed to agent profiles, and a started session receives controlled context.**

- `ticket_move` to `in_progress` and the new `ticket_start_next` request use the same daemon hand-out path. The answer is `ticket` with a `start` outcome; a busy profile may queue the ticket in `todo`.
- New requests `ticket_start_next`, `settings_get` and `settings_set`; `ticket_start_next` answers with `ticket`, and both settings requests answer with `settings`.
- `Ticket` gains `agentId`, `provider`, `model`, `useWorktree`, `worktreeBranch`, `includeLinkedTasksContext`, `queuedFor`, `queuedAt`, `sessionId`, `executorOverrides`, `effectiveExecutor`, `effectiveContext`, `lastRun` and `start`.
- `SessionRecord` gains `ticketId` and `contextSources`. A profile's `instructions`, the ticket core and linked-ticket lines are the only context sources; linked ticket bodies are never included.
- The one project setting is `context.includeLinkedTasksContext`, default true and nullable per-ticket override. See section 2.2.4.
- Nothing older moved: no existing frame, field or meaning changed. A client that ignores fields and frames it does not know sees no difference.

### F2 replay (published in 1.7.0)

The F2 server and client slices are integrated. These wire shapes are part of the published 1.7.0
contract.

- **Per-session sequence** (`Envelope.sequence`, server slice; consumed by the client slice): every
  frame emitted from a live session event carries a positive integer `sequence`. It starts at `1` for
  each session and increases by exactly one for each frame in that session's stream, in emission order.
  The stream types are `session`, `status`, `stdout`, `stderr`, `exit`, `text_delta`, `error` and
  `finished` (and, since 1.11.0, `turn`; since 1.13.0, `usage`; since 1.23.0, `reasoning`). Initial `sessions` snapshots and non-session control/push frames are not sequenced.
- **Replay request** (`replay_request`, client and server slices): the client sends the session id in
  the envelope's `sessionId`, with `data` set to a JSON `SessionReplayRequest`, for example
  `{"lastSequence": 17}`. `lastSequence` is an exclusive cursor: only frames with a greater sequence
  may be replayed.
- **Replay response** (server slice): the daemon sends the original sequenced stream envelopes, not a
  wrapper, in ascending sequence order. Their `type`, `data`, `sessionId` and `sequence` retain their
  original values. If no frame follows the cursor, no stream frame is returned.
- **Replay gap** (`replay_gap`, server slice): when the requested cursor is older than the retained
  buffer, the daemon first sends a distinct `replay_gap` frame for that session, with JSON data shaped
  as `SessionReplayGap`: `requestedAfterSequence`, `oldestAvailableSequence`,
  `latestAvailableSequence` and cumulative `evictedCount`. The daemon then sends the retained frames
  in order. A gap is always reported; it is never represented by a silent skip.
- **Bounded replay buffer** (`SESSION_REPLAY_BUFFER_CAPACITY = 256`, server slice): retain at most
  256 stream frames per session, evicting the oldest first. `evictedCount` counts every evicted frame
  since that session's stream began. The replay buffer is volatile and does not alter the durable
  session, mail or pod stores. A daemon restart clears it; reconnect replay does not survive process
  restarts. A replay request after restart may receive a no-replay-history error and no stream frames.

### What changed in 1.5.0

**1.5.0 is additive: the daemon owns a backlog of tickets, and every change to it is pushed to every client.**

- Eight new requests: `ticket_save`, `ticket_get`, `ticket_list`, `ticket_move`, `ticket_link`, `ticket_unlink`, `ticket_reorder` and `ticket_delete` (section 2.2.3).
- Two new frames answer them: `ticket` (one ticket) and `tickets` (the whole board). The daemon also pushes them to every connected client whenever the board changes, a 1.4.0 client included when another client edits the backlog.
- Nothing older moved: no existing frame, field or meaning changed. A client that ignores frame types it does not know sees no difference.

### What changed in 1.4.0

**1.4.0 is additive: the daemon stores agent profiles, and a session can be spawned from one.**

- **Three client commands and two answers (section 2.2.2).** `agent_save` creates or overwrites a profile and is answered with `agent`. `agent_list` and `agent_delete` are answered with `agents`, the whole list.
- **`SpawnOptions.agentId`, a new optional field.** `new {"agentId":"backend"}` spawns the connection's next sessions from that profile: on its provider and model unless `new` names its own, with its `instructions` put ahead of each session's first prompt.
- **`SessionRecord.agentId` and `SessionRecord.role`, two new optional fields.** The profile a session was spawned from, and that profile's role at spawn time. Absent or null means no profile.

A 1.3.0 client works unchanged. It never sends the new commands or `agentId`, so it never receives `agent` or `agents`, and section 2's compatibility rule already has it ignore the two new `SessionRecord` fields.

### What changed in 1.3.0

**1.3.0 is additive: one daemon can serve several providers, and a client chooses per session.**

- **`new` may carry spawn options (section 2.2.1).** `{"provider":"claude","model":"sonnet"}` chooses the provider and model of the sessions this connection creates next, until the next `new`. Data that is not a JSON object, empty included, keeps its old meaning: the default provider, and any earlier choice is cleared.
- **`providers`, a new request and answer.** It lists every provider the daemon can spawn, the default first, with the model each one uses when a spawn names none.
- **`SessionRecord.model`, a new optional field.** The model the session's spawn named. Absent or null means the provider's default.
- **`GET /health` gains `providers`.** `provider` still names the default.
- **A resume stays on the session's own provider.** A session whose provider this daemon is no longer configured with is refused with `error`. Before 1.3.0 a daemon had one provider and ran every session on it, including a session another provider had started, which could only fail.

A 1.2.0 client works unchanged. It never sends spawn options, so every session runs on the default provider as before, and it never sees `providers` unless it asks. Which providers a daemon offers is operator configuration, not a frame: `AGENTHQ_PROVIDERS` lists them besides the default `AGENTHQ_PROVIDER`, and unset means the default only.

### What changed in 1.2.0

**1.2.0 keeps every frame name and payload. It corrects one meaning and adds authentication.**

- **A correction (finding F1).** A client frame's `sessionId` is honoured on `prompt`, `mail_send`, `mail_inbox` and `mail_outbox`. Before 1.2.0 the daemon ignored it and used the connection's active session, so a prompt that named session B could run in session A. A client that never sends `sessionId`, or always names its own active session, sees no change.
- **Authentication (section 7).** A daemon may require `Authorization: Bearer <token>` on every route except `GET /health`. This is configuration, not a frame: a daemon with no token still serves loopback clients.
- **Stricter admission (section 7.1).** A request carrying an `Origin` header is refused with 403 in every mode. In no-token mode the `Host` header must also name loopback, so a client that reaches a loopback daemon under another name (a hosts-file alias, a container's host alias) is refused and must use the loopback name.

A 1.0.0 or 1.1.0 client still decodes every frame. Against a daemon that has a token it must send one, or it is refused before any frame is exchanged.

### What changed in 1.1.0

**1.1.0 is additive: nothing 1.0.0 froze moved.** It adds the pod command surface (section 2.5)
and two counts on `GET /health` (section 3). The four events you render - `text_delta`, `status`,
`error`, `finished` - are byte-for-byte what 1.0.0 published: same names, same payload types,
same meaning, same field order. **A 1.0.0 client works unchanged against a 1.1.0 daemon** and
simply never sees the new frames. If a future change needs a MAJOR bump, it is because one of
those four moved, not because more was added.

---

## 1. Why this file exists

Phase 1 shipped the WebSocket and forwarded the provider's own stream lines: `opencode run --format
json` emits `step_start` / `text` / `step_finish`, `claude --output-format stream-json` emits
`system` / `assistant` / `result`. A client therefore had to know which CLI was running and parse
that CLI's schema — which meant the UI could not be built until the provider layer was finished, and
it broke the day a provider changed.

So the boundary was frozen here instead. **This document and the frames in it are the contract.**
The owner's decision of 2026-10-08 is explicit: the UI is a separate layer from the coding layer,
and the two tracks schedule independently against this one shared, stable boundary.

| | |
| --- | --- |
| Published vocabulary | `text_delta` · `reasoning` · `status` · `error` · `finished`, plus `pod` and attention frames |
| Transport | Ktor WebSocket at `/ws`, plus plain HTTP (see §3) |
| Version constant | `com.superiorsoftllc.agenthq.protocol.EventVocabulary.VERSION` |
| Published to you | in the `ready` frame's `data` on connect, and in `GET /health` |

---

## 2. The WebSocket surface

`GET /ws` upgrades to a WebSocket. Every message, in both directions, is a JSON **envelope**:

```json
{ "type": "text_delta", "data": "the text", "sessionId": "9f2c…" }
```

| Field | Type | Notes |
| --- | --- | --- |
| `type` | string | one of the frame names in the tables below |
| `data` | string | payload; JSON-encoded when the payload is structured (stated per frame) |
| `sessionId` | string, optional | tags a frame with the session it belongs to. In a client frame it selects the session: `prompt` continues that session; `mail_send` sends from it when the request's `from` is blank; `mail_inbox` and `mail_outbox` read its mail when the frame's data is blank (1.2.0, finding F1). Omit it and the daemon uses the connection's active session. For `prompt`, an unknown or non-resumable id is answered with `error` and never forked into a new session. |

**Compatibility rule (both directions).** Decode with `ignoreUnknownKeys = true`. New *optional*
fields are added freely and are never breaking; a new payload on an existing frame type is not
breaking either. What *is* breaking is changing the meaning of a field that exists today — that
requires a version bump, and you may refuse a version you do not know. A client must also ignore a frame type it does not know: from 1.5.0 the daemon pushes `ticket` and `tickets` frames that the client never asked for.

The `ready` frame arrives immediately after the handshake, before anything else, and tells you the
contract version, which provider is active and (since 1.11.0) which daemon process this is:

```json
{ "type": "ready", "data": "provider=stub contract=1.11.0 instance=5c0e… since=1791681195703" }
```

### 2.1 The events you render — the public vocabulary

These are provider-neutral. A `text_delta` means "assistant text arrived", full stop, whether it
came from OpenCode, Claude Code, or a scripted stub.

#### `text_delta` — incremental assistant text

`data` is the delta itself, a plain string. Append it to the transcript; do not try to derive
anything from its length or shape. A provider that emits one large blob still produces exactly one
`text_delta` with the whole thing, so the same renderer works for both.

```json
{ "type": "text_delta", "data": "Reading the failing test…", "sessionId": "9f2c…" }
```

#### `reasoning` — the model's reasoning (1.23.0)

`data` is a plain string, the model's own chain of thought. It is deliberately a **different frame**
from `text_delta`: a client renders reasoning apart from the answer (collapsed under a "Thinking"
affordance, say) rather than appending it to the transcript as if the assistant had said it.
Everything else is like `text_delta` — it is sequenced and replayable on the session stream, and one
provider blob still yields one `reasoning`.

```json
{ "type": "reasoning", "data": "The build fails because the cache is stale…", "sessionId": "9f2c…", "sequence": 4 }
```

#### `status` — a lifecycle transition

`data` is a JSON `StatusPayload`:

```json
{ "state": "BUSY", "detail": "" }
```

`state` is one of the seven `SessionState` values, and it is **the same vocabulary as the `session`
frame**, so you learn one state machine, not two:

| State | What it means for a UI |
| --- | --- |
| `STARTING` | a run was spawned, no output yet — show "starting" |
| `BUSY` | output has arrived — show the spinner and stream |
| `WAITING_INPUT` | defined, but **no provider produces it today** (see §5) — treat as `BUSY` |
| `IDLE` | no run in flight, alive and resumable — the resting state between prompts |
| `EXITED` | ended on purpose (a kill) — terminal, still resumable |
| `ORPHANED` | a daemon restart left this run's child alive — terminal, still resumable |
| `FAILED` | non-zero exit, or the run could not start — terminal, resumable |

`detail` is an optional human gloss. Show it if you like; never parse it.

`tool` (since 1.25.0) is the **name of the tool the run is currently calling** — `Read`, `Bash`, and
so on — or absent when the status is not about a tool call. When present, show it ("Running read").
It is not a lifecycle state: a tool call still leaves the session `BUSY`, so a client that ignores
`tool` renders exactly the frame it always did.

`activity` (unreleased, AGENT-UX-12) is the tool call's **salient argument** — the path being searched,
the command being run — or absent. When present, show it with the tool ("Running bash: ls -la src").
It is redacted to a short single line (no file contents, no environment) and clamped to 100 characters.
A client that ignores `activity` renders the frame it always did.

```json
{ "state": "BUSY", "detail": "", "tool": "Read" }
{ "state": "BUSY", "detail": "", "tool": "Bash", "activity": "ls -la src" }
```

#### `error` — a failure

`data` is a plain string, the message. Two sources use this frame and they are deliberately the same
frame so a client needs no branching:

- a **daemon-side** failure (an unknown frame, a resume that was refused, a kill of an unknown id);
- a **provider-reported** failure, normalised from that provider's own error event.

Note the difference from `finished`: `error` is *one bad thing that happened*; `finished` is *the
run is over*. A failed run emits both.

#### `turn` — what the run was asked (1.11.0)

`data` is a plain string: the prompt this run was started with, exactly as the provider received it.
It is the human side of the conversation and is sequenced on the same stream as the agent's side,
always before the first frame of the run it started. Render it as the user's message.

```json
{ "type": "turn", "data": "Why does the build fail?", "sessionId": "9f2c…", "sequence": 3 }
```

#### `finished` — the run is over

`data` is a JSON `FinishedPayload`:

```json
{ "exitCode": 0, "outcome": "SUCCEEDED", "detail": "" }
```

`outcome` is the daemon's own judgement, and it is **not** derivable from `exitCode` alone:

| `outcome` | Meaning |
| --- | --- |
| `SUCCEEDED` | the provider exited 0 |
| `FAILED` | non-zero exit, or the run could not be started |
| `KILLED_GRACEFUL` | you asked for a kill and it resolved within the grace window |
| `KILLED_FORCED` | you asked for a kill and the process tree had to be killed |
| `ORPHANED` | reserved for a daemon restart that found a live child |

This is the important one: a deliberate kill and a crash both end a process with a code, but they
mean opposite things to a user. Render `outcome`, not the exit code.

### 2.2 Session and worktree frames

Frames you render for structure and context. These existed before the freeze and are unchanged,
apart from the 1.3.0, 1.4.0, 1.5.0, 1.6.0 and 1.28.0 additions marked in the table.

| Type | `data` | Direction |
| --- | --- | --- |
| `ready` | `"provider=<id> contract=<version> instance=<id> since=<epoch millis> build=<build>"` (`instance`, `since`: 1.11.0; `build`: BUILD-STAMP-1, additive within 1.115.0, e.g. `1.0.1+20261023.1432.e85b223[.dirty]`, absent from older daemons) | daemon → client |
| `session` | one `SessionRecord` (JSON) | daemon → client |
| `sessions` | list of `SessionRecord` | daemon → client |
| `worktree` | one `WorktreeStatus` (JSON). Carries `changes` (git view): `null`/absent = UNKNOWN, `[]` = known clean, else `{path,status,staged,fromPath?}` per changed file, status one of `ADDED MODIFIED DELETED RENAMED UNTRACKED CONFLICTED`; a file changed on both sides appears once per side; `changesTruncated` is true when the list was cut at 500 | daemon → client |
| `worktrees` | list of `WorktreeStatus` | daemon → client |
| `worktree_remove` | one `CleanupResult` (JSON) | daemon → client |
| `prompt` | the prompt text | client → daemon |
| `new` | starts a session. With no profile named, the next prompt starts it; with a profile named (section 2.2.2) the session starts HERE, at `new`, since 1.30.0. Since 1.3.0, optionally a `SpawnOptions` object (JSON, section 2.2.1) carrying `provider`/`model` and, since 1.30.0, `projectId`; data that is not a JSON object (empty, as 1.2.0 clients send it) means the default provider. Since 1.4.0 it may name an agent profile | client → daemon |
| `role_list` | — (request the role catalog; 1.12.0) | client → daemon |
| `roles` | list of `Role` (JSON), canonical ids first (1.12.0) | daemon → client |
| `role_save` | one `RoleSave` (JSON, strict; 1.12.0) | client → daemon |
| `role` | one `Role` (JSON), pushed to every client after a save (1.12.0) | daemon → client |
| `role_delete` | a role id: remove its custom file; a customised bundled role is reset (answered by the `role` push, the bundled definition), a pure custom role is removed (answered by `role_removed`); refused (`error`) for a bundled role with no override, an unknown id, or while a live session has the role (1.108.0) | client → daemon |
| `role_removed` | a role id: the custom role was deleted, pushed to every client (1.108.0) | daemon → client |
| `trigger_save` / `trigger_fire` / `trigger_arm` / `trigger_disable` / `trigger_delete` / `trigger_list` / `trigger_events` | see "What changed in 1.14.0"; `projectId`, `trigger_arm` enabling, prompt-as-mail: 1.105.0 | client → daemon |
| `trigger` / `triggers` / `trigger_events` | one `TriggerView` / a list / a list of `TriggerEventView` (1.14.0; `projectId` / `lastFiredAt` / `nextFireAt` and the `mailed` / `coalesced_pending` / `deferred_busy` dispositions, 1.105.0) | daemon → client |
| `provider_states` | — (request every provider's limit state; 1.13.0) | client → daemon |
| `provider_states` | list of `ProviderState` (JSON), one per served provider (1.13.0) | daemon → client |
| `provider_state` | one `ProviderState` (JSON), pushed when it changes (1.13.0) | daemon → client |
| `usage` | one `RunUsage` (JSON), session-tagged and sequenced, when a run that reported usage ends (1.13.0) | daemon → client |
| `session_project` | `{"sessionId","projectId"}`: bind an unbound session to a project; answered by a `session` push (1.61.0) | client → daemon |
| `session_restart` | `{"sessionId","force"?}`: reboot a session in place as a never-run session (same id, role, harness, project, worktree); mid-turn refused unless `force`; answered by a `session` push (ROLE-NOTES-1, version PM-allocated) | client → daemon |
| `progress` | `sessionId` = the reporting session; one `ProgressReport` (JSON) `{op, percent?, label?, steps?, count?, step?, done?, total?}`, `op` = `percent` \| `plan` \| `step` \| `bytes` \| `count` \| `status` \| `done` \| `fail` \| `clear` \| `get`; the change reaches every client as the usual `session` push, a bad report is `error` `progress refused: ...` (1.117.0) | client → daemon |
| `progress` | the session's `SessionProgress` (JSON) or `null`, answering a `progress` request (1.117.0) | daemon → client |
| `assistant` | — (empty; ignored): open the installation-wide Assistant. Answered to the sender with the Assistant's `session` frame (one `SessionRecord`, `global` = true, tagged with its session id); the first request creates it (role `assistant`, no project, working directory `<stateDir>/assistant`) and every later request, from any client, answers the same session. Failure: `error` `assistant refused: ...` / `assistant failed: ...` (1.115.0) | client → daemon |
| `limits` | — (request the plan-limit meters; 1.59.0) | client → daemon |
| `limits` | list of `ProviderLimits` (JSON), the answer and a push after each `usage` (1.59.0) | daemon → client |
| `usage_get` | — (empty): request the plan meters and pacing allowance (1.119.0) | client → daemon |
| `usage_report` | `UsageReport` (JSON) `{meters, rule, observedAt}`, answering `usage_get` (1.119.0) and `usage_set` (1.121.0) | daemon → client |
| `usage_set` | `UsageSet` (JSON): a meter's manual percent / override set or cleared, and/or the pacing rule; refused = `error` `usage_set refused: ...` (1.121.0) | client → daemon |
| `providers` | — (request the list; 1.3.0) | client → daemon |
| `providers` | list of `ProviderInfo` (JSON), the default first (1.3.0) | daemon → client |
| `agent_save` | one `AgentProfile` (JSON): no `id`, or one nobody has, creates; a known `id` overwrites (1.4.0); `traitIds` absent/null keeps the stored list, `[]` clears it (1.50.0); `skillIds` likewise, unknown ids refused (1.56.0) | client → daemon |
| `agent` | the `AgentProfile` as stored (JSON), answering `agent_save` (1.4.0); always carries `traitIds` (1.50.0) and `skillIds` (1.56.0) | daemon → client |
| `agent_swap` | `{agentId, provider, model?, effort?, session?}` (strict; `session` = `auto` \| `keep` \| `new`, default `auto`); answered by the `agent_swapped` push to every client (1.47.0) | client → daemon |
| `agent_swapped` | `{agent, from{provider, model, effort}, session{outcome, sessionId?}}`; outcome `kept` \| `new` \| `none` (1.47.0) | daemon → client |
| `agent_list` | — (request the list; 1.4.0) | client → daemon |
| `agent_delete` | a profile id (1.4.0) | client → daemon |
| `agents` | list of `AgentProfile` (JSON), oldest first, answering `agent_list` and `agent_delete` (1.4.0) | daemon → client |
| `agent_roster` | `{"projectId"}` (JSON, `RosterRequest`): the project whose live agents to list; a blank one is refused (1.31.0) | client → daemon |
| `roster` | list of `RosterRow` (JSON), one per agent of that project, answering `agent_roster` (1.31.0) | daemon → client |
| `ticket_save` | one `TicketSave` (JSON): no `id` creates (a `title` is required), a known `id` edits, an unknown `id` is refused (1.5.0) | client → daemon |
| `ticket_get` | a ticket id (1.5.0); answered with the ticket's `branch` and `base` carried in `Ticket` (1.32.0) | client → daemon |
| `ticket_list` | — (request the board; 1.5.0), a bare project id, or `{"projectId"?,"epicId"?}` (1.66.0) | client → daemon |
| `ticket_move` | one `TicketMove` (JSON): `id`, optional `status`, `position`, `wontFix` (1.5.0); moving to `in_progress` starts a ticket session (1.6.0) | client → daemon |
| `ticket_link`, `ticket_unlink` | `{"ticketId", "relatedId", "type"}` (JSON) (1.5.0) | client → daemon |
| `ticket_reorder` | `{"status"?, "ids": [...]}` (JSON): the listed tickets go to the top of their column, in order (1.5.0) | client → daemon |
| `ticket_delete` | a ticket id (1.5.0) | client → daemon |
| `ticket` | one `Ticket` (JSON), answering `ticket_save` / `ticket_get` / `ticket_move` / `ticket_link` / `ticket_unlink` / `ticket_start_next`; also pushed to every client for each ticket a change touched (1.5.0 / 1.6.0); the `Ticket` carries `branch` and `base` (1.32.0) | daemon → client |
| `tickets` | list of `Ticket` (JSON) in board order, answering `ticket_list` / `ticket_reorder` / `ticket_delete`; also pushed to every client after a reorder or a delete (1.5.0) | daemon → client |
| `ticket_start_next` | empty data; start the top eligible `todo` ticket (1.6.0) | client → daemon |
| `settings_get` | empty data for all settings, or one setting key (1.6.0) | client → daemon |
| `settings_set` | `{"key": string, "value": json}` (1.6.0) | client → daemon |
| `settings` | a JSON object of settings (1.6.0) | daemon → client |
| `limit_catalog` | request: empty data. Answer: `{limits: [{key, value, default, unit, description, min, max?, zeroMeans?, env}]}`, every daemon limit with its value in force (1.133.0) | both |
| `resume` | `"<sessionId>\n<prompt>"` | client → daemon |
| `kill` | `"<sessionId> [force]"` | client → daemon |
| `interrupt` | the session id, a bare string: stops the session's CURRENT turn and keeps the session at rest (`IDLE`), same conversation; sends NO `finished` frame; refused with `error` when no turn is in flight (1.32.1) | client → daemon |
| `models` | request: empty data. Answer: a JSON list of `ProviderModels` `{provider, group, label, models, note}` for every configured provider; an empty `models` carries a `note` (1.34.0). Answered off the request loop: may arrive after later replies on the same connection, FIFO among `models` replies (see 1.34.0) | both |
| `role_binding_list` | empty data: request every stored role binding, answered by `role_bindings` (1.40.0) | client → daemon |
| `role_binding_get` | a role id; answered by `role_binding` (1.40.0) | client → daemon |
| `role_binding_set` | `{roleId, provider?, model?, effort?}`; answered by the `role_binding` push to every client (1.40.0) | client → daemon |
| `role_binding` | one `RoleBinding` `{roleId, provider, model, effort, updatedAt}` (1.40.0) | daemon → client |
| `role_bindings` | list of `RoleBinding` (1.40.0) | daemon → client |
| `prompt_save` | `{name, body, tags?}` (strict); creates the prompt or updates the one with that name; answered by the `prompt_item` push to every client (1.44.0) | client → daemon |
| `prompt_list` | optional tag filter (empty = all); answered by `prompts` (1.44.0) | client → daemon |
| `prompt_get` | a prompt id; answered by `prompt_item` (1.44.0) | client → daemon |
| `prompt_delete` | a prompt id; answered by the `prompts` push (remaining list) to every client (1.44.0) | client → daemon |
| `prompt_item` | one `PromptView` `{id, name, body, tags, createdAt, updatedAt}` (1.44.0) | daemon → client |
| `prompts` | list of `PromptView`, ordered by name (1.44.0) | daemon → client |
| `skill_save` | `{name, description?, body, tags?}` (strict); creates the skill or updates the one with that name; answered by the `skill_item` push to every client (1.45.0) | client → daemon |
| `skill_list` | optional tag filter (empty = all); answered by `skills` (1.45.0) | client → daemon |
| `skill_get` | a skill id, or an exact skill name (1.56.0); answered by `skill_item` (1.45.0) | client → daemon |
| `skill_delete` | a skill id; answered by the `skills` push (remaining list) to every client (1.45.0) | client → daemon |
| `skill_item` | one `SkillView` `{id, name, description, body, tags, createdAt, updatedAt}` (1.45.0) | daemon → client |
| `skills` | list of `SkillView`, ordered by name (1.45.0) | daemon → client |
| `trait_save` | `{id, name, body, check?}` (strict, nested `check` too); creates the trait at version 1 or updates the one with that id (version +1 only on a change); answered by the `trait_item` push to every client (1.48.0) | client → daemon |
| `trait_list` | data ignored; answered by `traits` (1.48.0) | client → daemon |
| `trait_get` | a trait id; answered by `trait_item` (1.48.0) | client → daemon |
| `trait_delete` | a trait id; answered by the `traits` push (remaining list) to every client (1.48.0); refused while a role (1.49.0) or an agent (1.50.0) binds it | client → daemon |
| `trait_item` | one `TraitView` `{id, name, version, body, check?{kind, reportTo, fields, reprompt}, createdAt, updatedAt}` (1.48.0) | daemon → client |
| `traits` | list of `TraitView`, ordered by id (1.48.0) | daemon → client |
| `role_traits_set` | `{roleId, traitIds[]}` (strict; `[]` clears); answered by the `role_traits` push to every client (1.49.0) | client → daemon |
| `role_traits_get` | a role id; answered by `role_traits` (1.49.0) | client → daemon |
| `role_traits_list` | data ignored; answered by `role_traits_all` (1.49.0) | client → daemon |
| `role_traits` | one `RoleTraits` `{roleId, traitIds, updatedAt}` (1.49.0) | daemon → client |
| `role_traits_all` | list of `RoleTraits`, ordered by role id (1.49.0) | daemon → client |
| `memory_save` | `{projectId, name, description?, body?, mode?, find?, content?, folder?}` (strict); `replace` (default) / `append` / `edit`, see "What changed in 1.51.0"; answered by `memory_note`; refusals put the code first (`note_not_found`, `anchor_not_found`, `anchor_ambiguous`) (1.21.0, modes 1.51.0) | client → daemon |
| `memory_get` | `{projectId, name}`; answered by `memory_note` or refused `note_not_found` (1.51.0) | client → daemon |
| `memory_list` | the raw project id; answered by `memory_notes` (1.21.0) | client → daemon |
| `memory_list` (scoped) | `{projectId, scope?, sessionId?, role?}` (a payload starting with `{`); answered by `memory_notes` (1.98.0) | client → daemon |
| `memory_delete` | `{projectId, name, scope?, sessionId?, role?}`; answered by that scope's `memory_notes` index (1.98.0) | client → daemon |
| `memory_purge_role` | `{role, confirm}`, `confirm` must equal `role`; deletes the role's notes in every project (1.98.0) | client → daemon |
| `processes` | `ProcessesRequest` `{op: list\|reap\|reap_rogue, pid?}` → `ProcessesAnswer` `{report, reaped, refused?}`: list / reap the processes this daemon's sessions started (1.120.0) | client → daemon, daemon → client |
| `process_pressure` | a `ProcessReport`, pushed when free RAM drops under `processes.floorMb`, at most once per 10 min (1.120.0) | daemon → client |
| `bg_job` | `{op: beat|done|list, job?, jobId?}` with the frame sessionId; `beat` registers or heartbeats a `BgJob` (`agenthq bg run`), answered by a `bg_job` list of the session's jobs; additive, no version bump | client → daemon, daemon → client |
| `decision_add` | `{projectId, sessionId?, summary, body?, supersedes?}` (strict); answered by a `decision_item` pushed to every client; refusals `decision_not_found`, `already_superseded`, `session_not_found` code first (1.53.0) | client → daemon |
| `decision_list` | `{projectId, includeSuperseded = false}`; answered by `decisions` (1.53.0) | client → daemon |
| `decision_get` | `{projectId, ref}` (`D7` or `7`); answered by `decision_item` or refused `decision_not_found` (1.53.0) | client → daemon |
| `issue_open` | `{projectId, sessionId?, summary, location, ticketId?}` (strict); answered by an `issue_item` pushed to every client (1.54.0) | client → daemon |
| `issue_attempt` | `{projectId, sessionId?, summary, outcome? = failed, location?, issue?}` (strict); no `issue`: the session's active issue, else a new `implicit` one; answered by a pushed `issue_item`; refusals `issue_not_found`, `already_fixed`, `session_not_found` (1.54.0) | client → daemon |
| `issue_fix` | `{projectId, sessionId?, summary, issue?}` (strict); answered by a pushed `issue_item`; refusals `no_active_issue`, `issue_not_found`, `already_fixed`, `session_not_found` (1.54.0) | client → daemon |
| `issue_list` | `{projectId, includeFixed = false, location?}`; answered by `issues` (1.54.0) | client → daemon |
| `issue_get` | `{projectId, ref}` (`I7` or `7`); answered by `issue_item` or refused `issue_not_found` (1.54.0) | client → daemon |
| `issue_precheck` | `{projectId, path}`; answered by `issues`, open first (1.54.0) | client → daemon |
| `memory_note` | one `MemoryNote` `{id, projectId, name, folder, description, body, updatedAt}`, the whole note (1.21.0) | daemon → client |
| `memory_notes` | list of `{name, description, folder}` in name order, body withheld (1.21.0, `folder` 1.51.0) | daemon → client |
| `decision_item` | one `Decision` `{projectId, number, ref, summary, body, supersedes?, supersededBy?, authorSessionId?, authorAgentId?, authorRole?, createdAt}`; pushed to every client after an add (1.53.0) | daemon → clients |
| `pipeline_config` | `{projectId, stages?, exclusiveCheck?, clear?}`: set a project's ordered stage names (stage names are data), or `clear` to remove the pipeline; answered by `pipeline_state` (additive, PIPE-1) | client → daemon |
| `notify` | `{say, level?, projectId?, ticketId?}`: raise a spoken attention alert; pushed to every client as `spoken` (additive, VOICE-1) | client → daemon |
| `spoken` | one `SpokenNotice` `{id, at, say, level, projectId?, projectName?, ticketId?}`; ephemeral push to every client (additive, VOICE-1) | daemon → clients |
| `pipeline_show` | `{projectId}`; answered by `pipeline_state`, `configured:false` for a project with no pipeline (the default) | client → daemon |
| `pipeline_record` | `{projectId, commit, stage, evidence?, sessionId?}`: move a commit to a configured stage; refused `no_pipeline` / `unknown_stage`; answered by `pipeline_state` | client → daemon |
| `pipeline_check` | `{projectId, action: start\|ping\|finish, scope?, commit?, what?, sessionId?}`: the running check; with `exclusiveCheck` a second start in one scope is refused `check_running`; answered by `pipeline_state` | client → daemon |
| `pipeline_state` | `PipelineState` `{projectId, configured, config?{stages, exclusiveCheck}, commits[{commit, stage, movedBy?, movedAt, evidence?}], history[], checks[{scope, commit, what, startedBy?, startedAt, lastOutputAt}]}` | daemon → client |
| `decisions` | list of `Decision` in number order, active only unless `includeSuperseded` (1.53.0) | daemon → client |
| `issue_item` | one `Issue` with its `events`; pushed to every client after an open, attempt or fix (1.54.0) | daemon → clients |
| `issues` | list of `Issue` (number order for `issue_list`; open first for `issue_precheck`) (1.54.0) | daemon → client |
| `command_result` | answer to a `prompt` that ran a `/` command: `{ok, command, message}`, envelope `sessionId` = the session (1.39.0) | daemon → client |
| `command_catalog` | request: `{provider?, prefix?}` (+ optional envelope `sessionId`). Answer: `{provider, prefix, commands}` filtered by the prefix (1.38.0) | both |
| `command_list` | empty data: request the saved commands (1.33.0) | client → daemon |
| `commands` | a JSON list of `CommandSpec` `{id, name, cwd, argv, graceMillis}` (1.33.0; `argv` + `graceMillis` since 1.94.0); also pushed to every client after a `command_save` / `command_delete` | daemon → client |
| `command_save` | `{id, name?, argv: [..], cwd?, graceMillis?}`: upsert a saved command into commands.json; answered by `commands` to every client, refused with `error` `command_save refused: ...` (1.94.0) | client → daemon |
| `command_delete` | `{id}`: remove a saved command; answered by `commands` to every client, refused with `error` `command_delete refused: ...` (1.94.0) | client → daemon |
| `run_start` | `{"commandId": "<id>"}`; refused with `error` if the id is unknown or a run is already in flight (1.33.0) | client → daemon |
| `run` | one `RunRecord` `{runId, commandId, state, startedAt, pid, lastSequence}`, the answer to `run_start`; `state` is `STARTING`, `RUNNING` or `FINISHED` (1.33.0) | daemon → client |
| `run_output` | `{runId, sequence, stream, line, truncated}`; `stream` is `stdout` or `stderr`, `sequence` starts at 1 per run (1.33.0) | daemon → client |
| `run_finished` | `{runId, exitCode, outcome, endedAt, detail}`; sent exactly once per run, including after a stop and after a spawn failure (1.33.0) | daemon → client |
| `run_stop` | `"<runId> [force]"`: kills the run's process tree; the answer is the `run_finished` (1.33.0) | client → daemon |
| `run_bytes` | `{runId, sequence, data, replay, droppedBytes}`: raw terminal output of a run that has a pty, `data` base64; `sequence` counts chunks from 1 per run (apart from `run_output`'s); a `replay` chunk answers `run_attach` (1.134.0) | daemon → client |
| `run_list` | empty data: request the kept runs; answered by `runs` (1.134.0) | client → daemon |
| `runs` | a JSON list of `RunSummary` `{runId, commandId, name, state, startedAt, endedAt, exitCode, outcome, terminal, interactive}`, newest first (1.134.0) | daemon → client |
| `run_attach` | `{runId}`: answered by one replay `run_bytes` with the run's kept scrollback, running or finished; `error` if the run is not kept (1.134.0) | client → daemon |
| `run_resize` | `{runId, cols, rows}`: resizes the run's pty; no answer unless refused (1.134.0) | client → daemon |
| `run_input` | `{runId, data}`: keys (base64) into a run started `interactive`; refused under the demo lock (1.134.0) | client → daemon |
| `session_delete` | a session id (1.28.0); on success, no direct reply -- the daemon pushes a fresh `sessions` frame to every client; refused (unknown id, or `live`) answers `error` with `"session_delete refused: <reason>"` | client → daemon |
| `repo` | a repository path (empty clears it) | client → daemon |
| `sessions` | — (request the list) | client → daemon |
| `worktrees` | — (request the list), or a session id in `data` to get that ONE session as a `worktree` frame; an unknown id is an `error` (git view) | client → daemon |
| `project_git_status` | a project id (1.36.0); answered by `project_git`; an unknown project is `error` `"project_git_status refused: ..."` | client → daemon |
| `project_git_diff` | `ProjectGitDiffRequest` `{projectId, path, staged?}` (1.36.0); answered by `project_git_patch`; an escaping or blank `path` is an `error` `"project_git_diff refused: ..."` | client → daemon |
| `project_git_log` | `ProjectGitLogRequest` `{projectId, limit?}` (1.36.0, default 50, max 200); answered by `project_git_history` | client → daemon |
| `project_git` | one `ProjectGitStatus` `{projectId, directory, available, reason, repoRoot, branch?, head?, changes[], changesTruncated}`; `available=false` is UNKNOWN, never clean (1.36.0) | daemon → client |
| `project_git_patch` | one `ProjectGitPatch` `{projectId, path, staged, available, reason, patch, binary, truncated}` (1.36.0) | daemon → client |
| `project_git_history` | one `ProjectGitHistory` `{projectId, available, reason, commits[{hash,shortHash,author,timestamp,subject}], truncated}`, newest first (1.36.0) | daemon → client |
| `project_base` | `ProjectBaseRequest` `{projectId, branch?, clear?}` (1.81.0); answered by `project`; a refusal is an `error` `"project_base refused: ..."` | client → daemon |
| `epic_base` | `EpicBaseRequest` `{epicId, branch?, clear?}` (1.82.0); answered by `epic`; a refusal is an `error` `"epic_base refused: ..."` | client → daemon |
| `project_config` | `ProjectConfigRequest` `{projectId, laneCaps?, autoRemoveDoneWorkers?, routingRules?}` (LANE-CAPS, 1.111.0; `autoRemoveDoneWorkers` 1.118.0 sets `Project.autoRemoveDoneWorkers`, absent = unchanged; `routingRules` 1.128.0 REPLACES `Project.routingRules`, see ROUTING-1): absent or empty `laneCaps` (and no `autoRemoveDoneWorkers` or `routingRules`) reads the project, otherwise the `{role: max}` entries are MERGED into `Project.laneCaps` (a cap of 1 is the default and removes the entry); answered by the whole `project` frame, also pushed to every client when a cap changed; a cap below 1, an unknown project or a malformed payload is an `error` `"project_config refused: ..."` | client → daemon |
| `image_upload` | `ImageUpload` `{projectId, purpose, fileName, mimeType, base64}` (1.83.0): `purpose` `chat`\|`ticket`\|`file` (required; `file` 1.89.0: any type, at most 20 MB, no magic-byte check); otherwise png/jpeg/gif/webp by magic bytes, at most 10 MB, stored at `<Project.directory>/.agenthq/uploads/<purpose>/<sha256>.<ext>`; answered by `image`; a refusal is an `error` `"image_upload refused: ..."` and writes nothing | client → daemon |
| `image` | `ImageStored` `{path, relPath, sha256, bytes, mimeType}` (1.83.0): where an `image_upload` is stored; the same bytes answer the same `path` | daemon → client |
| `session_swap` | `SessionSwap` `{sessionId, provider, model?, effort?}` (strict, 1.84.0); a session with no agent onto another harness, in place; answered by the `session_swapped` push to every client; a refusal is an `error` `"session_swap refused: ..."` | client → daemon |
| `session_swapped` | `SessionSwapped` `{sessionId, provider, model?, defaultModel?, from{provider, model, effort}, newConversation}` (1.84.0) | daemon → client |
| `project_git_branches` | `ProjectGitBranchesRequest` `{projectId}` (1.65.0); answered by `project_git_branch_list` | client → daemon |
| `project_git_branch_changes` | `ProjectGitBranchChangesRequest` `{projectId, branch, base?}` (1.65.0); answered by `project_git_branch_diffstat`; a malformed or unknown branch is an `error` `"project_git_branch_changes refused: ..."` | client → daemon |
| `project_git_branch_diff` | `ProjectGitBranchDiffRequest` `{projectId, branch, base?, path}` (1.65.0); answered by `project_git_patch` with `branch` set | client → daemon |
| `project_git_branch_list` | one `ProjectGitBranchList` `{projectId, available, reason, current?, branches[{name,head,upstream?,isWorktree,worktreePath?}], truncated}` (1.65.0) | daemon → client |
| `project_git_branch_diffstat` | one `ProjectGitBranchDiffstat` `{projectId, branch, base, mergeBase, available, reason, changes[{path,status,fromPath?,added,removed,binary}], truncated}`; the diff is `mergeBase..branch` (1.65.0) | daemon → client |
| `project_git_worktrees` | `ProjectGitWorktreesRequest` `{projectId, sessionId?}` (1.88.0; `sessionId` marks the session's worktree); answered by `project_git_worktree_list` | client → daemon |
| `project_git_worktree_list` | one `ProjectGitWorktreeList` `{projectId, available, reason, worktrees[], selected?}` (1.88.0) | daemon → client |
| `project_git_worktree_status` | `ProjectGitWorktreeStatusRequest` `{projectId, path, historyLimit = 30}` (1.88.0); answered by `project_git_worktree`; a `path` the repository does not list is an `error` `"project_git_worktree_status refused: ..."` | client → daemon |
| `project_git_worktree` | one `ProjectGitWorktreeStatus` `{projectId, path, available, reason, branch?, head?, base, ahead?, behind?, baseReason, uncommitted, changes[], changesTruncated, commits[], commitsTruncated}` (1.88.0) | daemon → client |
| `project_git_checkout` | `ProjectGitCheckoutRequest` `{projectId, branch, create?}` (1.74.0); answered by `project_git_checkout_result`; an unknown project or malformed payload is an `error` `"project_git_checkout refused: ..."` | client → daemon |
| `project_git_checkout_result` | one `ProjectGitCheckoutResult` `{projectId, branch, create, switched, code, reason, previous?, current?, dirtyCount, worktreePath?}`; a switch that happened is followed by a `project_git` push to every client (1.74.0) | daemon → client |
| `cli_locations` | — (request, no data) / one `CliLocations` `{tools:[CliLocation{id, label, path?, source?, override?, tried[], reason?}]}` (reply), built-ins first (`claude`, `opencode`, `pi`, `git`) then descriptor harnesses (since 1.73.0) | client → daemon (request), daemon → client (reply) |
| `cli_location_set` | `{id, path?}`: stores `path` as that CLI's override, or clears it when `path` is null or blank (since 1.73.0); answered by `cli_locations`; a bad path or unknown id is an `error` `"cli_location_set refused: ..."` | client → daemon |
| `harness_install_status` | `{checkLatest?}` (or empty): detect harness CLIs (since 1.100.0); answered by `harness_install_status` `HarnessInstallReport` | client → daemon, daemon → client |
| `harness_install` | `{ids?, update?}`: npm-install harness CLIs into the app prefix (since 1.100.0); answered by `harness_install_result` `HarnessInstallResult`; unknown id is an `error` `"harness_install refused: ..."` | client → daemon |
| `harness_install_result` | `{outcomes, log, report}` the answer to `harness_install` (since 1.100.0) | daemon → client |
| `secret_set` | `{name, value}`: seal `value` under a known secret name in the daemon keystore (since 1.102.0); answered by `secret_state`, pushed to every client; an unknown name or bad payload is an `error` `"secret_set refused: ..."` that never echoes the value | client → daemon |
| `secret_clear` | `{name}`: remove that secret from the keystore (since 1.102.0); answered by `secret_state`, pushed to every client; an unknown name is an `error` `"secret_clear refused: ..."` | client → daemon |
| `secret_import` | `{name}`: copy the older environment variable (e.g. `AGENTHQ_UPDATE_TOKEN` for `update-token`) into the keystore, deleting nothing (since 1.102.0); answered by `secret_state`, pushed to every client; an unknown name or nothing to import is an `error` `"secret_import refused: ..."` | client → daemon |
| `secret_status` | `{}` (or empty): read the keystore's state (since 1.102.0); answered by `secret_state` | client → daemon |
| `secret_field_set` | `{name, field, value}`: seal `value` as field `field` of the user secret `name`, creating it (since 1.135.0); `name` is lowercase `[a-z0-9._-]`, `field` an environment-variable name `[A-Za-z_][A-Za-z0-9_]*`; a built-in name, a bad name or the demo lock is an `error` that never echoes the value; answered by `secret_state`, pushed to every client | client → daemon |
| `secret_field_delete` | `{name, field}`: remove one field; the secret goes with its last field (since 1.135.0); answered by `secret_state`, pushed to every client | client → daemon |
| `secret_delete` | `{name}`: remove a user secret and all its fields (since 1.135.0); answered by `secret_state`, pushed to every client | client → daemon |
| `secret_state` | `{protection: external-key\|os\|key-file, secrets:[{name, label, set, tail, importable, overriddenByEnv}], entries:[{name, fields:[{name, tail}]}], locked}` (`entries`, `locked` since 1.135.0): the answer to every `secret_*` request, and pushed to every client after a change; never carries a stored value (`tail` is `••••` plus the last 4 characters of a 16+ character value, else `••••`) (since 1.102.0) | daemon → client |
| `tool_approval` | — (request, no data) / one `ToolApprovalInfo` `{mode?, effective, source, choices[]}` (reply): the global Tool approval and what a run without a session override gets (since 1.76.0) | client → daemon (request), daemon → client (reply) |
| `tool_approval_set` | `{mode?}`: stores the global Tool approval (`deny` / `allowEdits` / `allowAll`), or clears it when `mode` is null or blank (since 1.76.0); answered by `tool_approval`; an unknown mode is an `error` `"tool_approval_set refused: ..."` | client → daemon |
| `prompt_request` | one `PromptRequest` `{promptId, sessionId, kind, createdAt, tool?, input?, question?, options[], multiSelect, allowOther}`: a harness asks the owner (pushed when opened, and replayed after the connect burst while open; since 1.77.0) | daemon → client |
| `prompt_response` | `{promptId, decision?, answers[], otherText?}`: answers one open prompt; an unknown, already resolved or ill-fitting answer is an `error` `"prompt_response refused: ..."` (since 1.77.0) | client → daemon |
| `prompt_resolved` | one `PromptResolved` `{promptId, resolvedBy, outcome}`: the prompt is closed, whoever resolved it (since 1.77.0) | daemon → client |
| `approval_rules` | `{projectId?}` (null/blank = every project); answered by `approval_rules` `{projectId?, rules[]}`, oldest first (since 1.78.0) | client ↔ daemon |
| `approval_rule_delete` | `{ruleId}`; answered by `approval_rules` for the deleted rule's project; an unknown id is an `error` `"approval_rule_delete refused: ..."` (since 1.78.0) | client → daemon |
| `prompt_ask` | `{question, options[], multiSelect?, allowOther?, timeoutSec?}`, envelope `sessionId` = the asking session: opens a `question` prompt for it; a bad ask is an `error` `"prompt_ask refused: ..."` (since 1.79.0) | client → daemon |
| `prompt_answer` | one `PromptAnswer` `{answered, answers[], otherText?}`: the outcome of this connection's `prompt_ask`, sent to that connection only (since 1.79.0) | daemon → client |
| `question_ask` | `QuestionAsk` `{question, options[{label, description}], recommended, reason, allowOther?, defaultAfterMin?}`, envelope `sessionId` = the asking session: stores a NON-BLOCKING owner question; answered at once by `question_asked`; a bad ask or a rate limit is an `error` `"question_ask refused: ..."` (since 1.97.0) | client → daemon |
| `question_asked` | `QuestionAsked` `{questionId, note}`: the id of this connection's new question; the owner's pick arrives later as mail in thread `question:<id>` (since 1.97.0) | daemon → client |
| `question` | one `OwnerQuestion` (below): pushed to every client when a question is asked, answered, auto-picked, overridden or cancelled (since 1.97.0) | daemon → client |
| `question_list` | no data; answered by `questions` (since 1.97.0) | client → daemon |
| `questions` | a JSON list of `OwnerQuestion`, oldest first: every question, open and resolved; also sent to every connecting client (since 1.97.0) | daemon → client |
| `question_answer` | `QuestionAnswer` `{questionId, answer?, otherText?}` with NO envelope `sessionId` (the owner): picks on an `open` question or overrides an `auto` one; answered by the `question` push; a refusal is an `error` `"question_answer refused: ..."` (since 1.97.0) | client → daemon |
| `question_cancel` | `QuestionCancel` `{questionId}`, from the owner (no `sessionId`, the asker is mailed) or the asking session: withdraws an `open` question; answered by the `question` push (since 1.97.0) | client → daemon |

**Connection-scoped state.** The active session and the `repo` belong to one connection, so a reconnect starts with no active session and with the daemon's default `repo`. A `repo` frame sets the repository that new sessions and pods submitted on this connection are cut from, and it answers with the resolved path. An empty value clears it, so new sessions then share the daemon's checkout instead of getting a worktree. Every path a frame carries is on the daemon's machine (finding F3).

A `session` frame is emitted on **every state transition** of every session, and the daemon sends
the full `sessions` list once on connect. So the picture is always live, including after a daemon
restart — you never need to poll.

#### 2.2.1 Choosing the provider and model (new in 1.3.0)

```json
{ "type": "providers" }
{ "type": "providers", "data": "[{\"id\":\"opencode\",\"default\":true,\"defaultModel\":\"opencode/minimax-m3.1-flash-free\"},{\"id\":\"claude\",\"default\":false,\"defaultModel\":null}]" }
{ "type": "new", "data": "{\"provider\":\"claude\",\"model\":\"sonnet\"}" }
{ "type": "prompt", "data": "fix the failing test" }
```

| `ProviderInfo` field | Type | Meaning |
| --- | --- | --- |
| `id` | string | the value `new`'s `provider` takes |
| `default` | boolean | the provider a spawn gets when it names none; `ready` and `/health` call it `provider` |
| `defaultModel` | string or null | the model its runs use when a spawn names none, as the daemon resolves it now; null = the CLI's own configured model |

`SpawnOptions` has two optional string fields, `provider` and `model`; 1.4.0 adds a third, `agentId` (section 2.2.2). An absent `provider` is the default provider; an absent `model` is that provider's default model, and the session's `model` is then null.

- **The choice belongs to the connection**, like `repo`, and holds for every session the connection creates until the next `new`. A session keeps its provider and model for its whole life, every resume included, and every `session` frame names both (`provider`, `model`), so a UI can label it.
- **A choice the daemon cannot honour is refused, never substituted.** An unknown provider, an unknown key, or a model that could change the CLI command line (a leading `-`, whitespace, a double quote, a control character, more than 200 characters) is answered at once with `error` (`new refused: …`). Until a later `new` is accepted (an empty one included), every prompt that would start a session on this connection is answered with `error` (`no session started: …`). A prompt that names its `sessionId` is unaffected.
- **`new`'s JSON is decoded strictly**, the one exception to the compatibility rule in section 2: a client command the daemon does not fully understand is refused rather than half-applied, so a misspelt option, or one from a newer contract, cannot quietly fall back to the default provider. A client written for a later contract reads the version in `ready` and sends only the options that version defines.
- **The daemon checks a model's syntax, not its existence.** Whether a model exists is the CLI's answer: an unknown but well-formed model fails visibly in its run (`error`, then `finished` with `outcome: FAILED`).

#### 2.2.2 Agent profiles (new in 1.4.0)

A profile is a template the daemon stores: who an agent is (`name`, `role`), where it runs (`provider`, `model`) and its persona (`instructions`). It is not a session. Since **1.30.0** a `new` that names a profile creates **the** session at that moment, bound to the profile; before 1.30.0 it created one at the first prompt.

```json
{ "type": "agent_save", "data": "{\"id\":\"backend\",\"name\":\"Backend\",\"role\":\"backend\",\"provider\":\"claude\",\"model\":\"sonnet\",\"instructions\":\"You are the backend agent.\"}" }
{ "type": "agent", "data": "{\"id\":\"backend\",\"name\":\"Backend\",\"role\":\"backend\",\"provider\":\"claude\",\"model\":\"sonnet\",\"instructions\":\"You are the backend agent.\"}" }
{ "type": "agent_list" }
{ "type": "agents", "data": "[{\"id\":\"backend\",\"name\":\"Backend\",\"role\":\"backend\",\"provider\":\"claude\",\"model\":\"sonnet\",\"instructions\":\"You are the backend agent.\"}]" }
{ "type": "new", "data": "{\"agentId\":\"backend\"}" }
{ "type": "prompt", "data": "add the endpoint" }
```

| `AgentProfile` field | Type | Rules |
| --- | --- | --- |
| `id` | string; optional on `agent_save` | absent or blank: the daemon picks one (`agent-` and 8 characters). Otherwise a letter or digit, then up to 63 letters, digits, `.`, `_` or `-`. No `:`, which is reserved |
| `name` | string | required: the display name, at most 100 characters, no control characters |
| `role` | string | required: a lowercase letter, then up to 31 lowercase letters, digits or `-` (`backend`, `qa`, `frontend-web`) |
| `provider` | string or null | an id `providers` lists; null = the default provider |
| `model` | string or null | a model of that provider, checked like `new`'s `model`; null = the provider's default |
| `instructions` | string or null | the persona, at most 20,000 characters, put ahead of the first prompt of every session spawned from the profile |

`agent` and `agents` carry every field, nulls included, exactly as stored: trimmed, `role` and `provider` lowercased, a blank optional field as null.

- **`agent_save` creates or overwrites, and answers with the stored profile.** An overwrite keeps the profile's place in the list. A profile the daemon could not spawn from (a provider it is not configured with, a model `new` would refuse) is refused when saved, never stored to fail later. A refusal is `error` (`agent_save refused: …`) and stores nothing. Its JSON is decoded strictly, like `new`'s: an unknown key is refused.
- **`agent_list` answers with every profile, oldest first; `agent_delete` deletes one and answers with what is left.** An unknown id is `error` (`agent_delete refused: no agent profile '<id>'`). Deleting or editing a profile changes no existing session: a session keeps the provider, model and attribution it was spawned with.
- **These answers go only to the client that asked.** Nothing is broadcast, so a client that shows profiles asks again with `agent_list` when it needs a fresh list.
- **`new {"agentId": …}` spawns from a profile.** Since 1.30.0 this creates **the** session immediately, bound to the profile's `agentId`, `role`, `projectId` and persona, and every later prompt runs that session. The daemon reads the profile at that moment, so an edit made after `new` still applies, and a profile deleted since is answered with `error` (`no agent profile '<id>' …`) and starts nothing. An unknown `agentId` in `new` itself is `new refused: …`, with the same effect as any refused `new` (section 2.2.1). Before 1.30.0 the session was created at the first prompt and the profile was re-read then.
- **`new {"projectId": …}` scopes the session (1.30.0).** Carried on `SpawnOptions` (section 2.2.1) and recorded on the session. Absent or null keeps the old behaviour; it does not filter anything by itself, it is what a roster or role-based mail later filters on.
- **What the session runs on.** Provider: `new`'s `provider`, else the profile's, else the default. Model: `new`'s `model`, else the profile's, else the provider's default. The profile's model is used only while the session runs on the profile's own provider, so a `new` that moves a profile to another provider gets that provider's default model.
- **The persona is the first prompt's prefix.** A session's first prompt is the profile's `instructions`, a blank line, then the client's prompt. Later prompts, resumes and mail deliveries carry only their own text, because the CLI's own session already holds the persona.
- **Every `session` frame says whose session it is.** `SessionRecord` gains `agentId` and `role`, copied from the profile at spawn time and kept for the session's life. Both are null for a session spawned without a profile, and on every record an earlier daemon wrote.

#### 2.2.3 Backlog (new in 1.5.0)

The daemon owns a board of tickets. The requests below edit it, and every change is pushed to every connected client (see "Answers and pushes").

**`Ticket`** (every field is always present):

| Field | Meaning |
|---|---|
| `id` | made by the daemon: `ticket-` followed by 8 hex characters |
| `title` | trimmed, 1 to 200 characters, no control characters |
| `description` | trimmed, at most 100,000 characters; `""` when empty |
| `ticketType` | `null`, or one of `enhancement`, `defect`, `restructure`, `upkeep`, `documentation`, `cosmetic`, `performance`, `testing`, `hardening` (case-insensitive on input; a pre-1.91.0 spelling is accepted and stored under its replacement) |
| `epicId` | `null`, or the full id of the epic the ticket is filed under (1.31.0) |
| `status` | the column: `backlog`, `todo`, `in_progress`, `pending` or `done`, in that board order (`backlog` since 1.131.0) |
| `position` | the place in its column, 0 at the top; a column's positions are always 0, 1, 2… |
| `wontFix` | `true` only on a `done` ticket closed as won't fix |
| `links` | `{ "type", "ticketId" }`, seen from this ticket's side, oldest first |
| `heldBy` | the ids of its open `blocked_by` prerequisites (open: not `done`, or `done` as won't fix) |
| `holdReason` | `null`, or why it is held, e.g. `waiting for A, B; C was closed as won't fix` |
| `createdAt`, `updatedAt` | epoch milliseconds. `updatedAt` moves when the ticket's own content, column or `wontFix` changes, when a client places it, and when the daemon moves it between `todo` and `pending`; not when other tickets renumber it, and not for a link |

**Requests**

- `ticket_save`, a `TicketSave`: `{ "id"?, "title"?, "description"?, "ticketType"? }`. Without `id` it creates a ticket (a `title` is required) at the bottom of `todo`. With `id` it edits that ticket; an unknown `id` is refused, so an edit racing a delete never brings the ticket back. A missing field is left as it is; `""` clears `description` or `ticketType`.
- `ticket_get`: data is a ticket id.
- `ticket_list`: empty data; answered with the whole board. A bare project id lists only that project's tickets. Since 1.66.0 the data may be `{ "projectId"?, "epicId"? }`: only the tickets matching every given filter, in board order; an unknown epic answers an empty list, not an error.
- `ticket_move`, a `TicketMove`: `{ "id", "status"?, "position"?, "wontFix"? }`. Without `status` the ticket stays in its column. Without `position` it goes to the bottom of a new column, or stays where it is in its own. A `position` past the end means the bottom; a negative one is refused.
- `ticket_link` and `ticket_unlink`: `{ "ticketId", "relatedId", "type" }`, answered with the `ticketId` ticket.
- `ticket_reorder`: `{ "status"?, "ids": [...] }`. The listed tickets go to the top of their own column, in the given order; the others keep their order. With `status`, every listed ticket must already be in that column: a reorder never changes a column (`ticket_move` does).
- `ticket_delete`: data is a ticket id. Allowed in any column; the ticket's links go with it.

**Columns and `pending`**

- `backlog` (1.131.0) is limbo: potential work parked until someone deliberately promotes it to `todo` (the current sprint). The daemon never moves a ticket into or out of `backlog`; `ticket_move` does, in both directions, by hand. A backlog ticket is never started: see "What changed in 1.131.0".
- `pending` is derived. A `todo` ticket with an open `blocked_by` prerequisite moves to `pending`. When nothing holds it any more, it moves back to the bottom of `todo` in the same change.
- The daemon never moves an `in_progress` or `done` ticket; `heldBy` still says what holds it. A held ticket moved back to `todo` lands in `pending`.
- A move to `pending` is refused unless an open prerequisite holds the ticket. A held ticket cannot leave `pending`, whatever the target column.
- In 1.5.0, a move to `in_progress` was refused. In 1.6.0, see section 2.2.4: it hands the ticket to an assigned profile.
- `wontFix: true` needs `status: done`. Leaving `done` clears it, and `wontFix: false` on a `done` ticket makes it plain done. A prerequisite closed as won't fix keeps its dependents held.

**Links**

| Requested | Stored as |
|---|---|
| A `blocked_by` B, A `depends_on` B | A `blocked_by` B |
| A `blocks` B | B `blocked_by` A |
| A `child_of` B / A `parent_of` B | A `child_of` B / B `child_of` A |
| A `duplicates` B / A `duplicated_by` B | A `duplicates` B / B `duplicates` A |
| A `relates_to` B | one link, shown on both tickets |

Each ticket lists its links from its own side: when A is `blocked_by` B, B lists `blocks` A. Only `blocked_by` holds a ticket. `blocked_by` and `child_of` may not form a cycle, and no ticket can be linked to itself. Linking an existing link changes nothing; unlinking a link that does not exist is refused.

**Answers and pushes**

- `ticket_save`, `ticket_get`, `ticket_move`, `ticket_link` and `ticket_unlink` are answered with `ticket`; `ticket_list`, `ticket_reorder` and `ticket_delete` with `tickets`.
- Every committed change is pushed to every connected client: one `ticket` for each ticket the change touched (the requested one first), or one `tickets` after a reorder or a delete. For the client that asked, that push is the answer: it gets no second copy.
- A request that changed nothing, and every `ticket_get` and `ticket_list`, is answered to its sender only.
- Nothing is pushed on connect: send `ticket_list` to load the board.
- A refused request changes nothing and is answered with `error`, reading `<frame> refused: <reason>`. Examples: `ticket_link refused: B blocked_by A would close a cycle: A blocked_by B already`; `ticket_save refused: malformed ticket_save payload: …` (unknown keys are refused). An unexpected daemon failure reads `<frame> failed: <reason>`.

#### 2.2.4 Ticket hand-out and controlled context (new in 1.6.0)

Story 9.3 extends the daemon-owned backlog. `ticket_move` to `in_progress`, `ticket_start_next`, and a queue drain all call one serialized hand-out path; clients do not start sessions directly.

**New `Ticket` fields** (all are serialized, including nulls):

| Field | Meaning |
|---|---|
| `agentId` | A profile id, `role:<canonical-role-id>`, or null when deliberately unassigned. With no explicit value on create, the first profile's role is used when a profile exists; `""` leaves the ticket unassigned. |
| `provider`, `model` | Nullable ticket overrides. Resolution is ticket → profile → daemon default; an invalid provider/model is refused, never substituted. |
| `useWorktree` | Nullable: when unset, a start uses a worktree when the daemon has `AGENTHQ_REPO`, or else when the daemon's working directory is inside a git checkout, which is then the repo (BUG-WT-1); `true` requires a configured repo and fails closed; `false` runs in the daemon checkout, and is refused when that is a git checkout (BUG-WT-1). A worktree's `ticket/` branch is cut from the base ref's COMMIT (`AGENTHQ_BASE_BRANCH`, else `HEAD`): uncommitted changes in any checkout are not on it and never refuse a start (BUG-REGRESS-1). |
| `worktreeBranch` | Optional branch-name hint; the daemon/worktree manager chooses the actual unique branch. |
| `includeLinkedTasksContext` | Nullable Boolean override of `context.includeLinkedTasksContext`. Null inherits the project setting. |
| `queuedFor`, `queuedAt` | The profile id or `role:<role>` lane and enqueue time while a ready ticket waits for capacity. Cleared when the ticket leaves `todo`, its assignee changes, or the daemon restarts. |
| `sessionId` | The session created for this ticket. |
| `executorOverrides` | `{ "provider": string|null, "model": string|null }`, the ticket-only pins. |
| `effectiveExecutor` | `{ "agentId", "role", "provider": {"value","source"}, "model": {"value","source"} }`; sources are `ticket`, `profile`, `role` or `default`. A role assignment without a concrete pin may report a null value with source `role`. |
| `effectiveContext` | `{ "includeLinkedTasksContext": {"value": boolean, "source": "ticket"|"project"|"default"}, "includeSkillsContext": {same shape} }` (`includeSkillsContext` since 1.55.0). |
| `skillIds` / `skillsMode` / `includeSkillsContext` | 1.55.0: attached skill ids (absent keeps, `[]` clears, unknown ids refused), `merge` or `replace`, and the nullable skills switch (null inherits `context.includeSkillsContext`). |
| `lastRun` | The latest completed ticket run: `{ "sessionId", "startedAt", "endedAt", "outcome", "detail" }`. The ticket remains `in_progress` when its run ends; no failed run is restarted automatically. |
| `start` | Present on a start answer: `{ "ok", "reason", "detail" }`. Reasons are `started`, `queued`, `held` and `start_refused`; only `started` has `ok: true`. `launch_blocked` is reserved for a later version. On `started`, `detail` is free text: `cut from <ref> <short sha>` when the start cut the ticket's branch (absent when it reused one), then any `launch warning: …`, joined by `; `; null when there is neither (BUG-REGRESS-1, no version bump: a client reads `detail` as text). |

**Hand-out rules.** The daemon re-checks open `blocked_by` prerequisites before every start. A held ticket answers with `start.reason = "held"` and the open ids. A named profile has capacity one; `role:<id>` selects the first free matching profile. A permanently unresolved assignment is `start_refused`, never queued. When a valid lane is busy, `ticket_move` returns `queued`, leaves the ticket in `todo` and records `queuedFor`/`queuedAt`. `ticket_start_next` never queues: it skips held or busy lanes and starts the top eligible ticket; if none is startable it answers `error` `ticket_start_next refused: nothing startable`, followed, when tickets were tried, by why each of the first three did not start (`: <id> "<title>" did not start: <reason>; …`) (BUG-REGRESS-1). When a ticket session ends, its outcome is stored in `ticket_runs`, surfaced as `lastRun`, and the daemon drains that profile's queued lane. Restart clears queue marks and starts nothing.

**Controlled context.** The prompt is composed from the selected profile's non-empty `instructions`, the ticket title and description verbatim, and (when enabled) one linked-ticket line per link with its type, id, title and status. Linked-ticket bodies, including a parent epic's body, never enter `ContextInput` or the prompt. The default for `context.includeLinkedTasksContext` is true; a ticket override takes precedence over the project setting. `SessionRecord.ticketId` attributes the session, and `SessionRecord.contextSources` reports the character count for each included source, in prompt order: `agenthq`, `team`, `instructions`, `identity`, `traits`, `knowledge`, `skills`, `ticket`, `linkedTickets` (`identity` and `agenthq`/`team` were added later without a bump; `traits`, the session's frozen standing rules, is TRAITS-T5 and appears only when the instance has traits; `knowledge`, a pointer to the project's knowledge base in the daemon with its live note, active-decision and open-issue counts, is P10-K9 and appears only when the ticket has a project; `skills` is P10-K15: the started session's frozen attached skills in full (at most 24 000 chars, a skill that does not fit drops whole to its `name: description` line and `agenthq skill show`, a deleted one prints `(deleted)`), then one `name: description` line per other skill that is not `attachedOnly` (at most 40 lines and 6 000 chars, then `N more: agenthq skill list`); it appears only when the session has an instance, the effective `includeSkillsContext` is true and there is something to list, and the same section follows `knowledge` on every other first-turn path; a client must tolerate an unknown source). Project-memory contents, a launch gate and an agent-facing "done" route are not part of 1.6.0.

**Settings frames.** `settings_get` with empty data returns the full settings object; with a key it returns that key. `settings_set` accepts `{"key":"context.includeLinkedTasksContext","value":<boolean>}` and answers with a `settings` object. The key set is closed and a wrong JSON type or unknown key is refused with `error`. The setting is durable across daemon restarts.

#### 2.2.5 MVP Sprint-Zero: project, stories and the story board (published in 1.8.0)

MVP Sprint-Zero I0 integrated the project and story slices. These additive frames and the ticket
`projectId` fields are part of the published 1.8.0 contract; the client may use them against a 1.8.0
daemon. Existing frame names and meanings are unchanged.

**Project** (implemented by slice A, branch `feature-agenthq-mvp-scope`):

| Type | Shape |
|---|---|
| `Project` | `{ "id", "name", "goal": "", "directory": "", "baseBranch": "" }`. `id` is chosen by the daemon and is stable across restarts and edits. `directory` (1.36.0) is the absolute directory the project lives in, empty = none. `baseBranch` (1.81.0) is the branch the project's ticket starts cut from, empty = none (set only with `project_base`). (`storyIds` removed in 1.29.0.) |
| `ProjectBaseRequest` | `{ "projectId", "branch"?, "clear"? }` (1.81.0): `project_base`, answered by `project`. A branch sets it (refused unless it resolves where tickets are cut), `clear` removes it, neither reads it. |
| `ProjectSave` | `{ "id"?, "name"?, "goal"?, "directory"? }`: no `id` creates, a known `id` edits, an unknown `id` is refused. A null `directory` keeps it, an empty one clears it, anything else must be an absolute path to an existing directory or the save is refused. |
| `Ticket` gains | `projectId`: nullable, the project the ticket belongs to; null = not in a project. |
| `TicketSave` gains | `projectId`: the same key on the create/edit request. |

Frames (1.8.0): `project_save` and `project_get` are answered with `project`; `project_list` (empty
data) with `projects`. Refusals follow the standing convention (`<frame> refused: <reason>`).

**Story board** (implemented by slice B, branch `feature-agenthq-mvp-board`):

| Type | Shape |
|---|---|
| `Story` | `{ "id", "projectId", "title", "status" }`. |
| `StorySave` | `{ "id"?, "projectId"?, "title"?, "epicId"? }`: a create needs a `title` and the `projectId` it belongs to, and lands in `todo`; an optional `epicId` (1.22.0) also joins that epic and is ignored on an edit. |
| `StoryMove` | `{ "id", "status"? }`: null = stay in its column. There is no `position`. |

The status vocabulary is `StoryStatus` — deliberately minimal, a board and not a workflow engine:
`todo`, `doing`, `done`, in that frozen order. This is NOT `TicketStatus`: the daemon's backlog
columns are a different board with a different life. An unknown status is refused, never silently
normalised.

**DEPRECATED in 1.62.0 (E1-c):** every story frame below is a shim over the ticket board, and `Epic.storyIds` is derived from the tickets filed under the epic; see "What changed in 1.62.0". The rules below describe the frozen wire shape, not the storage.

Frames (1.8.0): `story_save`, `story_get` and `story_move` are answered with `story`; `story_list`
(empty data) with `stories`, grouped by status in the frozen order.

`Story` is deliberately minimal: there is no `description`, no `position`, and no `createdAt` /
`updatedAt`. A story is a lightweight work item, not the daemon's own backlog ticket.

**Stories hang off epics (1.29.0).** A story names its project by `projectId`; the project keeps no
list of its stories (`Project.storyIds` was retired in 1.29.0). A `StoryMove` carries no `position`
and a story carries no creation timestamps.

**The epic join (1.22.0; derived since 1.62.0, see above).** `Epic.storyIds` is the ONLY ordered list of stories. A `story_save`
create that carries an `epicId` calls `EpicStore.appendStoryId`: the epic's list grows by the new
story id, an epic id that names no epic is a no-op, and the changed epic is pushed as an `epic`
frame to every client. An edit never touches it.

#### 2.2.6 Roles (1.12.0)

A `Role` is what a client needs to paint a role badge and to show what the role is for:

```json
{ "id": "backend", "name": "Backend Ninja", "color": "#0ea5e9", "persona": "You are …", "source": "CUSTOM" }
```

| Field | Meaning |
| --- | --- |
| `id` | role id, `[a-z][a-z0-9-]{0,31}` — the same id agent profiles and sessions carry in `role` |
| `name` | display name |
| `color` | `#rrggbb`, ready for a theme; never needs parsing |
| `persona` | markdown describing the role (may be empty) |
| `source` | `BUILT_IN` (bundled manifest or canonical id), `CUSTOM` (saved with `role_save` or an operator file) or `GENERATED` |
| `icon` | 1.93.0: a client icon key (`shield`), or `""` for none (the client paints its default for the role) |

`role_save` takes a `RoleSave`, decoded **strictly** (an unknown key is refused):

```json
{ "id": "data-wrangler", "name": "Data Wrangler", "color": "#22c55e", "persona": "Clean the data." }
```

`color` is optional (omitted keeps the role's current colour, or a generated one) and `persona` defaults
to empty. A canonical id overrides that built-in role on this daemon; any other valid id adds a role.
Refusals name the field: an invalid id, a blank or over-long (60) name, a colour that is not `#rrggbb`,
or a persona over 20,000 characters. Custom roles are files in the daemon's roles directory
(`AGENTHQ_ROLES_DIR`, else `roles/` in its state directory), so they survive a restart; a client never
reads that directory itself.

### 2.3 The raw stream (kept, not part of what you render)

| Type | `data` | Meaning |
| --- | --- | --- |
| `stdout` | the provider's own line, verbatim | raw passthrough, for debugging and for anyone who wants the bytes |
| `stderr` | the provider's own line, verbatim | diagnostics, hook noise |
| `exit` | the exit code as a decimal string | the session's hosted process ended without the daemon asking (a crash), during or between turns (1.64.0); a turn's end is `finished` |

**These are not going away, but you should not render them.** They exist so an existing client keeps
working untouched, and so the provider's exact output is never lost. Rendering them gives you a
duplicate transcript, and their content is provider-specific — the very thing this contract removes.

Both streams are derived from the *same* lifecycle event, so they can never disagree: `text_delta`
is a translation of the matching `stdout` line, never a second source of truth.

### 2.4 COMMS (Phase 6)

Agent-to-agent messaging rides the same socket. A message reaches an agent by **resuming its
session with the message as the next prompt** — delivery and "the console came back" are the same
event.

| Type | `data` | Direction |
| --- | --- | --- |
| `mail_send` | a `MailSendRequest` (JSON) | client → daemon |
| `mail_read` | `"<messageId>"` | client → daemon |
| `mail_inbox` / `mail_outbox` | `"<sessionId>"`, blank = this connection's active session | client → daemon |
| `mail_thread` | `"<threadId>"` | client → daemon |
| `mail_threads`, `mail_waiting` | — | client → daemon |
| `mail` | one `Message` (JSON) | daemon → client |
| `mails` | list of `Message` | daemon → client |
| `mail_threads` | list of `ThreadSummary` | daemon → client |
| `mail_waiting` | list of `WaitingOn` | daemon → client |
| `mail_refused` | a `MailRefused` (JSON) with the reason | daemon → client |

Two things worth knowing as a UI author:

- **`mail_refused` is never a silent drop.** A per-sender rate limit (12 / 10 min) and a
  per-thread ceiling (60 messages) both refuse *visibly* and tell you why, before anything is
  stored. Show the reason.
- **`mail_waiting` is filtered to senders actually at rest.** An agent mid-run is not "waiting on
  you". A false waiting signal erodes trust faster than no signal, so the daemon refuses to emit one.

### 2.5 The pod command surface (new in 1.1.0)

A **pod** is a directed graph of nodes the daemon schedules itself: each node is one run, a node
may wait on others, the pod caps how many run at once, and a node may retry. This is the
orchestration entry point. Before 1.1.0 the orchestrator existed inside the daemon and nothing
outside it could reach it.

Three commands, one answer frame, all of it on the same socket.

| Type | `data` | Direction |
| --- | --- | --- |
| `pod_submit` | a `PodSubmitRequest` (JSON) | client to daemon |
| `pod_query` | `"<podId>"` | client to daemon |
| `pod_cancel` | `"<podId> [force]"` | client to daemon |
| `pod` | a `PodStatus` (JSON) | daemon to client |

One answer frame for all three, because all three are answered by the same question: what is this
pod's state NOW. You never have to wait for a later push to learn the id of a pod you just
submitted; it comes back in the `pod_submit` answer.

#### `pod_submit` - run a pod

```json
{
  "type": "pod_submit",
  "data": "{\"goal\":\"ship phase 6\",\"maxConcurrency\":2,\"nodes\":[{\"name\":\"build\",\"prompt\":\"...\",\"maxAttempts\":2},{\"name\":\"check\",\"prompt\":\"...\",\"dependsOn\":[\"build\"]}]}"
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `goal` | string | free text; shown in the review queue, never parsed |
| `nodes` | array | at least one, each `{name, prompt, dependsOn?, maxAttempts?}` |
| `nodes[].dependsOn` | array of strings | other nodes NAMES in this same pod; forms the DAG |
| `nodes[].maxAttempts` | number | total attempts INCLUDING the first; default `1`, meaning no retry |
| `maxConcurrency` | number | how many nodes may run at once; default `1` |

The client says WHAT to run. It does not say WHERE: there is no repository, working directory or
base-ref field on the wire, and there will not be one. Those are the daemon's `AGENTHQ_REPO`
and its own working directory. The exception is the connection's `repo` (section 2.2): `pod_submit` runs in the repository that connection has set, and any authenticated client may set it (finding F3).

Validation happens before anything is written. An unknown dependency, a duplicate or blank node
name, a blank prompt, `maxAttempts < 1`, `maxConcurrency < 1` or a cycle are all refused with an
`error` frame naming the violation, and **no pod is created**.

#### `pod` - the answer

`data` is a JSON `PodStatus`:

```json
{
  "pod":   { "id": "9f2c...", "goal": "ship phase 6", "state": "RUNNING", "maxConcurrency": 2, "createdAt": 1, "endedAt": null },
  "nodes": [ { "id": "a1b2", "podId": "9f2c...", "name": "build", "specIndex": 0, "state": "SUCCEEDED", "attempts": 1, "reviewState": "PENDING", "detail": null, "createdAt": 1, "endedAt": 2 } ],
  "runs":  { "a1b2": [ { "id": "r1", "sessionId": "s1", "provider": "opencode", "state": "IDLE", "exitCode": 0, "startedAt": 1, "endedAt": 2 } ] }
}
```

`runs` is keyed by node id and ordered by attempt, oldest first, so a retrying node's history is
in one place. A node with no attempts yet has an empty list.

Pod states:

| `state` | Meaning |
| --- | --- |
| `RUNNING` | scheduling is in progress |
| `CANCELLING` | a cancel was accepted; running nodes are being stopped |
| `CONVERGED` | every node succeeded. Terminal, and every node is queued for review |
| `FAILED` | ended with at least one node failed. Terminal |
| `CANCELLED` | ended by request. Terminal |
| `INTERRUPTED` | a daemon restart found it open. Terminal, and never resumed |

Node states:

| `state` | Meaning |
| --- | --- |
| `PENDING` | waiting on dependencies, or on a free concurrency slot |
| `RUNNING` | an attempt is in flight |
| `SUCCEEDED` | the attempt exited 0 |
| `FAILED` | retries exhausted, or the run could not be started |
| `CANCELLED` | cancelled by request, at pod or node level |
| `SKIPPED` | an upstream node failed or was cancelled, so this one never ran |

`detail` is an optional human gloss ("killed (forced)", "attempts exhausted (2/2)", "daemon
restarted mid-run"). Show it if you like; never parse it.

#### `pod_cancel`

`force` escalates the kill of the pod's running nodes from a graceful stop to a process-tree
kill. Without it the daemon still escalates on its own if a node ignores the grace window; the
flag only asks it not to wait first. A cancel on an already-terminal pod is a no-op that returns
the pod unchanged. An unknown id is an `error` frame.

**A cancelled pod is never retried and never resumed.** To run the work again, submit a new pod.

#### Three things worth knowing

- **`pod_query` with an unknown id answers `error`, not an empty `pod`.** A client that mistyped an
  id can tell that apart from a pod that genuinely has no nodes yet. A blank `pod_query` is also
  an `error`: pass a pod id.
- **There is no "list my pods" frame.** A pod id comes from its own `pod_submit` answer, and
  `GET /health` reports how many are open. Listing is deliberately not in this version.
- **`pod` is a pull, not a push.** Nothing announces pod progress on its own. Poll `pod_query`, or
  watch the per-run `session` / `status` / `finished` frames, which the daemon broadcasts exactly
  as it does for a pod submitted by any other route.

### 2.6 The recorded provider → neutral mapping

This is the mapping the daemon applies, in one place, once. It is recorded here so you can see
exactly what is *not* translated and why.

| Provider | Raw line shape | Becomes |
| --- | --- | --- |
| OpenCode | `{"type":"text","text":"…"}` or `{"type":"text","part":{"text":"…"}}` | `text_delta` |
| OpenCode | `{"type":"reasoning","text":"…"}` or `{"type":"reasoning","part":{"text":"…"}}` | `reasoning` (1.23.0) |
| OpenCode | `{"type":"error", …}` | `error` |
| OpenCode | `{"type":"tool","part":{"tool":"bash", …}}` | `status` with `tool` = `bash` (1.25.0) and `activity` = the salient argument from `part.state.input` (unreleased) |
| Claude | `{"type":"assistant","message":{"content":[{"type":"text","text":"…"}]}}` | one `text_delta` per `text` block, blocks joined by a newline |
| Claude | `{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"…"}]}}` | `reasoning` (1.23.0), in block order beside any `text_delta` |
| Claude | `{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash"}]}}` | `status` with `tool` = `Bash` (1.25.0) and `activity` = the salient argument from the block's `input` (unreleased), in block order beside any `text_delta` |
| Claude | `{"type":"result","subtype":"error_…"}` or `"is_error": true` | `error` |
| Claude | `{"type":"result","subtype":"success"}` | **nothing** — see below |
| any | non-JSON | **nothing** — see below |
| any | JSON without a recognised `type`, or unrecognised | **nothing** — still delivered on `stdout` |

Deliberately **not** mapped, and the reasons are worth reading because they are the decisions that
keep the contract stable:

- `step_start` / `step_finish` (OpenCode) and `system` (Claude) are provider *bookkeeping*. The
  daemon already derives lifecycle from process facts — spawned, produced output, exited — and a
  provider's "step" is not a session. Mapping them would let the provider's notion override the
  daemon's, re-introducing the inversion Phase 3 removed.
- A provider's claim to be **done** is never mapped to `finished`. `finished` comes from the
  process exit, because a provider that says it is done is still running until it exits, and
  treating the claim as the end is how a UI ends a run early.
- A session id on a line is never an event. The daemon force-persists it the moment it is observed,
  and you learn it on the `session` frame.
- **A non-JSON line is never mapped to a `text_delta`.** There is deliberately no "this looks like
  prose" rule: a guess that leaks a CLI's incidental chatter — a progress bar, a warning, an
  install line — into a UI transcript is worse than a missing line, and the line is still there
  verbatim on `stdout`. A provider that streams plain text gets an explicit row in this table,
  added on evidence rather than inferred by shape.

### Attention (new in 1.9.0)

Attention items are durable honesty records. They survive daemon restarts and are ordered oldest
first for replay. The same `AttentionItem` JSON shape is used for a push, an acknowledgement update,
the WebSocket list response and the HTTP list response.

| Direction | Frame | Data | Result |
| --- | --- | --- | --- |
| client → daemon | `attention_list` | empty | `attention_items` with a JSON array of every attention item |
| client → daemon | `attention_ack` | plain attention id | updated `attention` is pushed to every connected client; unknown ids answer `error` |
| daemon → clients | `attention` | one `AttentionItem` as JSON | emitted after an item is inserted or first acknowledged |

`kind` uses the stable lower-snake-case values `trigger_tripped`, `deadline`, `stalled`, `ceiling`,
`launch_blocked`, `prerequisite_declined`, `queue_cleared` and `unreported` (1.52.0). `at` and `ackedAt` are Unix epoch
milliseconds; `ackedAt` is null until acknowledged. Optional association ids are nullable and serialize
as null when absent.

---

## 3. The HTTP surface

Plain HTTP on the same host and port as the WebSocket. This is the surface a *spawned agent* uses
(a CLI agent can `curl`; it cannot speak WebSocket), and it is equally available to a UI.

| Method | Path | Body / query | Response |
| --- | --- | --- | --- |
| GET | `/health` | — | `Health` JSON: `status`, `provider`, `runner`, `sessions`, `worktrees`, `pods`, `runsInFlight`, `contract`, `providers`, `attentionOpen` |
| GET | `/attention` | — | ordered JSON list of `AttentionItem` records; same history as `attention_list` |
| POST | `/mail/send` | a `MailSendRequest` (JSON) | the stored `Message`, or 400 + `MailRefused` |
| POST | `/mail/read` | `{"messageId": "…"}` | the updated `Message`, or 404 |
| GET | `/mail/inbox` | `?session=<id>` | list of `Message` |
| GET | `/mail/outbox` | `?session=<id>` | list of `Message` |
| GET | `/mail/thread/{threadId}` | — | ordered `Message` list |
| GET | `/mail/waiting` | — | list of `WaitingOn` |

```json
{ "status": "ok", "provider": "opencode", "runner": "pipes", "sessions": 2, "worktrees": 1, "pods": 1, "runsInFlight": 1, "contract": "1.9.0", "providers": ["opencode", "claude"], "attentionOpen": 1 }
```

- **`providers` (1.3.0)** lists every provider a spawn may name, the default (`provider`) first. Ask the WebSocket `providers` frame when you also need each one's default model.
- **Every field 1.0.0 published is unchanged in name, type and meaning.** 1.1.0 added two.
  `pods` is how many pods have no end time, that is, what the orchestrator is still working on.
  `runsInFlight` is how many runs have no end time, that is, processes the daemon believes are
  running right now. Both are plain counts you can diff between polls; neither promises anything
  about the future.
- **`attentionOpen` (1.9.0)** is the number of durable attention items whose `ackedAt` is null.
- **There is no pod HTTP API in 1.1.0.** Pods are a WebSocket surface only (section 2.5). This
  HTTP surface is what a spawned CLI agent uses, and a spawned agent runs nodes, not pods.
- **`/health` is the one route answered without a token** when the daemon has one. Like every route it refuses an `Origin` header, and it discloses counts only (finding F4).

Notes that matter:

- The HTTP mail API reuses the **same** `Mailbox` and `MailRouter` as the WebSocket frames, so
  limits, receipts and delivery policy are identical whichever door a message came through.
- A send response carries the message's **current** state (delivered, or undeliverable with the
  reason), because an HTTP caller has no event stream to read it from.
- **Auth (Phase 7).** When the daemon has a token, every route except `/health` requires `Authorization: Bearer <token>`. Section 7 has the rules. Without a token the daemon binds loopback only.
- `AGENTHQ_MAIL_BASE` sets the base URL rendered into delivered prompts. Left unset, it follows the bind host, and a wildcard bind is reached at `127.0.0.1`.

---

## 4. Running against the stub

The stub lets you build and test the UI with **no provider CLI installed** — no OpenCode, no Claude,
no keys. It is selected the same way any provider is:

```bash
AGENTHQ_PROVIDER=stub .\gradlew.bat :superiorSoftLibrary:agentHq:runDaemon
```

or, from another terminal, against a daemon already running:

```bash
.\gradlew.bat :superiorSoftLibrary:agentHq:runClient
```

`AGENTHQ_PROVIDER=stub` boots the whole daemon — real Ktor, real sessions, real persistence, real
mailbox, real frames — with an in-memory scripted transport standing in for the agent CLI. The
scripted lines go through the **same** normaliser a real provider does, so the UI you build against
the stub renders a real provider unchanged. There is no second, divergent protocol to drift from.

The default script spawns a session, streams two text deltas, emits one stderr diagnostic, and
finishes clean. `StubScript.failing()` scripts the failure path (an `error` frame plus a non-zero
exit) when a test needs it.

**Test-only delay knob.** `AGENTHQ_STUB_DELAY_MS` (default `25`) sets the pause before each scripted
line, so a cancel can land mid-run over a real network. It changes timing only, never the protocol.
Keep `lines x delay` under 30 s (the in-memory runner's exit wait). No "hold until cancelled" mode
exists; a plain delay suffices.

**A scripted mail handoff.** Because delivery *is* a resume, the stub's mail behaviour is real and
needs nothing special: send a message to a stub session over `/mail/send` (or a `mail_send` frame),
and the router resumes that session carrying the rendered prompt — exactly as it would for a real
agent. The `mail` frames you then see are produced by the daemon's mailbox, not by the stub, so
inbox/thread/receipt rendering is exercised for real.

---

## 5. What is deliberately absent

- **`WAITING_INPUT` is a defined state that nothing produces today.** Neither CLI, run
  non-interactively with no TTY, ever signals "I need input" — it either finishes or blocks. The
  state exists so a future PTY transport or an HTTP provider can raise it without a protocol change.
  Render it as `BUSY` and you are correct either way.
- **A provider never invents lifecycle.** Every state except `WAITING_INPUT` is *observed* from
  process facts. There is no inference, and no provider can put a session into a state the daemon
  did not observe.
- **The raw provider line is still on `stdout`.** If you render only the four public events, your
  transcript is a subset of a client that also reads `stdout`. That asymmetry is intended: the
  neutral vocabulary is the curated view, `stdout` is the unedited one.
- **A pod is never resumed after a restart.** A pod interrupted by a daemon restart becomes
  `INTERRUPTED` and its runs become `ORPHANED`. Resubmit: the daemon will not adopt processes it
  cannot reattach to, and it will not silently re-run work whose context it no longer holds.
  Treat `INTERRUPTED` as final.
- **A pod runs every node on the default provider.** The 1.3.0 and 1.4.0 choices apply to sessions
  a connection starts with `prompt`; `pod_submit` has no provider, model or profile field yet.
- **A profile starts nothing by itself.** 1.4.0 stores profiles and spawns from one only when a
  client names it in `new`. Starting a session from a ticket, a per-profile concurrency cap, asking
  for "any profile with this role", and choosing how much context a session is given belong to a
  later minor version. A profile's `role` is a label today: nothing matches on it yet.
- **No accounts, and no transport encryption.** AgentHQ has no login and no user accounts; the bearer token (section 7) is the whole credential. The daemon does not terminate TLS, so the network path is assumed trusted (section 7.3).
- **Tickets have no `maxConcurrent`, skills or memory-context controls yet.** Capacity is fixed at one active session per profile; skills and project-memory context are later work.
- **A won't-fix prerequisite raises nothing yet.** Its dependents stay held and `holdReason` says why; bringing it to someone's attention arrives in a later version.

---

## 6. A minimal client, in order

```
connect → ready            (read the contract version; refuse a version you do not know)
        → sessions          (render the known sessions)
providers → providers       (optional, 1.3.0: fill a provider picker)
new {provider, model}       (optional, 1.3.0: choose where the next session runs; `error` = refused)
agent_list → agents         (optional, 1.4.0: fill an agent picker)
new {agentId}               (optional, 1.4.0: the next session runs as that profile; `error` = refused)
ticket_list → tickets       (optional, 1.5.0: load the board; later changes arrive as ticket / tickets pushes)
ticket_start_next → ticket  (optional, 1.6.0: start the top eligible ticket; the answer carries `start`)
settings_get → settings     (optional, 1.6.0: read project context settings)
ticket_move(in_progress) → ticket (1.6.0: `start` says started / queued / held / start_refused)
prompt → session STARTING   (your "spawn")
       → status STARTING
       → status BUSY        (start the spinner)
       → text_delta …       (append)
       → status IDLE
       → finished SUCCEEDED (stop the spinner; read `outcome`, not the code)
```


And if you drive pods (section 2.5), the loop is the same, one level up:

```
pod_submit  -> pod        (read the new pod id back from the answer)
pod_query   -> pod        (poll until pod.state is terminal)
pod_cancel  -> pod        (only if you mean it)
```

Everything under the `->` is a `text_delta` / `status` / `finished` sequence per node run, exactly
as above, so one renderer covers both a hand-driven session and a pod.
On error: keep the transcript, surface `error`, and treat a following `finished` with
`outcome: FAILED` as the end of that run.

---

## 7. Remote daemon: admission, transport and reconnect (Phase 7)

The daemon is local by default and remote-capable by configuration. **AgentHQ has no accounts and no login.** The one credential is a bearer token that the operator creates and shares. A bearer token rather than mTLS is a deliberate choice: it keeps the no-login promise and needs no certificate infrastructure.

### 7.1 Who is admitted

| Daemon configuration | Admitted | Refused |
| --- | --- | --- |
| bound to loopback, **no token** (the default) | a process on this machine whose `Host` names `localhost`, `127.0.0.1` or `[::1]` | any other `Host` (403) |
| bound to any address, **with a token** | every route with `Authorization: Bearer <token>`; `GET /health` with no credential | a missing or wrong token (401, with `WWW-Authenticate: Bearer realm="agenthq"`) |
| bound to a non-loopback address, **no token** | nothing: the daemon refuses to start and names the setting to change | startup itself |

On every route, in every configuration, a request that carries an `Origin` header is refused with 403. Browsers attach `Origin` to cross-site requests and no CLI or agent sends it, so this closes cross-site WebSocket hijacking.

- The token must be at least 32 characters, with no whitespace or control characters.
- The daemon keeps only a SHA-256 digest of the token and compares digests in constant time. The token is never printed, logged, put in a URL, or written into a prompt.
- A blank bind host counts as non-loopback, because most network stacks bind the wildcard address for an empty host. It therefore needs a token.

### 7.2 The token

| Where | How |
| --- | --- |
| daemon | `AGENTHQ_TOKEN_FILE` (its trimmed contents; a UTF-8 byte-order mark is stripped) or `AGENTHQ_TOKEN` (the value). Setting both is an error. |
| CLI client | `--token-file FILE`, or the same two variables when no endpoint is named |
| spawned agents | `AGENTHQ_TOKEN`, added to each agent's environment **only when the daemon has a token** |

An agent holds the daemon's full authority: it can call every route, including `repo`, mail and pods. The token therefore reaches agents as an environment variable and never as prompt text. A delivered prompt tells the agent to send mail with `$AGENTHQ_TOKEN`, so the literal token is not written into any prompt. Treat the token as operator-level: give it only to processes you would give the operator's shell to.

### 7.3 Transport: a trusted network is assumed, not guaranteed

The daemon does **not** terminate TLS. Over `ws://` and `http://` the token, every frame and every mail body cross the network in cleartext. AgentHQ **assumes the path between client and daemon is trusted, and makes no guarantee** against eavesdropping, tampering or replay on it. Where that path is not trusted:

- keep the daemon on loopback and reach it through an SSH tunnel, for example `ssh -N -L 8787:127.0.0.1:8787 user@daemon-host`, then point the client at `ws://127.0.0.1:8787/ws`. Keep the token anyway: the daemon sees every tunnelled connection as coming from `127.0.0.1`, so the source address is never used as an identity;
- or run the daemon on a VPN you control.

The CLI prints a warning before it sends a token over `ws://` to a non-loopback host. The CLI accepts `wss://` URLs, so a TLS proxy in front is conceivable, but this phase neither adds nor tests one.

### 7.4 Finding a daemon

There is no automatic discovery: a client is told where a daemon is. The CLI resolves the address in this order, and the first one present wins:

1. `--url ws://HOST:PORT/ws`;
2. `--endpoint NAME`, looked up in the endpoints file;
3. `--host` and/or `--port`, when either is typed (1.58.0, BUG-G2d: before, `AGENTHQ_URL` beat them);
4. `AGENTHQ_URL`;
5. the defaults, `127.0.0.1` and `8787`.

The endpoints file is `--endpoints FILE`, else `AGENTHQ_ENDPOINTS`, else `endpoints.json` in the state directory (`AGENTHQ_STATE_DIR`, default `~/.agenthq`):

```json
{ "endpoints": [ { "name": "b", "url": "ws://10.0.0.5:8787/ws", "tokenFile": "C:\\agenthq\\token-b.txt" } ] }
```

- Names are unique and non-blank. URLs start with `ws://` or `wss://`.
- The file names token **files**, never token values. A `token` key is ignored without an error, so keep secrets in token files.
- An endpoint never inherits the environment token. If it names no token file, it sends none, so one daemon's secret is never sent to another.
- To check that an address really is a daemon, `GET /health` answers without a token and reports its `status` and `contract`.

### 7.5 Several clients, several daemons

- **Several clients on one daemon.** Each connection is independent, and every connected client receives every frame the daemon broadcasts, for every session. Clients are not scoped: under one token they are one principal. The acceptance tests run a `watch` and a `cancel` on separate connections at the same time.
- **One client, several daemons.** Each CLI command talks to one daemon, selected with `--endpoint NAME`. Fanning one command out across daemons is not built.

### 7.6 Reconnect

- The CLI re-dials a dropped connection with backoff: 250, 500, 1000, 2000, 4000 and 8000 ms, about 16 seconds in all, then reports the connection lost. A refused token is never retried.
- `watch` survives a drop: it re-asks the daemon for the pod's state and keeps streaming. Session and pod state live on the daemon, so a drop loses nothing the daemon holds.
- Session-stream output is replayed after reconnect. The CLI sends a `replay_request` for each tracked session, with `sessionId` and JSON data `{"lastSequence": N}`; `lastSequence` is an exclusive cursor. The daemon replays retained session frames with greater sequence numbers in order. It keeps at most 256 frames per session, so older output can age out.
- If `lastSequence < oldestAvailableSequence - 1`, the daemon sends `replay_gap` before any retained replay frames. Its JSON data reports `requestedAfterSequence`, `oldestAvailableSequence`, `latestAvailableSequence` and `evictedCount`. The CLI surfaces this as a warning; frames evicted from the buffer cannot be recovered and are never silently skipped.
- **Restart boundary:** the 256-frame buffer is in-memory. Replay covers a socket re-dial while the daemon process retains that session's buffer; a daemon restart clears replay history. A no-replay-history response after restart is expected, not a contract failure.
- A `submit` is **never re-sent** after a drop, because the first copy may already have created the pod. The CLI reports the drop instead. Queries and `pod_cancel` are re-sent, because repeating them gives the same answer.
- **Connection-scoped state resets on reconnect.** A client that needs a session to continue names it in `sessionId` on every frame, and sets `repo` again.

### 7.7 Remote worktrees

A session on machine B has its worktree on machine B. Every path a frame carries (`worktreePath`, `repoRoot`, a `repo` answer) is a path on the **daemon's** machine. The CLI prints ids and never opens a path, and a client on another machine must not assume a drive letter, a separator or a shared filesystem. The daemon's default repository is `AGENTHQ_REPO`, and a `repo` frame changes it for one connection (finding F3). The daemon runs the `git` CLI, so `git` must be on the daemon's `PATH`.

### 7.8 Deployment

The step-by-step deployment, the Windows path quirks and the firewall rule are in the README, under "Remote daemon (Phase 7)".

---

## 8. Phase 1 findings

The ticket's rule is that any protocol change found while building Phase 7 is a finding about Phase 1, and is recorded here.

### F1: a client frame's `sessionId` was ignored (changed in 1.2.0)

- **Found:** Phase 7, with several clients on one daemon. Section 2 documents `sessionId` on client frames as the way to address a session, but the daemon ignored it on `prompt`, `mail_send`, `mail_inbox` and `mail_outbox` and always used the connection's active session.
- **Effect:** a prompt that named session B, sent on a connection attached to session A, ran in A. A reconnecting client's prompt started a new session, because a new connection has no active session.
- **Change:** `sessionId` is honoured on those four frames (1.2.0). A missing or non-resumable id is answered with `error`, never forked. On `mail_send`, a blank `from` takes the frame's `sessionId`.
- **Protocol change:** yes, a behaviour change to four existing frames. The version moved from 1.1.0 to 1.2.0.

### F2: reconnect replay (fixed in 1.7.0)

- **Found:** the reconnect test. A client that re-dialed received only frames produced after reconnect, losing output emitted while its socket was down.
- **Change:** every session-stream frame (`session`, `status`, `stdout`, `stderr`, `exit`, `text_delta`, `error` and `finished`) now carries a per-session `sequence`, strictly increasing from 1. The daemon retains at most 256 frames per session. Control and other non-session frames are not sequenced or buffered.
- **Request:** on reconnect, the client sends `replay_request` with the target `sessionId` and JSON data `{"lastSequence": N}`. The cursor is exclusive: the daemon returns the original retained session frames whose sequence is greater than `N`, in order. The CLI advances its cursor only as frames are delivered and suppresses duplicates at or behind it.
- **Oversized gap:** if `requestedAfterSequence < oldestAvailableSequence - 1`, the daemon sends a `replay_gap` frame first, with JSON data `{ "requestedAfterSequence": N, "oldestAvailableSequence": A, "latestAvailableSequence": B, "evictedCount": C }`, followed by the retained frames. The CLI prints the gap details as a warning. Evicted frames are unrecoverable, but their loss is explicitly reported and never silently skipped.
- **Protocol change:** additive sequence and replay frames; the published contract moved from 1.6.0 to 1.7.0. The four existing rendered events and their payloads are unchanged.
- **Restart boundary:** replay is limited to reconnects served by the same daemon process. The volatile replay buffer is not persisted; after a daemon restart, replay history is unavailable.

### F3: the `repo` frame chooses a daemon-side path for any authenticated connection (documented, not restricted)

- **Found:** Phase 7, when the daemon gained an authenticated remote surface. Section 2.5 said a client cannot name a path on the operator's disk, but the `repo` frame in section 2.2 names one, and the connection's `repo` also decides where its pods run. The two sections disagreed.
- **Decision:** an authenticated connection is operator-equivalent (it holds the token, and so do its agents), so `repo` grants nothing the token does not already grant. It is documented, not restricted to an allowlist. `repo` is per connection and resets on reconnect.
- **Consequence:** share the token only with a process you would give the operator's shell to.

### F4: `/health` is open and discloses counts (documented)

- **Found:** Phase 7, when the token gate was added. `/health` stays open so a client can find a daemon and read its contract without a credential.
- **Disclosure:** to any host that can reach the port, it reports the provider and runner ids and the counts `sessions`, `worktrees`, `pods` and `runsInFlight`. It discloses no paths, session ids, prompts or output.
- **Decision:** accepted. The counts are not secrets, and the route refuses `Origin` like every other. A daemon whose port should not reveal even that is bound to loopback and reached through a tunnel (section 7.3).

## 1.109.0 -- pair threads roll over (additive)

A new (non-reply) message on a full implicit pair thread lands in `pair:<a>|<b>:<n>` (n >= 2) instead of being refused; replies and explicit thread ids still hit the 60-message ceiling. `ThreadSummary` gains `group` (the `pair:<a>|<b>` id shared by all rolls; defaults to `id`).

## 1.110.0 -- headless daemon updates (additive)

Three authenticated HTTP routes (bearer token like every route but `/health`): `GET /update/status`, `POST /update/check`, `POST /update/apply`.

- `ServerUpdateStatus`: `version`, `contract`, `distribution` (`archive` | `docker` | `dev`), `canSelfUpdate`, `enabled`, `disabledReason`, `state` (`idle` | `checking` | `available` | `downloading` | `staged` | `error`), `available {version, notes}`, `lastChecked` (epoch ms), `error`, `instructions`, `runningAgents`, `staged`. `/update/status` reads memory only; `/update/check` asks the release source (GitHub or a local releases folder) and answers the same body.
- `POST /update/apply` takes `{"confirm": false}` and answers `UpdateApplyReply {result, version?, running?, message?}`: 200 `staged` (the verified archive is unpacked and made current; the restart is separate), 409 `agents_running` (resend with `confirm: true`), 409 `unsupported` (Docker or a dev run; `message` carries the manual instructions, e.g. the `docker pull`), 409 `nothing_to_apply`, 502 `failed`.
- A client compares its own version and contract with `version` / `contract` to warn on a mismatch. The update token is read from `AGENTHQ_UPDATE_TOKEN` / `INFANTRY_UPDATE_TOKEN` (or the keystore / credential store) and never appears in any reply.

## 1.111.0 -- per-project agent caps per role, LANE-CAPS (additive)

The board used to run ONE agent per role; a second ticket of the same role queued. Now each project stores, per role, the most agents that may run at once.

- `Project.laneCaps` (`{role: max}`, default `{}`): a role not listed runs 1 (today's behaviour). No upper bound in the daemon. Stored with the project (migration 46); an older daemon omits the key.
- `Project.autoRemoveDoneWorkers` (1.118.0, default false): a done ticket's worker session is ended and removed once its turn is over (migration 47).
- `project_config` request `{"projectId", "laneCaps"?}`: absent or empty `laneCaps` is a read; otherwise the entries are merged (a cap of 1 is the default and removes the entry; below 1 is refused as `project_config refused: ...`). Answered by the whole `project` frame, which is also PUSHED to every connected client when a cap changed.
- Ticket start/queue: a `role:<role>` ticket takes an idle agent of the role; if all are busy and fewer than the cap are running, the daemon adds another agent of the role (a copy of the first, named `<name> 2`, `<name> 3`, ... with its own agent id; each ticket already gets its own worktree). `queued` is answered only at the cap. Lowering a cap never stops a running agent; queued tickets drain as runs end, and a clone left idle above the cap is removed when a run of its role ends (the role's first agent never is). A clone keeps the template's persona, provider and model, and an idle agent is always reused before a new clone is made.
- CLI: `agenthq project config get <projectId>` / `project config set <projectId> <role> <n>`. MCP: `agenthq_project_config_get`, `agenthq_project_config_set` (`projectId`, `role`, `cap`).

## 1.112.0 -- `turnActive` on the session record (additive)

A session with no turn in flight but live background processes (`backgroundWork`, `bgJobs`) reads `state: BUSY` on the wire, for display. A client could not tell that from a turn generating, and refused the owner's message.

- `SessionRecord.turnActive` (boolean): true only while a turn is in flight (`STARTING`, `BUSY`, `WAITING_INPUT`), computed before the display rewrite. `state: BUSY` with `turnActive: false` is "idle, background running": input is accepted at once. While `turnActive` is true a client queues the owner's message (`queue`), it is never refused. An older daemon omits the key; a client then falls back to treating background work as not busy.

## 1.113.0 -- one usage definition for every harness, cost on `session_stats` (additive)

`session_stats` (1.92.0) now means the same thing for every harness (claude, pi, omp, opencode and its variants such as `opencode-go`, and any future one). Each harness has a small adapter (`UsageAdapters`) that turns its own record into ONE `RequestUsage {at, model, input, output, cacheRead, cacheWrite, cost?}`; only that record is folded into the stats, so the charts and numbers are directly comparable.

- **Request** = one model call, counted once, never per streamed update (Claude: one per `message.id`; Pi: one per assistant message; OpenCode: one per `step-finish`). A call that reports no tokens at all (aborted, synthetic) is skipped, for every harness: it is not a request and adds no flat point.
- **Context tokens** of a request = `input + cacheRead + cacheWrite` (what the model saw). The Stats "Context tokens" chart and `lastContextTokens` are this, per request.
- **Billed tokens, running total** (`billedTokens`, `StatsPoint.cumulativeBilledTokens`) = the cumulative sum of `input + cacheRead + cacheWrite + output` over the requests. Reasoning is never added.
- **Cost**: `StatsPoint.cumulativeCost` and `SessionStats.costTotal` (both new, nullable, default null) are the running total of the cost the HARNESS reported (Pi `usage.cost.total`, OpenCode `step-finish.cost`). Nothing is computed from a price table. null = the harness reports none (Claude): the UI shows "cost not reported". A reported 0 is 0.
- **Context limit** (`modelContextLimit`): from the model, by its local catalog (Pi `models-store.json`, OpenCode `models.json`); Claude, which states none, resolves the model id in those catalogs too. Not found = null, shown as "limit unknown"; never guessed from a name.
- **What filled the context** is derived identically (growth between requests, credited to the one tool of a step); tool names are shown as the harness reports them.
- No usage at all (no `series`): the UI says "no usage data for <harness>", never zeros or a flat line.
- A harness id that begins `opencode-` reads OpenCode's database.

### What changed in 1.129.0 (ASK-OTHER) — "Other" is on by default for owner questions

ADDITIVE; no new wire field (`question_answer.otherText` and `OwnerQuestion.allowOther`/`otherText` already carried free text since 1.97.0). `question_ask.allowOther` now DEFAULTS to `true` (absent = the owner may type an own answer; send `false` to keep a question option-only; MCP `agenthq_ask.allowOther`, CLI `ask --recommend ... --no-other`). An old client that sends `allowOther` explicitly is unchanged; an old UI ignores the Other row. The answer mail for a typed answer reads `Custom answer from the owner: <text>` (subject `Custom answer to question <id>: ...`), never `Chosen option`. The inline panel shows an "Other…" row at the end of the options: a multi-line field, Enter sends, Shift+Enter newline, Esc back to the options. `ready` reports `contract=1.129.0`.

### What changed in 1.130.0 (TODO-PROGRESS-1, ticket-aba6cdca) — the agent's to-do list drives the progress bar

ADDITIVE; `contract=1.130.0`. When a to-do tool call carries the agent's list (the 1.127.0 `status.todos`: claude `TodoWrite`, opencode `todowrite`, and now pi, whose every session loads a daemon-owned `todowrite` extension in opencode's shape), the daemon also sets the session's `progress` as `mode=count`, `done` = items done, `total` = items, `label` = `to-do`, `step` = the item in progress (else the first open one), and the new optional field **`SessionProgress.origin = "todo"`**. All items done = `state=done`; an empty list clears it. `step` may now also accompany a `count` bar (a client shows it after the count). Precedence, highest first: a RUNNING progress of the agent's own (`progress` op, `agenthq run` markers; `origin` omitted) is never overwritten by the list, and the agent's own next report replaces a `todo` bar outright (a fresh job); then the to-do bar; then the 1.123.0 sleep hint (`estimateSource=sleep`), which no longer replaces a to-do bar. Once the agent's own job finished, the next list change takes the bar back. An older daemon never sends `origin`; an older client ignores it and draws a plain count bar.

### What changed in 1.131.0 (BACKLOG, ticket-39eab724) — a Backlog lane before To Do

ADDITIVE; `contract=1.131.0`. A new ticket column `backlog` (`TicketStatus.BACKLOG`), first in board order: `TicketStatus.ALL` is now `backlog, todo, in_progress, pending, done`. Backlog is limbo (potential work that waits until someone promotes it); `todo` is the current sprint (committed work).

- **No migration.** `tickets.status` is TEXT with no CHECK; no stored row changes column. Existing parked tickets stay where they are until moved by hand.
- **Never started.** Every start path goes through the hand-out (`ticket_move` to `in_progress`, `agenthq ticket start` / `agenthq_ticket_start`, the UI's Start and a drop on In progress): a backlog ticket answers `start: {ok:false, reason:"start_refused", detail:"ticket is in the Backlog lane: move it to To Do (the sprint) before starting it"}` and stays in backlog. No "start next" logic (`ticket_start_next`, triggers, queue drain, lane caps) ever picks a backlog ticket: they read `todo` only.
- **Moves.** `ticket_move` to and from `backlog` is allowed by hand from any column. A held ticket may be parked from `pending` to `backlog` (it stays held; the daemon never moves a backlog ticket to `pending`, and moved back to `todo` it settles into `pending` again). The deprecated story shim reads `backlog` as `todo`.
- **Create.** `TicketSave.status` (new, create only): absent/null or `todo` = the sprint (unchanged default), `backlog` = the new lane; any other column, or a status on an edit, is refused. CLI: `agenthq ticket create ... --status backlog`; MCP: `agenthq_ticket_create` `status`.
- **Listing.** `agenthq tickets --status backlog`; `agenthq board` / `agenthq_board` print the Backlog group first.
- **Old clients.** A client built before 1.131.0 has no `backlog` column and simply does not render those tickets; nothing it sends changes meaning.

### What changed in 1.132.0 (BUILD-LIMITS, ticket-66153313) — worker builds run at a lower CPU priority, with caps

ADDITIVE + BEHAVIOURAL; `contract=1.132.0`. Builds that workers start no longer rely on the worker remembering to lower their priority:

- **Priority, enforced by the daemon.** Every worker session's root process (the harness the daemon spawns) starts at `limits.build.priority` (`normal` | `belownormal` | `idle`, default `belownormal`), and everything below it inherits that priority: the shell, `agenthq run` / `bg run`, `./gradlew`, the Gradle JVM and its test forks. Windows: `SetPriorityClass` plus a per-session Job object with a priority-class limit only, so a child cannot raise itself back to Normal (no CPU-rate cap; `BREAKAWAY_OK` is set so a child that asks to break away starts outside the job instead of failing). POSIX: the argv runs under `nice -n 10` (`idle` = 19). Any failure is logged once and never fails the session. `normal` turns all of this off.
- **Gradle caps in the session env.** Every worker run gets `GRADLE_OPTS` (the daemon's own value, then) `-Dorg.gradle.workers.max=<limits.build.maxWorkers>` (default logical cores − 2, minimum 1; `0` = no cap), `-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process` (the Kotlin compiler runs inside the session's lowered Gradle JVM, not in a shared Kotlin daemon that a Normal build may have started), and, when `limits.build.maxParallelForks` > 0 (default `0` = each build script's own default), `-Dorg.gradle.project.agenthq.testForks` / `agenthq.uiTestForks`. Also `AGENTHQ_BUILD_PRIORITY`, `AGENTHQ_BUILD_MAX_WORKERS`, `AGENTHQ_BUILD_MAX_FORKS`, which `scripts/agenthq-gate.sh` reads for its own fallback (`nice`, `--max-workers`). The owner's own Gradle and IDE never see any of this.
- **CPU in `processes`.** `TrackedProcess.cpuPercent` (this process's share of ALL logical cores over the last 10 s watch pass, 0–100; null until two samples), `ProcessReport.agentCpuPercent` (their sum), `cpuAlertPercent`, `cpuAlertMinutes`, `cpuHighSince` (epoch ms since when the sum has stayed at or above the threshold; null while below).
- **Sustained-CPU attention.** When `agentCpuPercent` stays at or above `limits.cpu.alertPercent` (default `90`, 1–100) for `limits.cpu.alertMinutes` (default `10`; `0` = off), the daemon raises ONE attention item per episode: kind `ceiling`, outcome `cpu_sustained`, with the top processes in the detail. Falling below the threshold rearms it.
- **Old clients.** They ignore the new fields; the attention item renders like any other `ceiling` item.

### What changed in 1.135.0 (SECRETS-2, ticket-e5d1b57f) — key/value secrets, injected into runs

ADDITIVE + BEHAVIOURAL; `contract=1.135.0`.

- **A secret holds named fields.** `android-signing` can hold `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; any environment-style set works the same way. Each field is sealed on its own (AES-256-GCM, the AAD is the secret name, a NUL, and the field name), so a field copied under another field or secret does not open. Field names are environment-variable names.
- **Migration.** `keystore.json` becomes `v: 2` (`secrets: {name: {field: sealed}}`). A 1.102.0 entry reads as one field named `value` and is re-sealed into v2 at the next write; one that does not open (another key) is kept unchanged, never dropped. The built-in single-value secrets (`update-token`) keep their frames and read the `value` field.
- **New frames** `secret_field_set`, `secret_field_delete`, `secret_delete` edit the user's secrets (any valid name except a built-in's). `secret_state` gains `entries` (each user secret, its field names and masked tails) and `locked`. **No frame returns a value.** A refusal names the secret or field, never the value.
- **Runs get secrets as environment variables.** `CommandSpec` / `command_save` gain `secrets` (a list of secret names, at most 16). `run_start` accepts `secrets` too, added on top of the command's own. At start the daemon puts every field of each secret into the child's environment under its field name (a later secret's field of the same name wins). An unknown secret, or a field named `AGENTHQ_*`, refuses the run.
- **Output scrubbing.** Every injected value of 4 or more characters is replaced with `••••` in the run's `run_bytes`, its scrollback (`run_attach`) and its `run_output` lines, including a value split across two chunks (the bytes that could start a value wait for the next chunk). A value the child transforms (encodes, splits with other text in between) is not caught.
- **Demo lock** (`AGENTHQ_DEMO_LOCK`): every `secret_*` change is refused, `secret_state.locked` is true, and a `run_start` that would inject any secret is refused. A run with no secret is unchanged.
- **Not covered.** `agenthq run` starts its child in the CLI process, not the daemon, so it gets no secrets (injecting there would mean sending values to a client).
- **Old clients.** They ignore `entries`, `locked` and `secrets`; a command saved by an old client keeps no secrets (a save replaces the entry).

### What changed in 1.134.0 (RUN-CONSOLE, ticket-67f117b6) — runs have a terminal; pills and a console window

ADDITIVE + BEHAVIOURAL; `contract=1.134.0`.

- **A run has a pty.** A saved command now runs in a pseudo-terminal (pty4j: ConPTY / winpty on Windows, the POSIX pty on macOS and Linux), still from its argv (never a shell string), with `TERM=xterm-256color`; stdout and stderr are one stream. Where no pty can load, the run uses pipes as before (`terminal: false`).
- **`run_bytes`** (new, to every client): the raw output chunks, base64, `sequence` 1..n per run. A console applies them in order.
- **`run_output` keeps coming** for every run, so a client without a terminal emulator (web, Android, an older desktop) keeps its line pane: on a pty run each line is the plain text of the terminal line (escape sequences dropped, `\r` returns to the start of the line and what follows overwrites it, backspace and erase-line applied), sent at its `\n`. Stream is `stdout`.
- **`run`** gains `name` (the command's name at start), `terminal` and `interactive`; a pty run also pushes a `RUNNING` `run` frame (with its `pid`) to every client once the child is up.
- **`run_list` -> `runs`**: the runs the daemon keeps (setting `limits.run.history`, default 20, the oldest finished one forgotten first), newest first: the Run tab's pills.
- **`run_attach {runId}`** -> one `run_bytes` with `replay: true`: the kept scrollback (setting `limits.run.scrollbackBytes`, default 2 MiB, newest kept; `droppedBytes` fell off the front) up to and including chunk `sequence`. A console resets, writes it, then applies the live chunks with a higher `sequence`. Works for a finished run while it is kept.
- **`run_start`** accepts `interactive` (bool, default false), `cols`, `rows` (the first pty size, default 120 x 32). **`run_resize {runId, cols, rows}`** resizes the pty. **`run_input {runId, data}`** writes keys (base64) into a run started interactive; refused otherwise.
- **Demo lock** (`AGENTHQ_DEMO_LOCK`): `run_start` with `interactive: true` and every `run_input` are refused (no raw shell). Starting and watching a saved command is unchanged.
- **Output is untrusted.** The daemon never interprets it; the desktop console disables escape sequences that act on the host (no clipboard write by OSC 52, a link opens only on a click).
- **Old clients.** They ignore the new fields and frames and never send the new requests; `run_output` and `run_finished` behave as before.

### What changed in 1.133.0 (NO-HARD-CODED-LIMITS, ticket-23471801) — every cap, threshold and timeout is a documented setting

ADDITIVE + BEHAVIOURAL; `contract=1.133.0`. Owner rule 2026-10-25: no hard-coded restriction. Each limit is a `LimitRegistry` entry resolved, first hit wins, from the stored daemon setting (`settings_set`, Configuration > Limits, `agenthq settings set <key> <n>`) -> the environment (`AGENTHQ_LIMIT_<GROUP>_<NAME>`, e.g. `AGENTHQ_LIMIT_CONCURRENCY_MAX_PER_POD`, plus the legacy names listed) -> the default. Values are whole numbers, read at every use (a change applies at once, no restart); a bad env value is logged at daemon start and ignored. `0` turns a limit off where it says so.

| Setting | Default | 0 means | Was |
|---|---|---|---|
| `limits.concurrency.maxPerPod` | 4 | unlimited | `AGENTHQ_POD_SPAWN_CAP` (still read) |
| `limits.concurrency.maxDepth` | 1 | sessions may not spawn | `AGENTHQ_POD_SPAWN_DEPTH` (still read); the MCP write tools' built-in 2 is gone, they follow this |
| `limits.concurrency.mcpMaxSpawns` | 4 | unlimited | `McpGuardrails.DEFAULT_MAX_SPAWNS`; `--max-spawns` / `.agenthq/mcp.json` still override per connection |
| `limits.concurrency.laneDefault` | 1 | unlimited | the built-in cap of a role a project sets no lane cap for |
| `limits.pacing.maxWorkersCeiling` | 50 | (min 1) | `PacingRule.MAX_WORKERS`, the highest `usage.pacing` maxWorkers / tier workers / override accepted |
| `limits.build.maxWorkers`, `limits.build.maxParallelForks`, `limits.cpu.alertPercent`, `limits.cpu.alertMinutes` | as 1.132.0 | as 1.132.0 | unchanged keys, now in the registry (catalog, env) |
| `limits.watchdog.ticketDeadlineMinutes` | 180 | no deadline | `AGENTHQ_TICKET_DEADLINE_MIN` (still read) |
| `limits.watchdog.deadlineMinutes` | 60 | no deadline | fixed 60 min for unattended non-ticket runs |
| `limits.watchdog.stallMinutes` | 15 | never stopped for silence | fixed 15 min |
| `limits.watchdog.killRetryMinutes` | 5 | never retried | fixed 5 min |
| `limits.watchdog.silentMinutes` | 20 | never flagged | fixed 20 min silent sweep |
| `limits.processes.watchIntervalSeconds` | 10 | (min 1, max 3600) | fixed 10 s process-watch pass (ticket-3b30398d); read before each pass |

- **`settings` / `settings_get`** list every `limits.*` key with its value in force. `settings_set` refuses a value outside the range with `setting '<key>' must be a JSON integer in <min>..<max> (<unit>; 0 = <meaning>; default <d>)`.
- **`limit_catalog`** (new; request with empty data, answered to the requester): `{limits: [{key, value, default, unit, description, min, max?, zeroMeans?, env}]}`. Configuration > Limits renders it; `agenthq settings limits` prints it.
- **Refusals name the limit.** A pod-cap / depth refusal (`spawn refused: ...`) and the MCP `agenthq_spawn` / `agenthq_ticket_start` refusals end with `Change it with the setting '<key>' (Settings > Limits, agenthq settings set <key> <n>, or env <ENV>)`. Watchdog kill reasons name their setting.
- **CLI.** `agenthq settings [get [<key>]]`, `agenthq settings set <key> <value>`, `agenthq settings limits`.
- **Pacing okProjected / hardStopPercent** stay fields of the `usage.pacing` rule (already a setting; `usage_set` / `agenthq settings set usage.pacing <json>`), not separate keys.
- **Old clients.** They ignore the new keys and never send `limit_catalog`; nothing they send changes meaning.
