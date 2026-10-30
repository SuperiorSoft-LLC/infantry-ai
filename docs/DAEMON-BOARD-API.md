# DAEMON-BOARD-API: the DaemonApi surface a board plugin needs (design only, DAEMON-PLUGINS-3, ticket 23d334b7)

Status: **design, nothing built.** Owner priority is a clean licensing line and orchestration decoupling before launch; the board
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
| **DP-4a** | Add `Tickets`/`Epics`/`Board` interfaces + data classes to `agentHqDaemonApi`; `CoreDaemonServices` implements them over `TicketBoard`/`EpicStore` (local binding). Move `ProjectScope` into the host (applies to every `Tickets` call). Golden tests per operation. | M |
| **DP-4b** | `TicketBoard` events: replace `transitionSink` with typed ticket events on `api.events`; `triggers`, `DoneWorkerReaper`, ticket-files recorder subscribe through the API. | M |
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
