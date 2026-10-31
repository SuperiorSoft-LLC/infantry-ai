# DAEMON-CORE-1: what the daemon core is (ticket ad656fe1)

Owner principle: **core is the abstraction layer**: abstractions, configuration, the tools to modify configuration, and the
abstract data contract between UI and daemon. Everything concrete is a plugin (docs/DAEMON-PLUGINS.md). This note defines the
daemon core by that rule, classifies every other daemon package as the target list of DAEMON-PLUGINS-2, makes the line
checkable (`DaemonCoreGuardTest`), and records how the daemon could split into an orchestrator and a runner (section 6).
Licensing follows the line: core MPL-2.0, everything else Apache-2.0, split by directory.

## 1. The rule

A thing is **core** when removing it would stop a plugin from being written or a client from talking to the daemon, and it names
no product feature. A thing is **plugin** when it is a concrete behaviour built on core's abstractions (a board, a tool family,
a harness adapter, a policy preset, a demo). A thing that is concrete but cannot leave yet is **stays in core** with the reason
that holds it, and shrinks with DAEMON-PLUGINS-2.

## 2. The core list (verified against `superiorSoftLibrary/agentHq/src/main`)

| core item | where | reason |
|---|---|---|
| wire contract and frames | `protocol` (`Protocol.kt`, `CONTRACT.md`) | the abstract data contract between UI and daemon; version bump only when a frame changes |
| session lifecycle | `session` (`SessionManager`, `Session`, `SessionStore`, `session/host`) | spawn, resume, restart, kill and the process ledger every harness runs under |
| harness abstraction | `harness` (`Harness`, `HarnessPlugin`, `HarnessRegistry`, `CliHarness`, `HarnessCommand`) | the interface third parties implement; the adapters under it are not core |
| mail routing and the mailbox | `mailbox` (`Mailbox`, `MailRouter`, `MailStore`, `Message`), `daemon/MailHttp.kt` | the routing mechanism and durable queue; the monitors in the package are product rules (section 3) |
| plugin host | `daemon/plugins` (`DaemonPluginHost`, `PluginGuard`, `DaemonApiClassLoader`, `DaemonServices`), `daemon/DaemonPlugins.kt`, `agentHqDaemonApi` | loads, isolates, grants and limits daemon plugins; the module `agentHqDaemonApi` is the plugin-facing API |
| policy mechanism | `policy` (`ActionCatalog`, `PolicyProvider`, `PolicyRuntime`), `daemon/FrameGate.kt`, `daemon/AccessPolicy.kt` | who may do what; presets, seats and gates are data and plugins on top |
| configuration | `settings` (`SettingsService`, `LimitRegistry`, `ProjectSettings`, `ModelAllowlist`), `daemon/DaemonConfig.kt`, `ConfigManifestHttp.kt`, `NodeConfigHttp.kt` | the unified config and the frames/tools to modify it |
| storage abstraction | `db` (`AgentHQDatabase`), `keystore` | the one SQLite entry point and the secret store behind plugin secret grants |
| watch and run mechanism | `runner` (`ProcessRunner`, `PtyProcessRunner`, `WatchShims`, `WatchLog`, `WatchNotices`, `Sandbox`), `daemon/WatchHooks.kt` | how a command runs and is watched; `ToolProfiles` (gradle, mvn, npm...) are data on the mechanism |
| daemon bootstrap and transport | `daemon` (`DaemonMain`, `HealthGate`, `StateDirLock`, `RemoteAccess`, `OwnerCredential`, `/ws`, `/mail/*`, `/hooks/*`, `/health`) | starts the above in order and serves the contract |

Everything else is either a **stays** (not abstract, cannot leave yet) or a plugin (section 3). Concrete code that lives inside a
core package (examples: `session/AutoProgress*`, `session/ToolApprovals`, `mailbox/StallDetector`, `FinishNoticeMonitor`,
`ReportBackMonitor`, `AgentWatchdog`, `daemon/DoneWorkerReaper`, `AssistantService`, `SlashCommands`, `Roster`, `UpdateHttp`)
is listed in the guard's reason for its package and moves package-by-package in DAEMON-PLUGINS-2.

## 3. Classification of every top-level package

`DaemonCoreGuardTest` holds the same table as code: a new package fails the build until it is classified, and a package
that is renamed or deleted must leave the table. Lines in PLUGIN_NOW/PLUGIN_LATER/STAYS are the DAEMON-PLUGINS-2 target list.

**CORE** (stays by definition): `protocol`, `daemon`, `policy`, `settings`, `db`, `keystore`, `harness`, `runner`.

**STAYS in core (reason)**
| package | reason |
|---|---|
| `session` | SessionManager and the process ledger are core; AutoProgress, ToolApprovals, SleepHint, RetrievalClassifier are plugin candidates |
| `mailbox` | Mailbox/MailRouter/MailStore are core; the Stall/Watchdog/ReportBack/FinishNotice monitors are product rules |
| `persistence` | AgentHqStore and the records are shared by every feature table; shrinks as features take their own tables |
| `worktree` | git working copies, runner side, used by SessionManager and the board alike |
| `projectgit` | git status/diff/commit helpers over worktree, runner side; moves with the Git plugin once DaemonApi has a git surface |
| `usage`, `usagemeter`, `stats`, `tokens` | limits, metering and the harness-neutral RequestUsage record are fed by every harness stream |
| `executables` | resolves harness and tool binaries for the runner |
| `update` | self-update of the install; DaemonMain wires it directly |
| `notify` | the spoken-push frame helper of the protocol |
| `provider` | AgentProvider abstraction used by SessionManager; the concrete providers in it follow `claude` and `opencode` |
| `orchestration` | the pod/node DAG scheduler (orchestrator side, section 6) and `OrchestratorLink`, the seam |

