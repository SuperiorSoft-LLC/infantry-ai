# DAEMON-BOARD-API: the DaemonApi surface a board plugin needs (design only, DAEMON-PLUGINS-3, ticket 23d334b7)

Status: **design; DP-4a built (BOARD-API-1, ticket 089a22a9, section 7), DP-4b/4c/5 not.** Owner priority is a clean licensing line and orchestration decoupling before launch; the board
migration is built after launch. This note is what DP-4.. start from. It does not change behaviour or the wire contract.

Why the board family is not a simple move (DAEMON-PLUGINS-2 finding, confirmed here): a plugin compiles against
`agentHqDaemonApi` only, and `DaemonApi` today offers `projects`, `sessions`, `policy`, `events`, `tools`, `frames`, `routes`, `hooks`.
It has no ticket, epic or board operation, so a board plugin could not reach its own data; and core reaches the board directly.

## 1. What is board family, and how big

| package | lines | what it holds |
|---|---|---|
| `backlog` | 2220 | `TicketBoard` (1915 lines: status machine, linking, start/handOut, worktree cut, sweeps, brief), `TicketAnswer`/`BoardAnswer`/`BoardEvent`, `RoutingRules` |
| `mvp` | 1047 | `BoardHandler`, `StoryHandler`, `ProjectHandler`/`WorkspaceHandler` + their stores and SQL persistence |
| `stories` | 495 | `StoryMigration` (legacy stories to tickets) |
| `ticketfiles` | 494 | per-ticket changed-file index, related work (the two tools are already a plugin: `Infantry_Board/daemon`) |
| `context` | 350 | `TicketContextComposer` (worker prompt context, and the `STARTER` addressee constant the mailbox uses) |
| `epics` | 332 | `EpicStore`, `EpicHandler`, `SqlEpicPersistence` |
| `brief` | 197 | lead-to-worker brief |
| `ledger` | 82 | asked/done ledger of the board's workers |

About 5200 lines plus the `tickets` table family in `persistence/AgentHqStore`.

## 2. Every direct coupling to the board (core -> board), with a rough size

References by type name (`grep TicketBoard`, `import ...backlog|epics|stories|mvp|ticketfiles|context|brief|ledger`):

| site | what it does | size |
|---|---|---|
| `daemon/DaemonMain.kt` (20 board-family imports, 7 `TicketBoard` uses: field `val board: TicketBoard` (~443), `boardRef` (~1083), construction (~1250), `gitRootOf` (~2327), `ticketTitles` (~4017)) | builds the board and the story/epic/project/workspace handlers, routes ~40 ticket/board/story/epic frames to them, wires every seam in 3 | **L**: the frame switch plus wiring is most of it |
| `mvp/StoryHandler`, `stories/StoryMigration` | take a `TicketBoard` and call `import`/`create`/`move`; they are board family themselves | **S** (move with it) |
| `ticketfiles/LiveRelatedWork` (4 imports), `ticketfiles/TicketFiles` | read ticket rows to rank related work | **S** (move with it) |
| `triggers/TriggerService` (2), `TriggerNudges` (1) | read tickets / transitions to fire triggers | **M**: needs the ticket events (section 4) |
| `mailbox/MailRouter` (1) | `TicketContextComposer.STARTER`, the `starter` addressee constant, plus `starterOf`/`reportCheck` seams | **S**: move the constant to core (`mailbox`), keep the seams (section 5) |
| `settings/SettingsService` (1), `projectgit/ProjectGitHandler` (1), `images/ImageUploads` (1), `daemon/plugins/CoreDaemonServices` (1), `db/AgentHQDatabase` (1), `ledger/*` (2) | reads of ticket rows, project dirs, ticket ids | **S** each |
| `cli/TicketStarter`, `cli/CliMain` ticket commands, `cli/mcp/McpTicketTools`, `McpWriteTools`, `McpProgressTools` | clients: they speak frames, not `TicketBoard` | **none** (frames stay) |
| `session/SessionManager`, `session/TicketWorkerHealth`, `mailbox/*` | **no `TicketBoard` import.** They reach the board through function-valued seams wired in `DaemonMain`: `manager.launchGate`, `manager.skillsFor`, `bindSessionOnSpawn`, `Orchestrator.sessionStartedSink`, `mail.reportCheck` (already via `OrchestratorLink`), `starterOf = board::starterOf`, `board.transitionSink`, `ticketOf` (x2), `manager.ticketBrief` | **M**: a handful of lambdas to turn into interface calls |