**PLUGIN NOW** (mechanism exists, only the move is left): `memory` (a Knowledge
plugin); `images` (Infantry_Chat).
DP-10b: `prompts` is reclassified CORE, not PLUGIN NOW: it is the PROMPT-1 tool-approval round-trip (PendingPrompts, PromptTimeouts, SpawnPromptTimeout; used by session, harness, claude, opencode), not the Infantry_Prompts template library. Only its policy data left: the stored "allow always" rules and the `approval_rules` / `approval_rule_delete` frames are Infantry_Approvals' daemon half (table `infantry_approvals_rules` via `api.storage`), reached by core through the `hooks.approvalRules` port (DaemonApi 1.22.0, additive; `ApprovalRulesHook.match` / `remember`). With the plugin absent there are no stored rules: every prompt asks and ALLOW_ALWAYS degrades to ALLOW_SESSION with no error. Rules stored before this change (settings row `toolApproval.rules`) are not migrated.
DP-7c: `issues` and `decisions` (stores, handlers, frames) moved to plugins/official/Infantry_Notes/daemon; only the wire DTOs stay in core (agentHqProtocol).

DAEMON-PLUGINS-3: `probe` (a one-off provider comparison `main()` and its `probeProvider` Gradle task) was dead and is deleted. `experiments` (the pi tool bridge) cannot move yet, see below.

**PLUGIN LATER (blocker named)**
| package | blocker |
|---|---|
| `backlog`, `epics`, `stories`, `mvp`, `ticketfiles`, `context`, `brief`, `ledger` (Infantry_Board's daemon half) | DONE (DP-5h-4): core builds no board. The Infantry_Board plugin builds the `TicketBoard` (epics, stories, handover, worker sweep, frames) over `BoardRuntime`; core reaches it only through `BoardPorts` / `BoardCoreAccess`. With the plugin absent the board frames refuse with the no-board text. `BoardImportGuardTest.remaining` is EMPTY (no core file imports a board class); see `docs/DAEMON-BOARD-API.md` section 21 |
| `claude`, `opencode` | the adapters are already `HarnessPlugin`s, but ProviderFactory, HarnessModelCatalogs, Sandbox and DaemonMain name them, so built-ins are not registered through the SPI |
| `role`, `agents` | roles and profiles are bound into SessionManager spawn and mail addressing; needs a DaemonApi role surface / spawn-binding hook |
| `questions`, `attention` | usage, mailbox and triggers raise attention items directly (reverse dependency); needs attention in the API first |
| `triggers` | DaemonApi has `schedule()` but no spawn-with-binding or ticket-transition events |
| `commands` | RunManager needs a pty/runner surface in DaemonApi |
| `experiments` | the pi tool bridge hooks `PiLaunchExtras`, `SessionStatsService` taggers and reads `AgentHqMcp`/`AccessPolicy` internals; needs a harness launch-extras hook and an MCP tool-exposure surface in DaemonApi |
| `cli`, `client` | the `agenthq` CLI/MCP server and console client are clients of the daemon, not daemon, but import protocol/session/daemon types; they leave with the agentHqCore split (section 4) |

Measured coupling (imports between top-level packages, `agentHq/src/main`): `daemon` imports 31 packages, `cli` 18, `backlog`
11, `session` 10. `protocol` imports `session` (2) and `harness` (1) and `runner` imports `session` (1): those three
back-references are what the module split must break first.

## 4. Making the line physical

Done now (additive, no behaviour or wire change, contract version unchanged):

* **package-level split by classification** with `DaemonCoreGuardTest` (section 5);
* `agentHqDaemonApi` is already its own module and the only thing a daemon plugin compiles against;
* the plugin-facing API cannot be reached the other way: `agentHq` depends on no `:plugins:*` or `daemon-plugin-*` project
  (guarded), and its sources name no `agenthq.plugins.*` package.

Not done, with the reason: the **Gradle module split** (`agentHqCore`) is not a mechanical change today. The packages form one
strongly connected graph (`session` <-> `runner` <-> `provider` <-> `harness` <-> `daemon`; `protocol` imports `session` and
`harness`; `DaemonMain.kt` is 4300 lines naming ~40 packages), so a module cut either fails to compile or needs interfaces
that are the real work. Exact plan, one ticket per step, each keeps the build green:

1. **CORE-SPLIT-1 (DONE, ticket e7fb5a60)** move `protocol` + `Frame` types out of `session`/`harness` references (3 imports) so `protocol` is a leaf; create module `agentHqProtocol` (contract + wire DTOs). Small.
2. **CORE-SPLIT-2 (DONE, ticket e7fb5a60)** `runner` becomes a leaf (1 back-import of `session`); module `agentHqRunner`. Small.
3. **CORE-SPLIT-3** invert the `session`->`provider`->`harness`->`session` cycle: `Harness`/`AgentProvider` interfaces and `SessionEvent` types into `agentHqCore`; `ClaudeCodeProvider`, `OpenCodeProvider`, `harness/claude|pi|descriptor|install` into `agentHqHarnesses`. Medium; depends on DAEMON-PLUGINS-2 registering built-ins via the SPI.
4. **CORE-SPLIT-4** cut `DaemonMain.kt` into core bootstrap plus `feature wiring` (what DAEMON-PLUGINS-2 moves out); then `agentHqCore` holds `protocol, session, harness (api), mailbox, policy, settings, db, keystore, runner, daemon/plugins` and the dependency rule (core imports no feature module) becomes a Gradle fact instead of a test.
   **CORE-SPLIT-4a1/4a2 status:** feature wiring moved out of `daemon/DaemonMain.kt` into `daemon/wiring/*.kt`: AttentionWiring, CommandsWiring, ImagesWiring, KnowledgeWiring, QuestionsWiring, TriggersWiring (4a1), AgentsWiring (roles registry, agent modules, profiles), PromptsWiring (pending prompts, approval rules), BoardWiring (core ticket services, core-built LocalBoardHost) (4a2). `DaemonMain.kt` is now 4338 lines (was 4417); the rest is bootstrap plus the plugin-host, orchestrator-hub and harness-module construction, still to cut.
5. License split follows: `agentHqCore` + `agentHqDaemonApi` MPL-2.0, features Apache-2.0.

### License readiness (CORE-SPLIT-A, ticket e7fb5a60)

Physically core today (own Gradle module, depends on no other agentHq module and no plugin module; `DaemonCoreGuardTest` checks it):

* `superiorSoftLibrary/agentHqProtocol` (`:superiorSoftLibrary:agentHqProtocol`): package `protocol` (wire contract and DTOs), plus the two
  wire types it needs, which keep their package names: `session.SessionState`, `session.SleepHint`/`WaitHint`, `session.RunPhaseOverlay` and
  `harness.HarnessCommand` (+ `CommandAction`, `StandardCommands`, `CommandLine(s)`). `protocol` was made a leaf by moving those declarations here instead of
  importing `session`/`harness`; `SpawnOptions.promptTimeoutOverride()` (which named `prompts`) became an extension in `prompts/SpawnPromptTimeout.kt`.
  The leaf's `internal` members became public (tests and the daemon are now in another module).
* `superiorSoftLibrary/agentHqRunner` (`:superiorSoftLibrary:agentHqRunner`): package `runner` (ProcessRunner, PtyProcessRunner, watch shims,
  ToolProfiles, ...). Its one back-import of `session.InfantryEnv` was inverted by letting `RunnerFactory` read `INFANTRY_RUNNER`/`AGENTHQ_RUNNER` itself.
* `superiorSoftLibrary/agentHqBoard` (`:superiorSoftLibrary:agentHqBoard`, DP-5e): packages `epics`, `brief`, `ledger`, `context`, `ticketfiles` (package names unchanged).
  Depends on `agentHqDaemonApi` and `agentHqProtocol`, never on `agentHq` (`BoardImportGuardTest`); `agentHq` depends on it, still one daemon jar. What the packages
  need of the host comes in through ports declared there: `TicketFileRows` (SQL adapter `SqlTicketFileRows` in core), `ProjectDirectory`, `KnowledgeSection`,
  `SkillsSection`, `GitCli`/`GitResult`, `ProjectSettings`, and the EpicHandler `branchName` hook. `SqlEpicPersistence` and the `TicketFiles(db, ...)` factory stay in core.
  `backlog`, `mvp` and `stories` stay in `agentHq` and depend on it (DP-5h-4: no core main file imports them any more; the board constructors `TicketBoard(store, ...)`/`StoryMigration(...)` live in the test source set).
* `superiorSoftLibrary/agentHqDaemonApi` and `superiorSoftLibrary/agenthqPlugins` were already separate modules.

Still inside `superiorSoftLibrary/agentHq` (not yet physical): `session`, `harness` (apart from `HarnessCommand.kt`), `daemon`, `policy`,
`settings`, `db`, `keystore` and every other package, until CORE-SPLIT-3/4. No license file or header has been added; the owner signs off
this directory list first.

## 5. Guards (`DaemonCoreGuardTest`, `NoDemoInDaemonCoreGuardTest`)

* (a) no core source names a plugin-owned package (`agenthq.plugins.`, `com.acme.`), and `agentHq/build.gradle.kts` has no
  `:plugins:`/`daemon-plugin` project dependency;
* (b) the classification table of section 3 is complete, exclusive, current, every entry has a reason, every plugin-later entry
  names a `blocker:`, and this file names every package; DAEMON-PLUGINS-2 deletes rows as it migrates (the test fails if a
  deleted package stays listed);
* (c) `NoDemoInDaemonCoreGuardTest` stays green and unchanged;
* (d) transport guard for the orchestrator link (section 6.5).

**Build your own daemon core as a top-level daemon plugin.** The mechanism supports *extending* core, not *replacing* it. A
top-level (official/core tier) daemon plugin can add frames, tools, hooks, routes, policy presets, limits and top-level/public
routes, so a product can ship its own board, policy or demo lock on the stock core (Demo_Lock is the worked example). What it
cannot do is replace a core component (SessionManager, the mailbox, the store, the harness registry, the transport): they are
constructed by `DaemonMain`, not resolved through the host. Gap, named as a blocker: **DAEMON-CORE-2: component bindings**
(resolve `SessionManager`, `Mailbox`, `OrchestratorLink`, store by a setting such as `core.<component>.provider`, same shape
as `orchestrator.provider` below). Until then "own core" means "own plugins on the shared core" or a fork of `DaemonMain`.

## 6. Orchestrator vs runner

Product direction (owner): a hosted **orchestration** service people pay for (board/tickets, mail and routing, policy,
usage/stats, accounts) while the **workers stay on the user's machine** (harness sessions, working copies, git, builds, the
watch runner). Source code never leaves the machine; only orchestration data crosses. Local-only mode keeps working unchanged:
nothing in this section changes behaviour. Note the name clash: `orchestration/Orchestrator` today is the *pod DAG scheduler*;
"orchestrator" below means the future service side.