So the claim "SessionManager and the mailbox import TicketBoard" is imprecise: they import the `context` constant and receive
board lambdas. The real blockers are `DaemonMain` (frames + wiring) and the missing API.

## 3. The surface: `DaemonApi.tickets` (and `epics`, `board`)

One new section of `DaemonApi`, read and write, versioned with `DaemonApi.VERSION` (minor bump when added). Plain data in/out
(no `TicketRow`, `SessionRecord` or other daemon class crosses): `TicketInfo`, `TicketDraft`, `TicketPatch`, `EpicInfo`,
`BoardColumn`, `TicketEvent` live in `agentHqDaemonApi` as serializable data classes.

```
interface Tickets {
    fun get(id: String): TicketInfo?
    fun list(projectId: String? = null, epicId: String? = null, status: Set<String> = emptySet(), limit: Int = 200, cursor: String? = null): Page<TicketInfo>
    fun create(draft: TicketDraft): TicketInfo
    fun update(id: String, patch: TicketPatch): TicketInfo          // title, description, role, provider, model, epic, priority, links
    fun move(id: String, status: String, by: Caller): TicketInfo    // runs the status machine, may refuse
    fun delete(id: String, by: Caller)
    fun link(from: String, to: String, kind: String) / unlink(...)
    fun reorder(projectId: String, ids: List<String>)
    fun start(id: String, by: Caller): StartResult                  // handOut: role, provider, model, branch, worker
    fun starterOf(sessionId: String): String?                       // ticket's starter, for the mailbox
    fun ticketOf(sessionId: String): String?
}
interface Epics { get / list / create / update / delete / setBase }
interface Board { fun columns(projectId: String): List<BoardColumn>; fun brief(ticketId: String): String? }
```

* **Scoping.** Every read/write takes the caller (`Caller` already exists) and the host applies the same project scope core
  applies today: a worker sees its own project, `all` is for pm/owner/assistant callers, a non-owner explicit id must be its own.
  This is the existing MCP `ProjectScope` rule (`cli/mcp/ProjectScope.kt`, 81 lines, contract 1.106.0) moved to the **host**
  (`ToolCall.caller` already carries role and project), so a plugin never reimplements it and cannot widen it.
* **Policy.** Each operation maps to an existing `ActionCatalog` action (`board.*`, `ticket.*`); the host checks it before the
  call, as `FrameGate` does for frames. A plugin's own tools keep declaring `action` in `module.json`.
* **Error model.** Same as `OrchestratorLink` (DAEMON-CORE section 6.5): an expected failure is a value, not an exception:
  `Result.Ok(value)`, `Refused(text)` (the status machine said no; the text is the one core says today, byte for byte),
  `Unavailable(reason)`. A refusal is a normal answer; only an unexpected bug throws, and the host turns it into a plugin fault.
* **Pagination.** `list` returns `Page(items, next: String?)` with an opaque cursor (ticket id + order key); default and max
  page from `limits.board.*` settings. Ordering is stable (board order, then id).
* **Idempotency.** `create` takes an optional client key; `move`/`start` are idempotent on (ticket, target status): a repeat is a
  no-op answer, not an error.
* **Events.** `api.events` gains typed ticket events, delivered in order per ticket with a monotonic sequence, at-least-once
  (consumers dedupe on `(ticketId, seq)`): `TicketCreated`, `TicketMoved(from, to, by)`, `TicketUpdated`, `TicketStarted(worker)`,
  `TicketDone(files)`, `EpicChanged`. These replace `board.transitionSink`, and are what `triggers`, the done-worker reaper and
  the ticket-files recorder subscribe to.
* **Versioning.** The board section has its own `BOARD_API_VERSION` (semver) inside `DaemonApi.VERSION`; a module's
  `daemon.apiAtLeast` is checked at load as for the rest of the API. Adding a field with a default is minor; changing the
  meaning of an operation is major and needs a deprecation window.
* **Wire contract.** No frame changes: the ticket/story/epic frames keep their names, payloads and answers. The frame handlers
  just move into the plugin (`api.frames.handle`) after DP-5; until then core handlers call the same interface.

## 4. Taking the board out of core's hands: interface, not import