### 6.1 Every daemon area, classified (evidence from the code)

| area | side | evidence |
|---|---|---|
| `session` (SessionManager, Session, SessionStore, host/) | **runner** | spawns processes via `runner`, owns child env, working dir, process ledger, `ConversationLog` on disk |
| `harness`, `provider`, `claude`, `opencode`, `executables` | **runner** | `ProcessBuilder`/`ProcessRunner`, binaries, PTY, CLI installs (grep: harness 9 files, runner 7, session 6, provider 4) |
| `runner` (+ `daemon/WatchHooks`) | **runner** | processes, PTY, sandbox, watch shims |
| `worktree`, `projectgit` | **runner** | git CLI, branches, worktrees on disk (`GitCli`, `Worktree*`) |
| `commands`, `images`, `update` | **runner** | cwd-bound commands, uploads under `<project>/.agenthq`, install/updater |
| `keystore` | **runner** (secrets stay local) | OS vault master key (`OsVaults`) |
| `backlog`, `epics`, `stories`, `mvp`, `pipeline`, `ticketfiles`(rules), `context`, `brief`, `ledger` | **orchestrator** | tickets, status machine, linking, prompts for workers: state and rules; two leaks: `TicketBoard` takes `SessionManager`, `worktree` (7 imports), and ticket rows carry file paths |
| `orchestration` (pod DAG) | **orchestrator** | pure scheduling over `SessionManager`; its only runner contact is spawn/events |
| `role`, `agents`, `prompts`, `triggers`, `attention`, `questions`, `decisions`, `issues`, `memory` | **orchestrator** | persona, knowledge and notification state; no process or file access beyond the store (grep: none of `backlog`, `epics`, `stories`, `mvp`, `role`, `agents`, `triggers`, `attention`, `questions`, `memory` references `ProcessBuilder` or `ProcessRunner`) |
| `policy` (ActionCatalog, PolicyRuntime, FrameGate) | **shared** | decisions are orchestrator-authored, enforcement must run on the runner for every frame it executes (a hosted policy may only *tighten*, section 6.4) |
| `usage`, `usagemeter`, `stats`, `tokens` | **shared** | meter is measured on the runner, aggregated and limited by the orchestrator |
| `mailbox` | **shared** | routing/state is orchestrator; *delivery* is runner (`MailDelivery` resumes a local session) |
| `protocol`, `settings`, `persistence`, `db` | **shared** | the contract, config and storage both sides need; each side keeps its own store |
| `daemon` (transport, AccessPolicy, plugin host) | **runner** today | the local server; an orchestrator would have its own |

### 6.2 The contract between them as it exists today

There is no explicit contract. The nearest thing is a set of **function-valued seams** wired in `DaemonMain.kt` (the daemon
is the only place that sees both sides):

| direction | seam (where wired) | carries |
|---|---|---|
| orchestrator -> runner | `MailDelivery.deliver(Message)` (`Mailbox`/`MailRouter`, `DaemonMain` ~1313) | mail for a session: resume it with a prompt, outcome delivered/queued/failed |
| orchestrator -> runner | `SessionManager.spawn/resume/kill` through `bindSessionOnSpawn` (~1312, ~2882), `Orchestrator.sessionStartedSink` (~1459) | start/stop a session with role, provider, model, project, ticket, parent |
| orchestrator -> runner | `TicketBoard` + `Worktree*` | cut a ticket branch/worktree for a worker |
| runner -> orchestrator | `SessionEvent` stream into `MailRouter.onSessionEvent` and `manager.eventSink` (~1546) | status, finished, usage, progress events |
| runner -> orchestrator | `manager.launchGate` (~1025), `manager.skillsFor` (~1215), `manager.spawnGate` (~1662) | "may this launch?", "which skills for this agent/ticket", "plugin refusal" |
| runner -> orchestrator | `mail.reportCheck` (~1356, **now through `OrchestratorLink.reviewReport`**) | a worker's report mail, answered with a refusal or accept |
| runner -> orchestrator | `board.transitionSink` (~1371), `starterOf = board::starterOf` (~1317), `ticketOf` (~1342, ~1476) | ticket state reads and transitions |
| both | the frames of `protocol` | client-facing; also what an orchestrator UI speaks |

Missing for it to be an explicit, versioned, transport-neutral interface: (1) one interface type (this ticket adds
`OrchestratorLink` with one operation as the proof; ORCH-1 extended it, section 6.7); (2) a data model for every seam (today several pass daemon classes such as
`SessionRecord`, `Message`, `TicketRow`); (3) version and capability negotiation; (4) an error model with *unavailable*; (5) event
delivery with ordering and replay (today in-process callbacks); (6) a binding registry keyed by `orchestrator.provider`.

### 6.3 Coupling points that block a split (rough size)

| coupling | size |
|---|---|
| **One database**: `AgentHqStore` holds tickets, sessions, attention, settings, usage, roles, pods in one SQLite; `persistence` imports `usage`, `role`, `attention`, `session` | **L**: one store per side, plus migrations |
| **MailRouter <-> SessionManager**: `MailRouter(manager, mailbox, ...)` lists sessions, resumes, reads `ticketId`/`agentId`; `FinishNoticeMonitor`/`AgentWatchdog` read ticket + session together | **L**: routing vs delivery split; `MailDelivery` is the right edge, the rest of the router is not |
| **TicketBoard owns worktrees**: `backlog` imports `worktree` (7), `session` (7); `board.cutRepo`, branches | **M**: ticket rows carry `worktreeBranch`/`useWorktree`, upload paths relative to the project dir |
| **File paths in tickets and prompts**: `Tickets.kt` (worktree fields), `TicketContextComposer`, `ticketfiles` (git diff names) | **M**: paths are runner-local; the orchestrator must treat them as opaque |
| **Policy enforcement**: `FrameGate` uses `PolicyRuntime`, seats, plugins in-process | **M**: define "hosted policy may tighten only" and ship the active preset as data |
| **Usage/limits**: `SessionManager.launchGate`, `usage` read settings and attention | **M**: events out, decisions in, through the link |
| **DaemonMain wiring**: 4300 lines build both sides with direct references (`board`, `manager`, `triggers`, `attention`) | **L**: first 4 CORE-SPLIT steps, plus move wiring into bindings |
| **Auth and identity**: `AccessPolicy`, owner/agent tokens are local; no accounts | **M**: device pairing, section 6.4 |
| **Triggers/DoneWorkerReaper/AutoProgress**: orchestration rules calling `SessionManager` | **S** each |

### 6.4 Baby steps (smallest first; policy layer's role in each)

1. **Interface + local binding (this ticket, done in part).** `OrchestratorLink`, `OrchestratorReply`, `LocalOrchestratorLink`
   in `orchestration/OrchestratorLink.kt`; `mail.reportCheck` goes through it. Next in this step: one operation per seam of
   6.2, each with a plain data model, then move the interface into `agentHqDaemonApi` and add `orchestrator.provider` (default
   `local`) resolved at startup. Policy: unchanged; the local binding is the only one.
2. **Outbound-only connection from a runner to a remote orchestrator.** A binding plugin (`orchestrator.provider = <plugin id>`)
   opens one outbound, authenticated, TLS channel; the runner polls or streams; nothing listens. Operations carry orchestration
   data only: tickets, mail text, usage numbers, session status; never file contents, diffs or transcripts unless the user's
   settings say so. Policy: the active preset still decides what the *local* frame gate lets through.
3. **Accounts and device pairing.** Pair a machine with an account by a code the owner approves on the device; per-device
   revocable credential held in the keystore. Auth is the binding's concern, callers never see it. Policy: pairing is an
   owner-only action in the `ActionCatalog`.
4. **Security review.** A hosted service that can ask a runner to start sessions is a remote-execution channel. Required before
   it ships: every remote-originated spawn/resume/kill passes `FrameGate` and the active policy as a *non-owner caller* with a
   seat; a remote policy can tighten, never loosen, local rules; the runner keeps the final say (project allow-list, spawn
   guards, budget, kill switch, an owner "disconnect orchestrator" toggle); remote operations audit-logged; replay and
   idempotency keys on spawn. Policy: this step is mostly the policy layer's, and is why the policy layer is core.

### 6.5 Location transparency: the interface

Rule (owner, hard): the runner side, plugins, harness adapters and the UI never know where the orchestration lives. They hold
an `OrchestratorLink` and call it identically whether it answers in-process or remotely. Local vs remote is a **binding**,
chosen by one setting resolved at startup (`orchestrator.provider = local | <plugin id>`), swappable by a plugin. No caller has
an if/else on it.

* **Operations**: one method per seam of 6.2, each `(plain data in) -> OrchestratorReply<plain data out>`. Today:
  `reviewReport(from, body)`. Planned (step 1): `acceptMail`, `deliveryResult`, `sessionEvent`, `launchDecision(provider,
  origin, ticket)`, `skillsFor(agent, ticket)`, `ticketTransition`, `ticketOf`, `starterOf`, `usageRecord`.
* **Versioning**: `OrchestratorLink.CONTRACT_VERSION` (semver). Adding an operation is minor; changing meaning is major. A
  binding that finds a peer it does not speak returns `Unavailable("version")`; callers never branch on the version.
* **Error model**: every operation returns `Ok(value)` or `Unavailable(reason)`; no exceptions for expected failures. A refusal
  is a *value* (`Ok(text)`), not an error. Each caller owns its fail mode for `Unavailable` and documents it (reviewReport:
  fail open, i.e. no refusal, so a worker is never blocked by a dead service; launch decisions: fail to the local default).