Core defines the interface in `agentHqDaemonApi` and owns the *local binding*; the board plugin supplies the implementation
after the move. Core code stops naming `TicketBoard`:

1. **Seams become one interface.** Core already has one place for this: `OrchestratorLink` (`orchestration/OrchestratorLink.kt`,
   DAEMON-CORE section 6.5). The board is orchestrator side, so the seams of 6.2 and 6.6 that touch it become its operations:
   `ticketOf`, `starterOf`, `ticketTransition` (event), `skillsFor(agent, ticket)`, `launchDecision(provider, origin, ticket)`,
   `ticketBrief(ticket)`. `Tickets` above is the plugin-facing view of the same operations; `OrchestratorLink` is the runner-side
   view. The local binding of both forwards to the board; a remote orchestrator would answer both. No new seam type.
2. **`SessionManager`** keeps its lambdas but receives them from `OrchestratorLink`, not from `board::...`; `TicketWorkerHealth`
   is board-owned logic (it judges a ticket's worker) and moves into the board plugin with `sweepWorkers`.
3. **`mailbox`.** `STARTER` moves into `mailbox` (a constant). `reportCheck` already goes through `OrchestratorLink.reviewReport`;
   `starterOf` goes through it too.
4. **`DaemonMain`.** Its board frame switch is data: a table `frame type -> handler` registered by whoever owns the frame.
   The board plugin registers its ~40 frames; core keeps a fallback that answers "this daemon has no board" so a client talking
   to a daemon without the plugin gets a refusal, not a hang.
5. **Persistence.** `tickets` tables stay in `AgentHqStore` at first (the plugin gets the data through `Tickets`, never SQL);
   a later step gives the plugin its own table family through `DaemonApi.storage` (not designed here).

## 5. Step plan (all after launch; each step keeps the build green and the wire unchanged)

| step | work | size |
|---|---|---|
| **DP-4a** (DONE, section 7) | Add `Tickets`/`Epics`/`Board` interfaces + data classes to `agentHqDaemonApi`; `CoreDaemonServices` implements them over `TicketBoard`/`EpicStore` (local binding). Move `ProjectScope` into the host (applies to every `Tickets` call). Golden tests per operation. | M |
| **DP-4b** (DONE, section 8) | `TicketBoard` events: replace `transitionSink` with typed ticket events on `api.events`; `triggers`, `DoneWorkerReaper`, ticket-files recorder subscribe through the API. | M |
| **DP-4c** | Route the `SessionManager`/mailbox seams (section 4.1) through `OrchestratorLink`; move `STARTER` into `mailbox`; core no longer imports `backlog`/`context` outside `DaemonMain`. Guard: only `DaemonMain` and the board packages name them. | M |
| **DP-5** | Move `backlog`, `epics`, `stories`, `mvp`, `context`, `brief`, `ledger`, `ticketfiles` into `plugins/official/Infantry_Board/daemon` against the API only; frames registered by the plugin; `DaemonMain` loses its board switch. Parity: every existing board/ticket/story/epic daemon test passes unchanged against the plugin. | L (multi-day) |
| **DP-6** | Pipeline plugin (`pipeline`, `agenthq_pipeline*` tools): needs only `Tickets` + `Sessions`; small once DP-4a lands. | S |
| **DP-7** | Knowledge plugin: `issues`, `decisions`, `memory` (+ their CLI/MCP surface) behind a `Knowledge` section of the API (notes, decisions, issues CRUD, search); own tables. | M |
| **DP-10** | `prompts` (Infantry_Prompts) and `images` (Infantry_Chat) halves; `images` also needs a storage/upload surface scoped to `<project>/.agenthq`. | S-M each |
| **DP-11** | Agents tool family (`agenthq_spawn`, `list_sessions`, `prompt`, `kill`, `restart`...): needs `McpGuardrails` in the API (spawn/depth/concurrency/kill-scope checks a plugin calls and cannot reimplement) and `Sessions` extended with the spawn binding. Not done in DP-9 (see below). | M |

Order matters: DP-4a first (nothing moves yet, only an interface and its local binding), then 4b/4c, then the move (DP-5).
After DP-5 the licensing line is clean: core imports no board package, and `Infantry_Board`'s daemon half is Apache-2.0 under `plugins/official`.

## 6. What DP-9 left for these steps

DP-9 moved the **usage and processes** tool families (they only needed their wire models and text renderers in the API,
done) and **did not** move `ProjectScope` or `McpGuardrails` into the API: both read daemon internals (`ProjectScope` uses the
session record and `AccessPolicy`; `McpGuardrails` the session manager's limits and kill scope) and are CLI-process code in
`cli/mcp` today. They are host-side services in this design (section 3 for scope; DP-11 for guardrails): the right home is the
host applying them to every plugin tool call, not a helper a plugin may skip. The Agents and Board tool families wait for them.

## 7. DP-4a built (BOARD-API-1, ticket 089a22a9): what is there and where it differs from section 3

Built: `Tickets` / `Epics` / `Board` + plain `@Serializable` data (`TicketInfo`, `TicketDraft`, `TicketPatch`, `TicketQuery`, `Page`,
`StartResult`, `TicketBinding`, `BoardColumn`, `EpicInfo`) in `agentHqDaemonApi/.../api/board/BoardApi.kt` (`BOARD_API_VERSION` 1.0.0,
`DaemonApi.VERSION` 1.1.0 -> 1.2.0, members default to `NoBoard` = `Unavailable`); the local binding `LocalBoardServices` (over
`TicketBoard`/`EpicStore`, wired into `CoreDaemonServices` by `DaemonMain`); the grant wrapper `GrantedBoard` (in `DaemonPluginHost.Api`).
Tests: `LocalBoardServicesTest` (golden per operation, scope, policy, Unavailable through an `OrchestratorLink` fake, grants, get parity),
`BoardApiImportGuardTest` (API imports nothing from the daemon).

* **Results** are `BoardResult.Ok | Refused(text) | Unavailable(reason)`. A board refusal is `<frame> refused: <why>` (what the frame
  answers); an unknown / ambiguous ticket or a scope refusal is the bare sentence the MCP tools word today.
* **Scope** is the host's: `LocalBoardServices` calls the one `ProjectScope` (`cli/mcp`, pure logic, contract 1.106.0) with the daemon's
  session record of `Caller.activeSession`. Deviation: it is *shared*, not moved, because the CLI's remaining MCP tools still use it; the
  parity test is `ProjectScopeTest` plus the scope test here. Fail closed: an unknown session, or an agent connection with no session, is
  refused (the CLI path would have widened it to every project). An owner connection with no active session is the owner path.
* **Policy** is the active preset's, as the equivalent frame is gated (`ticket_get/list` = `board.read`, `ticket_save/link/unlink` =
  `board.ticket.save`, `ticket_move/reorder` = `board.ticket.move`, `ticket_delete`, `epic_list/save/base`); `start` is gated by
  `ticket_move` AND `ticket_start_next` (a start is a spawn), so it is stricter than a bare move.
* **Grants** (community plugins; official / bundled are trusted; default deny; named in modules.json `allow`): `tickets.read`
  (get, list, starterOf, ticketOf, workerOf, board columns/brief), `tickets.write` (create, update, move, link, unlink, reorder),
  `tickets.delete`, `tickets.start`, `epics.read` (get, list), `epics.write` (create, setBase). RUNTIME-LOAD-5 (DaemonApi 1.6.0): the sessions read model adds `sessions.read` and `sessions.transcript` (docs/RUNTIME-LOAD.md section 11).
* **Deviations from section 3:** `Tickets.get` also resolves short ids (what `tickets` prints); `move` refuses `in_progress` (use
  `start`); `starterOf`/`workerOf` go through `OrchestratorLink` (Unavailable passes through), `ticketOf(session)` reads the session
  record; `workerOf(ticket)` is new (the link's ticket-to-session direction). `Epics` has get / list / create / setBase only: core has no
  epic update or delete. Pagination limits are constants (default 200, max 500), not `limits.board.*` settings yet; the cursor is the last
  ticket id. `create` idempotency by `clientKey` is in-memory (lost on restart). Events (`TicketMoved`, ...) are DP-4b and not built.
* **Not done here:** the one-tool migration (see RUNTIME-LOAD.md section 8 for why it was skipped).

## 8. DP-4b built (BOARD-API-2, ticket b4a1b1c3): typed ticket events

`TicketBoard.transitionSink` is gone. A commit writes one `ticket_events` row per change INSIDE its own transaction (`TicketEventLog.record`,
migration 52), and delivers after the commit under the board monitor, as the sink did. `TicketEvents.subscribe(consumer, listener)` (in
`agentHqDaemonApi`, `BOARD_API_VERSION` 1.1.0, `DaemonApi.VERSION` 1.3.0, plugin side `api.events.onTicket(consumer, listener)`) is the one
way in: the three core consumers (`core.triggers`, `core.done-worker-reaper`, `core.ticket-files`) subscribe exactly as a plugin does.

* **Events:** `TicketEvent(seq, ticketId, kind, from, to, at)`, kinds `created`, `moved`, `started` (into in_progress), `done` (into done),
  `updated` (change that kept the column), `deleted`. `isTransition` = what the old sink announced (created/moved/started/done).
* **Order and delivery:** `seq` is a global monotonic counter, so events of one ticket arrive in seq order. At-least-once: a consumer's
  cursor (`ticket_event_cursors`) moves after its listener returned; unhandled events replay in order on the next subscribe after a
  restart. A first-ever consumer starts at the head (no history). A listener that throws is retried on the next delivery and skipped
  (logged) after 3 failures, so a poison event cannot wedge the stream. The last 10 000 events are kept.
* **Grants:** a community plugin needs `tickets.read` to subscribe; without it the subscribe throws and the host disables the plugin.
* **Deviations from section 3:** one global `seq` instead of a per-ticket one (stronger); no separate `TicketStarted(worker)` /
  `TicketDone(files)` payloads (the event carries ids and columns; read the ticket or `ticket_files` for the rest); `EpicChanged` is not
  built (the epics have no event source yet); the stream is fed by the local board, not routed through `OrchestratorLink` (a remote
  orchestrator would feed the same log in a later step). Tests: `TicketEventLogTest`, `LocalBoardServicesTest` (grant).

## CONTEXT-LIMIT-1: `BOARD_API_VERSION` 1.2.0 (additive)

`TicketInfo` gains `contextLimit` (the ticket's limit in tokens, null = none), `effectiveContextLimit`,
`effectiveContextLimitSource` (`ticket` / `default`) and `contextLimitSupport` (`supported` / `best_effort` / `unsupported`
for the ticket's effective harness), all defaulted, so a plugin built on 1.1.0 is unaffected. Infantry_Board's
`agenthq_ticket_get` renders them byte for byte as core's summary does (`LocalBoardServicesTest`). See docs/CONTEXT-LIMIT.md.

## EFFORT-1: `BOARD_API_VERSION` 1.4.0 (additive)

`TicketInfo` gains `effort`, `effectiveEffort`, `effectiveEffortSource` (`ticket` / `rule` / `agent` / `role` / `default`) and `effortSupport`;
`TicketDraft` / `TicketPatch.effort` (blank / `off` clears on a patch); the shared parser `EffortLevels`. `SpawnRequest.effort`,
`SessionInfo.effort` / `effortSupport` and the harness catalog's `effort` / `effortHow`. See docs/EFFORT.md.

## RUNTIME-LOAD-4: `BOARD_API_VERSION` 1.3.0 (additive)

The pure shared rules `TicketRefs`, `TicketPlacement`, `TicketImages`, `ContextLimits`; `TicketDraft` / `TicketPatch.contextLimit`;
`Tickets.mayFile(projectId, by)` (the scope's bare sentence, asked before a tool writes anything for a project). Infantry_Board's
daemon half now serves `agenthq_ticket_create/update/move/delete` and `agenthq_epic_base`. See RUNTIME-LOAD.md section 10.

## 9. DP-5c built (ticket 65094bfb): `BoardHost`, what the board plugin asks of the host (`BOARD_API_VERSION` 1.6.0, additive)

`daemon.api.board.BoardHost` (file `BoardHost.kt`) is the reverse of `Tickets`/`Epics`/`Board`: those are what core offers a caller of the board, this is what the
board needs from core once it moves (DP-5). Plain data in and out. Core binds it as `daemon/plugins/LocalBoardHost` over the existing classes; nothing consumes it yet.

**Decision: one port, narrow members.** `BoardHost` carries `storage`, `git`, `sessions`, `settings` and `now()`; each member is its own small interface with
no-board defaults (git and sessions refuse with a value, settings answer null; storage throws `UnsupportedOperationException`, a wiring bug). Several separate ports
were rejected: the board needs the transaction, the clock and the event log TOGETHER (events are recorded inside the commit), a single handle is what the plugin
receives from `DaemonPlugin`, and a test fakes one member by overriding it. A transaction handle (not a `DaemonApi.storage` generic SQL) keeps `TicketBoard`'s
`store.transaction { ... }` shape: `storage.transaction { tickets.all(); links; events.record(...) }`, so the move changes imports and `store.tickets` -> `tickets`.

| core class | what TicketBoard & co. call | port member |
|---|---|---|
| `AgentHqStore` | `transaction`, `writeTransaction` | `BoardStorage.transaction/writeTransaction { BoardTables }` |
| `TicketRepository`/`TicketRow`/`TicketLinkRow` | create/update/get/all/delete/link/unlink/links | `TicketTable` over `StoredTicket`/`StoredLink` |
| `TicketRunRepository`/`TicketRunRow` | insert/finish/byTicket | `TicketRunTable` over `StoredRun` |
| `TicketEventLog` | `record(before, after, changed, deleted)` in the commit; `deliver()` after | `TicketEventAppender.record/deliver` |
| `TicketBranches` | `ensure`, `resolveBase` | `BoardGit.ensureBranch/resolveBase` (`BranchRequest`/`BranchOutcome`) |
| `TicketBaseRef` | `choose(BaseScope)` | `BoardGit.baseRef(projectId, epicId)` -> `BaseChoice` |
| `projectDirectory` lambda, `repo: File`, `SessionManager.projectFolders` | NOGIT-1 folder lookup | `BoardGit.projectDirectory/defaultRepo/projectFolders` |
| `SessionManager.startNew` | spawn for ticket / unattended prompt | `BoardSessions.start(SessionStart)` -> `SessionStartResult` |
| `checkSpawn`, `launchCheck`/`GateDecision` | pre-start checks | `BoardSessions.checkSpawn`, `launchGate` -> `LaunchVerdict` |
| `SessionManager.get/list/kill/delete` | worker health, reaping | `BoardSessions.get/list/kill/remove` (`SessionView`) |
| `SettingsRepository` | exitBehavior etc. are lambdas the daemon feeds | `BoardSettings.get(key)` |
| `clock: () -> Long` | stamps | `BoardHost.now()` |

**Not in the port (left for DP-5, each noted):** `AgentProfiles` (`instanceFor`, `executorFor`, `resolveAssignment`) and `RoleInstance`/`withSkills` -- the board renders the first
turn from the resolved instance; a plugin needs the agents port (`daemon.api.agents`) extended, or the host resolves the instance inside `BoardSessions.start` (the
`instanceFor` chokepoint already exists). `KnowledgeCounter`/`SkillsSource`/`TicketImages`/`RelatedDescription`/`TicketContext` are lambdas/ports the daemon injects; they
move with the board. `WeightExecutors`, `RoutingRules`, `PacingVerdict` come from settings/usage: read through `BoardSettings` + usage port later. No frame or refusal text changes.

## 10. DP-5f-2 built (ticket 6e940497): the rest of the `BoardHost` members (`BOARD_API_VERSION` 1.7.0, additive)

`BoardHostPorts.kt` adds what section 9 left out, so `TicketBoard` can move without a core import. Same rules: plain data, no-board defaults, `LocalBoardHost` delegates to the core class, nothing consumes it yet, no behaviour change.

| core class | what TicketBoard calls | port member |
|---|---|---|
| `AgentProfiles` | `get/list/save/delete/roleKnown/executorFor/validateExecutor` | `BoardHost.agents: BoardAgents` over `BoardAgent` / `BoardExecutor` |
| `RunOutcomes` | `isAttemptOver`, `of(record)`, `isRetryable` | `BoardHost.runs: BoardRunHealth` (`isAttemptOver(state name)`, `outcomeOf(sessionId)` -> `BoardRunOutcome`) |
| `TicketWorkerHealth` | `problem`, `slowToBrief` | `BoardRunHealth.workerProblem(sessionId)`, `slowToBrief(sessionId, now, graceMs)` |
| `SessionEvent` (`Status`, `Exit`) | `onSessionEvent` | `BoardSessions.subscribe(BoardSessionListener)` -> `AutoCloseable`; `BoardSessionEvent.Status/Exit` |
| `KnowledgeCounter`, `SkillsSource` | `knowledge.section(projectId, role)`, `skills.section(instance)` | `BoardHost.context: BoardContextSources` (`knowledgeSection`, `skillsSection(skillIds, include)`) |
| `TicketImages` (daemon class) | `normalize`, `absolute`, `release` | `BoardHost.images: BoardImages` (paths as strings, `release` over `StoredTicket`) |

Still not in the port: `AgentProfiles.instanceFor` / `RoleInstance` / `withSkills` (the host resolves the instance inside `BoardSessions.start`, as section 9 noted) and the DP-5f-1 pure classes (already in `agentHqBoard`).
`LocalBoardHost.publish(SessionEvent)` is the fan-in: the daemon's one event sink calls it (not wired yet, DP-5 does it); listeners run in arrival order, a throw in one is swallowed.

TEST-TODO (DP-5f-2): (1) event delivery order: publish Status, Exit, Status for one session; one listener sees them in that order, two listeners each see all three in that order. (2) subscription lifecycle: after `close()` a listener gets nothing, `close()` twice is safe, a listener subscribing inside a callback does not break the delivery in flight, a throwing listener does not stop the next one. (3) Exit carries the `SessionView` as it stood on arrival even after the session record is deleted. (4) `executorFor` on `LocalBoardHost.agents` equals `AgentProfiles.executorFor` for the same inputs (role binding and pins).

## 11. DP-5f-2b built (ticket 05622d24): second batch (`BOARD_API_VERSION` 1.8.0, additive)

Same rules as section 10. `LocalBoardHost` delegates; `NoBoardHost` answers empty. `TicketBoard` untouched.

| core | TicketBoard use | port member |
|---|---|---|
| `SessionManager.providers/providerId/hasProvider/providerFor`, `AgentProvider.defaultModel` | provider lookups | `BoardSessions.providers() / providerId() / hasProvider / providerFor / defaultModel(providerId)` over `BoardProviderInfo` |
| `defaultWorkingDir`, `workEvents`, `requireAllowedRole` | start checks, stall checks | `BoardSessions.defaultWorkingDir() / workEvents(id) / requireAllowedRole(role)` (problem text or null) |
| `AgentProfiles.instanceFor`, `SessionManager.withSkills`, `startNew(instance=)` | first-turn render + freeze | `BoardAgents.instanceFor(profileId, role)`, `BoardAgents.withSkills(instance, ticketId)` over `BoardRoleInstance`/`BoardTrait` (check flattened); `SessionStart.instance` goes to `startNew(instance=)` (null = host resolves) |
| `SessionRecord.lastHeartbeat`, `isBusy()` | stall / worker checks | `BoardSessions.detail(id)` / `details()` -> `BoardSessionDetail(view, lastHeartbeat, busy)` |
| `Thread.holdsLock(sessions)` | `onSessionEvent` inline vs queued | `BoardSessions.holdsSessionsLock()` (default false). Monitor order is board -> sessions: an event delivered while this is true must be queued to the board's executor, not applied inline. Event ordering is unchanged. |
| subscribe thread | | `BoardSessions.delivery` = `EMITTING_THREAD`: listeners run on the emitting thread, possibly under the sessions lock |
| `TicketBoard.events` (`TicketEventLog`) | record / deliver | `BoardHost.events: TicketEventAppender` (same appender as `storage.tables.events`, usable outside a transaction) |

TEST-TODO (DP-5f-2b): (1) lock hook parity: `holdsSessionsLock()` true exactly inside `synchronized(manager)` on the calling thread, false on another thread. (2) delivery thread: a listener runs on the emitting thread, under the manager monitor when the emitter holds it. (3) heartbeat/busy mapping: `detail().busy` equals `isBusy()` for STARTING/BUSY/WAITING_INPUT/IDLE/EXITED; `lastHeartbeat` equals the record's. (4) `instanceFor`/`withSkills` round-trip equals core's `RoleInstance` including a trait check; `start(instance=...)` freezes exactly that instance. (5) `requireAllowedRole` text equals core's refusal under an allowlist. (6) `host.events` records and delivers like `storage.tables.events`.

## 12. DP-5f-3 built: third batch (`BOARD_API_VERSION` 1.9.0, additive)

`BoardProviderInfo` gains `contextLimitSupport/How`, `effortSupport/How` and a lazy `knownModels`; `BoardSessionDetail` gains `spawnedAt` and `providerSessionId`. `LocalBoardHost` fills them from the provider and the session record.