* **Idempotency and ordering**: every operation is idempotent on a natural key it carries (session id + message id, ticket id +
  transition id); a repeated call after `Unavailable` is safe. Events (`sessionEvent`, `usageRecord`) are ordered per session
  by a monotonic sequence number and a binding may batch or re-send; consumers dedupe by `(session, seq)`.
* **Auth** is entirely the binding's: callers pass no token, URL or host.
* **Guard**: `DaemonCoreGuardTest` fails when any source naming `OrchestratorLink`, other than a class under
  `orchestration/binding/`, names a transport (`java.net.http`, `HttpClient`, `HttpURLConnection`, `WebSocket`, `okhttp`,
  `ktor.client`, `http(s)://`, `ws(s)://`); the `DaemonMain` wiring is checked on its link lines.
* **Done as proof**: `OrchestratorLink` + `LocalOrchestratorLink`; `DaemonMain` builds the local binding and
  `mail.reportCheck` calls `orchestratorLink.reviewReport(...)`. Behaviour identical (same function, same arguments).

### 6.6 Call sites that bypass the interface today (and what each needs)

Each is a direct call between the runner and orchestrator sides that must route through `OrchestratorLink` before a remote
binding is possible. File:line are `DaemonMain.kt` unless noted.

| call site | needs |
|---|---|
| `manager.launchGate` (1025) | `launchDecision(provider, origin, ticket)` |
| `manager.skillsFor` (1215) | `skillsFor(agent, ticket)` |
| `MailRouter(... starterOf = board::starterOf ...)` (1317) | `starterOf(session)` |
| `mailbox` send/deliver: `MailRouter.send/pump` against `SessionManager` (`mailbox/MailRouter.kt`) | split routing (orchestrator) from `MailDelivery` (runner); `acceptMail` + `deliveryResult` |
| `MailRouter.onSessionEvent` / `manager.eventSink` (1546) | `sessionEvent` stream with sequence numbers |
| `FinishNoticeMonitor(ticketOf = ...)` (1342), second `ticketOf` (1476) | `ticketOf(id)` |
| `board.transitionSink` (1371) -> `triggers`, `DoneWorkerReaper`, ticket-files record | `ticketTransition` event, consumers subscribe to it |
| `orchestrator.sessionStartedSink` (1459), `bindSessionOnSpawn` (1312, 2882) | `spawn` as a *command from* the orchestrator (step 4) |
| `AgentWatchdog`/`ReportBackMonitor` -> `AttentionStore` (`mailbox/*.kt`) | attention is raised by the orchestrator from `sessionEvent`, not by runner-side monitors |
| `usage` writes from `SessionManager` (`usage/*`, `usagemeter`) | `usageRecord` |
| `mail.reportCheck` (1356) | **routed through the link** (done) |

No daemon source today names an HTTP/WebSocket client toward an orchestrator (the only `java.net.http` users are the update
check, `ClientMain` and the CLI, none of them orchestrator traffic), so the transport guard passes from day one.

### 6.7 ORCH-1: what shipped, what is left, degrade rules

**Shipped.** `daemon.api.orchestrator.OrchestratorLink` (contract 1.1.0) now lives in `agentHqDaemonApi`, beside `RunnerLink`
(orchestrator -> runner: `ticketIdOf`), `LocalOrchestratorLink`, and `OrchestratorHub` (the registry plus the one link callers
hold). Plugins offer a binding with `api.hooks.orchestratorBinding(id, binding)` (declare `orchestratorBinding` in
`daemon.hooks`); `AGENTHQ_ORCHESTRATOR_PROVIDER` (default `local`) picks it at startup, after plugins load; an unknown or failing
provider keeps the local binding and logs. Operations: `reviewReport` (mail), `ticketOf`, `starterOf` (board/tickets),
`launchDecision` (policy/usage). Routed in `DaemonMain`: `mail.reportCheck`, `MailRouter.starterOf`, `DoneWorkerReaper.ticketOf`,
`manager.launchGate`. Local mode calls the same functions with the same arguments.

**Degrade rules** (what a caller does on `Unavailable`; nothing is queued by callers because every routed operation is a read or
idempotent): `reviewReport` -> no refusal; `ticketOf` -> unknown ticket (nothing reaped); `starterOf` -> no starter (mail takes its
non-ticket route); `launchDecision` -> allow, the runner keeps its own guards. Mail is persisted by the mailbox BEFORE any
delivery attempt, so an unavailable orchestrator cannot lose mail. A remote binding owns retry (exponential, idempotent on the
natural key) and, for future event operations, an outbox with per-session sequence numbers. Proven by
`RemoteOrchestratorFakeTest` (create ticket, send mail, spawn+finish; slow and unavailable).

**Guards.** `OrchestratorBoundaryGuardTest` (runner<->orchestrator package imports are a ratchet; the interface module imports
nothing of the daemon) and `DaemonCoreGuardTest` (no transport next to the link).

**Left (follow-ups, exact lists).** (1) Remaining operations of 6.6: `skillsFor`, `ticketOf` for `FinishNoticeMonitor` (needs the
ticket row, not `TicketRef`), `ticketTransition` event, `sessionEvent` stream, `usageRecord`, `acceptMail`/`deliveryResult` (split
`MailRouter` routing from `MailDelivery`). (2) Shrink `OrchestratorBoundaryGuardTest.BASELINE` (51 imports in 30 pairs; biggest:
`cli>memory` 14, `cli>issues` 9, `cli>pipeline` 8, `backlog>session` 7, `backlog>worktree` 7, `orchestration>session` 7). The `cli>*`
entries are wire DTOs that belong in `agentHqProtocol`. (3) `orchestrator.provider` as a declared setting instead of the env var.

## 7. Plugin storage (DAEMON-STORAGE-1, DaemonApi 1.15.0)

`api.storage: PluginStorage` (default `NoPluginStorage`, every call refuses) gives a module its own tables in the daemon's one SQLite file; there is no raw core handle (DAEMON-PLUGIN-PLAN decision 5, conservative option).

- Members: `migrate(version, sql)` (runs once per module, records the version in the host table `plugin_storage_versions`, all-or-nothing), `query(sql, args)`, `execute(sql, args)`, `transaction { }` (rolls back on exception).
- Naming: every table / index / view / trigger a statement names (after FROM / INTO / UPDATE / JOIN / TABLE / INDEX / VIEW / TRIGGER, `ON` of CREATE INDEX) must start with the module prefix (module id lowercased, non-alphanumerics as `_`, plus `_`) or be on the adopt list. One statement per call; ATTACH / PRAGMA / VACUUM, schema-qualified names and table functions are refused. String literals and comments are ignored by the check.
- ADOPT: an OFFICIAL / bundled module may declare `daemon.storage.adopt: [table...]` in module.json, existing core tables it takes over (DP-6 / DP-7 keep their rows, no migration). A community module declaring adopt is refused at load; it gets prefix only and needs the grant `storage`.
- Implementation: `LocalPluginStorage` over the store's `AgentHQDatabaseHandle` (same driver, lock and write tracking). Values bind as null / Long / Int / Boolean / Double / String / ByteArray; rows come back as column-label maps.

## 8. Typed service registry (AR-5, DaemonApi 1.16.0)

`api.services: ServicesApi` (default `NoServices`) is a typed, optional registry keyed by an interface declared in an API module.

- `provide(type: KClass<T>, impl: T): AutoCloseable`: a plugin publishes. Official / core tier, or a community module holding the grant `services.provide`; otherwise `IllegalStateException`. One provider per type (a second throws). Closing the handle, or unloading / disabling the plugin (its closeables run), removes the provider; a stale handle never removes a newer provider.
- `find(type: KClass<T>): T?`: any module. No provider = null; the caller owns its fail mode (refuse, degrade, or skip).
- Core keeps the one `ServiceRegistry` (`DaemonPluginHost.serviceRegistry`) and queries it directly; DP-7b (Knowledge) and ATTN-1 (Attention) will look their providers up there. Plugins get a `ScopedServices` view (tier check, handles tracked per module).
- Registry is in memory only: providers vanish with the daemon and are re-published by each plugin's `start`.

### Deprecation plan

No existing getter is changed or deprecated by AR-5 (additive). `tickets`, `epics`, `boardRuntime`, `board` and `hooks.orchestratorBinding` are marked `@Deprecated("use services.find", level = WARNING)` only once a provider for their interface is published through `services.provide` (board plugin: DP-5 follow-ups; orchestration: the OrchestratorBinding provider), and removed or moved with CORE-SPLIT-4b (the `daemon.api.board` types stay in the API jar until then).

**DP-6 (pipeline moved out of core):** the `pipeline` package is gone from `agentHq`. Its wire DTOs (`PipelineConfig/Entry/Check/State/Configure/Show/Record/CheckRequest/Refusal`, package name unchanged) live in `agentHqProtocol`; `PipelineStore` / `PipelineHandler` and the tools `agenthq_pipeline` / `agenthq_pipeline_record` live in `plugins/official/Infantry_Board/daemon` (the tools moved there from Infantry_Git). Storage goes through `api.storage` adopting `pipeline_config`, `pipeline_entries`, `pipeline_checks` (module.json `daemon.storage.adopt`; core's migrations still create them). The four `pipeline_*` frames are declared in `daemon.frames` and registered with `api.frames.handle`; the new `pipeline_state` push goes out through `api.boardRuntime.push`. Plugin absent: `<type> refused: this daemon has no pipeline` (`BoardFrames.noPipeline`). No DaemonApi change.
**DP-7a (ticket 6df0d6c2):** the knowledge wire DTOs (`memory`: MemoryNote, MemorySave/Get/List/Search/Delete/PurgeRole, MemoryScoped, MemoryIndexEntry, MemorySearchResult, `memoryOccurrences`; `issues`: Issue, IssueEvent and the Issue* payloads, ref/location helpers; `decisions`: Decision, DecisionAdd/List/Get, `parseDecisionRef`) live in `agentHqProtocol` under the same package names (wire unchanged). Stores, handlers, refusals and SQL stay in `agentHq`. `OrchestratorBoundaryGuardTest` no longer counts an import of such a type as a runner<->orchestrator crossing: `cli>memory`, `cli>issues`, `cli>decisions` are 0.

**DP-7b (Knowledge read port, DaemonApi 1.17.0, additive):** `daemon.api.knowledge.Knowledge` (read-only: `noteCount`, `digest(project, role, budget)`, `section`, `search`, `roleNotes`) is a service in the AR-5 registry. Core registers `LocalKnowledge` (over MemoryStore/DecisionStore/IssueStore, owner `core`) right after the plugin host is set. `agents/Knowledge.kt`'s `KnowledgeCounter` (first-turn digest, board brief, mail router) is now `ServiceKnowledgeCounter`, which calls `services.find(Knowledge)` on every read; no provider = no counts = the knowledge section is simply omitted (no error). Rendered text unchanged (`renderKnowledge` untouched). `budget` is reserved: the local provider applies the shipped caps.

**DP-7c (issues and decisions moved out of core):** the `issues` and `decisions` packages are gone from `agentHq`. The Infantry_Notes daemon half owns the stores (over `api.storage`, tables adopted via `daemon.storage.adopt`: decisions, issues, issue_events; core migrations still create them) and the nine frames (module.json `daemon.frames`, same types, actions, kinds and refusal texts). DaemonApi 1.18.0 (additive minor) adds `KnowledgeRecords` (`activeDecisions`, `openIssues`) beside `Knowledge`; the plugin provides it and core's `LocalKnowledge` reads it lazily for the digest (memory stays in core until DP-7d). Without the plugin the frames refuse with `<type> refused: this daemon has no decisions|issues` and the digest omits both. No decision/issue MCP tools exist, so none were added.

**DP-7d (step 1, DaemonApi 1.18.0, additive):** `events.onSessionRemoved(listener)` tells a plugin the id of every session that is killed or removed (after core's own `onSessionEnded`; one failing listener never stops the rest; community plugins get no grant gate, it carries ids only). It is what lets the Infantry_Notes daemon half drop session-scoped notes when the memory package moves out of core. The memory package itself (MemoryStore, MemoryHandler, LeadNoteStore, `memory_notes` adoption, role-note mail, deleting `LocalKnowledge`) has NOT moved yet: see the DP-7d report for what is left.

**DP-7e (mail port, DaemonApi 1.19.0, additive):** `api.sessionNotifier` (`daemon.api.sessions.SessionNotifier`): `notifySession(sessionId, subject, body): Boolean` and `notifyRole(projectId, role, subject, body): Int` (count of live sessions mailed). Core implements it (`LocalSessionNotifier`) over the existing mail router, only to sessions that are live; a send that fails is not counted and never throws. The default (`NoSessionNotifier`) delivers nothing. Official / core plugins use it freely; a community module needs the grant `sessions.notify` in its module settings (`GrantedSessionNotifier`; default deny, the call throws `missing grant 'sessions.notify' in modules.json`). Core's ROLE-NOTES-1 announcement now goes through the same class, and the memory package can move into Infantry_Notes without a mail dependency.

**DP-7f (memory moved out of core):** MemoryStore, MemoryHandler, LeadNoteStore and the persistence (now `StorageMemoryPersistence` over `api.storage`; LIKE search with `ESCAPE`) live in the Infantry_Notes daemon plugin; `memory_notes` is in module.json `storage.adopt` (core migrations still create it) and the six `memory_*` frames are declared in `daemon.frames` (same types, actions, kinds and refusal texts). A session's notes are dropped on `events.onSessionRemoved`; a role-note change is mailed through `sessionNotifier.notifyRole`. The plugin provides the whole `Knowledge` port (digest, section, search, roleNotes); core's `LocalKnowledge` and `KnowledgeWiring` are deleted, `roleNotesOf` reads the port, and `KnowledgeLines`/`KnowledgeDigest` moved to `agentHqProtocol` (package `agents`) so the plugin can build the digest lines. Without the plugin the memory frames refuse `<type> refused: this daemon has no memory` and the digest omits notes. The `memory` package in core now holds only the wire DTOs (agentHqProtocol).

**USAGE-FIT (DaemonApi 1.20.0, additive):** `Meters.requests(sinceMs)` returns the recorded model requests (`MeterRequest`: ts, session, role, harness, model, input/output/cache tokens; null = not reported) so the Pace daemon can fit tokens per meter point. Read-only; empty when the daemon keeps none.

**DP-10a (chat images moved out of core, DaemonApi 1.21.0, additive):** `api.files(projectId)` returns a `ProjectFiles` rooted at `<project directory>/.agenthq/` (put / get / stat / list / delete / touch / absolutePath). It refuses `..`, empty or `.` segments, absolute paths, drive letters and a symlink that leads out of the root, and an unknown project or one without a directory (IllegalArgumentException). Official tier or a community `files` grant. ImageUploads and ChatImageRetention now live in Infantry_Chat (`image_upload` frame declared in module.json under the same action `images.upload`/write; the wire, the `.agenthq/uploads/<purpose>/<sha>.<ext>` layout and the refusal texts are unchanged); retention runs once at start then every 6h through `api.schedule`. Without the plugin `image_upload` answers `image_upload refused: this daemon stores no uploads`. Ticket image attach (backlog/TicketImages.kt) stays in core and takes its path prefix from `ProjectFiles.UPLOADS_REL`. Settings `images.*` are plugin-readable.
