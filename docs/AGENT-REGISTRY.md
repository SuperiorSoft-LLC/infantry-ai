# AGENT-REGISTRY-1: agents decoupled from orchestration

Ticket a1d0867a, 2026-10-30. Related: RUNTIME-4 (1acd09e2, Infantry_Agents extracted), RUNTIME-14 (b478ff9b, Infantry_Roles jar),
ORCH-1 (608d1418, orchestration behind `OrchestratorLink`), HARNESS-PLUGIN-3/4 (f0178091, 24a2706c, the manifest-contribution
pattern copied here), RUNTIME-LOAD-3/4/5, STACK-NEUTRAL-1.

**Owner rule (2026-10-30).** Orchestration depends on agents. Agents never depend on orchestration. A role-playing game, a news
reader or a support desk built from agents must work with no tickets, board, worktrees or branches. Today's agents (architect,
backend, pm, automation...) are the ones the orchestration layer contributes.

**Tiers (owner shorthand, PM mail 2026-10-30).** Each piece below carries two tags:

- **Layer**:
  - 1 = platform core
  - 2 = official core plugins
  - 3 = the orchestration plugin
- **Kind**:
  - **A** = abstract: an interface or mechanism others build on
  - **S** = specific: a concrete implementation

So `1A` is core abstract, `2A` is a plugin abstract, and `2S`/`3S` are specifics. A lower tier never names a higher one.
Dependency direction: 3 -> 2 -> 1, and agents -> harnesses.

---

## 1. The audit: where the coupling is today

`K` = `superiorSoftLibrary/agentHq/src/main/kotlin/com/superiorsoftllc/agenthq`. In the "Now" column, *core* means the piece
sits in the daemon jar today.

### 1.1 Coupling table

| # | Piece (file) | Now | Target | Coupling found | Proposed cut |
|---|---|---|---|---|---|
| 1 | Plugin loader, guard, trust, `DaemonPluginHost`, `PluginGuard`, `DaemonApiClassLoader` | core | **1A** | none | stays |
| 2 | `DaemonApi` (`agentHqDaemonApi/DaemonApi.kt`) | core | **1A** | **names board**: `tickets`, `epics`, `board`, `events.onTicket`, `hooks.orchestratorBinding` are on the core API | Move them behind an optional service lookup (`api.service(Board::class)`) that the orchestration plugin provides. Additive first, then deprecate. Follow-up AR-5 |
| 3 | `daemon.api.board.*`, `daemon.api.orchestrator.*` | core API | **3A** | it is the orchestration contract, but it is shipped inside the core API jar | Becomes the orchestration plugin's published API (a plugin abstract). AR-5 |
| 4 | Harness API + `HarnessRegistry`, `HarnessModules` | core | **1A** | none | stays |
| 5 | claude / opencode / pi harnesses | core | **2S** | none toward orchestration | already pluggable (HARNESS-PLUGIN); move out later |
| 6 | **Agent registry** (`K/agents/registry/AgentRegistry.kt`, `daemon.api.agents`, module.json `agents`) | new | **1A** | none (guard test) | **this ticket** |
| 7 | Config manifest (`DaemonConfig`, CONFIG-8) | core | **1A** | none | stays |
| 8 | Session runtime: `SessionManager`, `Session`, `SessionStore` | core | **1A** for the session mechanism | **heavy**, see 1.2: worktree fields on `SessionRecord`; `ticketId`; `WorktreeProvisioner` param; `briefFor`; ticket deadline; `busyTwin` (one ticket per agent); `gateLock` keyed by ticket; `skillsFor(agentId, ticketId)`; PROGRESS-ALL commit counts | Session = an agent instance in a chat. Work binding (worktree, branch, ticket, brief, deadline, twin rule) becomes a **session-binding capability** the orchestration plugin attaches. AR-3 |
| 9 | `SessionRecord` wire shape, UI mirror (`agenthqUiCore/.../WireProtocol.kt:1060`: `worktreePath/Branch`, `repoRoot`, `worktreeBaseRef`, `ticketId`, `unattended`) | core protocol | **1A** shape + **3S** fields | ticket and worktree fields on every session | Keep them on the wire (additive rule). Re-home them as a `binding` sub-object owned by orchestration. AR-3 |
| 10 | `SpawnOptions` (`agentHqProtocol/Spawn.kt`) | core | **1A** | none (no ticket or worktree fields) | stays |
| 11 | Roles: `RoleCatalog`, `RoleBundle`, `RoleService`, `CustomRoles`, `RoleIds` | core | **2A** (roles are a persona source agents may reference) | `RoleIdMigration.kt:28-42` runs SQL on the `tickets` table | Roles move to Infantry_Roles' daemon half. The ticket SQL moves with the board's own migration. AR-4 |
| 12 | Bundled role personas (`resources/roles/*.md`) | core resources | **3S** | 8 personas tell the agent to call `agenthq_ticket_related_files`; pm/architect personas say how to write tickets; version-control persona mentions branches | They are orchestration's agents: move them into the orchestration plugin's `agents` block (persona files beside it). AR-2 |
| 13 | `RoleIds.CANONICAL` (16 ids) | core | **3S** (the orchestration crew) | a list of orchestration jobs, kept in core code | It becomes data once 12 moves. Core keeps only `PATTERN`. AR-2 |
| 14 | Agent profiles (`K/agents/AgentProfiles.kt`) | core | **2A** (named agent instances: provider/model/persona/traits) | none in code; the `role:<id>` form exists for tickets (comment :325) | Becomes a registry override layer ("my architect on opus"). Undecided owner item: Agent Profile may be deprecated (note `profile-undecided-may-deprecate`) |
| 15 | Knowledge, SharedKnowledge, skills, traits (`K/agents/*`) | core | **2A** | comments only | stays plugin-abstract (first-turn context providers) |
| 16 | First-turn composer (persona + identity + notes + brief) | core | **1A** pipeline + **2S/3S** providers | the ticket brief is spliced in by `SessionManager.withBrief` | Make it a list of first-turn context providers. The brief is orchestration's provider. AR-3 |
| 17 | Daemon-communication: MCP server (`K/cli/mcp`), CLI (`K/cli`) | core | **2S** (the plugin that talks to the daemon) | `McpTools` serves `board`; `McpTicketTools` serves `ticket_start`; `McpPipelineTools` serves `pipeline`/`pipeline_record`. They are next to the session and mail tools | Split it. Session, mail, agent and watch tools go to the daemon-communication plugin (2S). The ticket, board and pipeline tools go to orchestration (3S). The list is `BuiltinToolsListGuardTest`'s ratchet. AR-6 |
| 18 | Infantry_Agents daemon half (harness_status/install, list_harnesses, progress, **agent_list/get/enable/disable**) | official | **2S** | the `agenthq_list_harnesses` description tells agents to use `agenthq_ticket_create/start` | wording only. Move that sentence into the orchestration persona text. AR-6 |
| 19 | Infantry_Agents UI (`Sidebar.kt:127-141`, `SidebarBoardButton.kt:23`, `TicketTag`) | official | **2S** | a Board button and ticket tags in the agents panel | Orchestration **injects** them into the panel (BLOCKS-INJECTORS). AR-7 |
| 20 | Infantry_Roles daemon half (role tools) | official | **2S** | none | stays |
| 21 | Infantry_Roles UI (`RolesAttention.kt:186` shows `worktreeBranch`) | official | **2S** | reads an orchestration field | read it through the binding sub-object once AR-3 lands, or have orchestration inject it. AR-7 |
| 22 | Mail / COMMS (`K/mailbox`) | core | **2S** | `starter` = who started the ticket | stays generic: mail between sessions. "starter" stays a lineage concept |
| 23 | Policy / spawn gate / limits | core | **1A** | `launchGate(provider, origin, ticketId)` (`SessionManager.kt:306`) | The gate takes an opaque binding instead of a ticket id. AR-3 |
| 24 | Triggers (`K/triggers`) | core | **2S** (schedules for agents) | none found | The schedule runner for registry agents lives here. AR-1 |
| 25 | Ticket workers' health (`K/session/TicketWorkerHealth.kt`), process watch, `ProcessLedger`, gate lock | core `session/` package | **3S** | board rules in the session package | Move to orchestration. AR-3 |
| 26 | `backlog/`, `worktree/`, `orchestration/`, `pipeline/`, `epics/`, `ticketfiles/`, `projectgit/`, `context/`, `mvp/`, `stories/`, `issues/`, `LocalBoardServices`, `GrantedBoard`, `cli/TicketStarter` etc. | core | **3S** (with `OrchestratorLink` **3A**) | they are the orchestration | Becomes the orchestration plugin. ORCH-1's `OrchestratorLink` is its interface. No official plugin implements `orchestratorBinding` yet, only a test fake |
| 27 | Infantry_Board, Epics, Pipeline UI plugins | official | **3S** | n/a | stay; they become the orchestration plugin's UI half |
| 28 | Acme_NewsAgent (new example) | example | **community S** | none (guard test) | proof of the cut |

### 1.2 Session-layer couplings, itemised for AR-3

All in `K/session/`.

**`Session.kt`**

- `:5` imports `WorktreeRef`.
- `:83-89` worktree identity on every record.
- `:127` `ticketId`.
- `:133` `contextSources` includes `ticket`/`linkedTickets`.
- `:137` `unattended`.
- `:340` `toWorktreeRef()`.

**`SessionManager.kt`**

- `:77` `WorktreeProvisioner`.
- `:107`, `:1338-1363`, `:1954` ticket deadline (`AGENTHQ_TICKET_DEADLINE_MIN`).
- `:110`, `:1317`, `:1353` `gateLock.liveFor(id, ticketId)`.
- `:306`, `:322` `launchGate(..., ticketId)`.
- `:347`, `:379-390` `skillsFor(agentId, ticketId)`.
- `:499-507` commit counts for ticket sessions.
- `:814-834` start params `branchHint`, `ticketId`, `preWorktree`.
- `:870-934` worktree selection.
- `:852`, `:979`, `:1666` `busyTwin`.
- `:983`, `:1018-1030` `withBrief` (wired at `DaemonMain.kt:1346`, `board::brief`).
- `:1105` `carry`.
- `:1134`, `:1167` re-resolve by ticket.

**Not coupled.** `InfantryEnv.kt` (sessions get only `AGENTHQ_SESSION_ID`/`ROLE`/`AGENT_ID`) and `RoleInstance.kt`.

### 1.3 The layer line, as input for LICENSE-MAP

The line the code points at:

- **1A**: rows 1, 2 (minus the board), 4, 6, 7, 10, the session mechanism of 8, the 16 pipeline, 23.
- **2A/2S**: rows 5, 11, 14, 15, 17 (the communication half), 18-22, 24.
- **3A/3S**: rows 3, 12, 13, 25-27.

**Owner decision O1 (2026-10-30, relayed by the PM):** only platform core (layer 1) is MPL-2.0. Every plugin is Apache-2.0
(layers 2 and 3), including anything decoupled out of orchestration later. LICENSE-MAP should carry this line. This ticket
added no licence file.

---

## 2. Options considered for the registry

| Option | What | For | Against |
|---|---|---|---|
| **A. Registry in core, contributions from manifests + code (chosen)** | `AgentRegistry` in core. `agents` in module.json, `DaemonApi.agents.contribute` for code. One user override layer | The same pattern as the harness registry, which works and is already understood. Data-only modules can contribute (no jar). Core owns the one place ids are arbitrated. The test of the cut passes with no orchestration loaded | Core gains about 150 lines. It is a mechanism, not a policy, which fits "core = mechanisms only" |
| B. Registry as an official plugin (Infantry_Agents owns it, others reach it through a plugin service) | a plugin abstract | Core is smaller | Plugin-to-plugin services do not exist yet (would need a service bus first). Spawn (core) must ask a plugin before it starts a session, which inverts the layers |
| C. Keep roles as the only agents (extend `Role` with harness/tools/schedule) | no new concept | Least code | Roles are an orchestration-flavoured persona catalog. A plugin's agent would have to become a "role" file. Couples the news agent to the roles plugin. Fails the owner's layering |

**Trade-off recorded.** A puts a registry in core. The owner's layer text names "registries (harness, agent, config)" as core, so this
follows their list. The PM's tier shorthand lists "agent registry" among the plugin abstracts. The two are reconciled like this:

- The **registry mechanism** (store, arbitration, overrides, the API) is **1A**.
- The **agent vocabulary built on it** (roles as persona sources, profiles, traits, skills) is **2A**.

The owner settled O3: the registry is core abstract (1A), as built.

---

## 3. Design

### 3.1 The contract (abstract, 1A)

`plugin.api.AgentDefinition` (`agenthqPlugins` commonMain, so desktop, wasm and Android share it):

| Field | Meaning |
|---|---|
| `id` | `[a-z][a-z0-9-]{0,31}` (the role-id shape). A community module's ids must start with `<vendor>-` |
| `name`, `description` | display name, and one line for lists and editors |
| `instructions` | the agent's own system prompt. Blank + `role` = the role's persona, read at spawn |
| `role` | optional role reference |
| `profile` | optional reference to an agent profile, a behaviour preset (owner O4, below) |
| `harness`, `model`, `contextLimit` | defaults |
| `tools` | the daemon tools it may use (a grant list). Empty = no narrowing |
| `schedule` | `{everyMinutes, prompt}`, optional |
| `ui` | `{color, icon}`, hints only |

Validation lives in `AgentDefinitions.refusal`.

**Profiles (owner O4).** An agent profile defines an agent's *behaviours*: instructions, tools/grants, model, context limit,
defaults. It is a reusable named preset that a definition references with `profile`. `AgentDefinitions.withProfile` applies it:

- a field the definition sets wins;
- a blank or empty field takes the profile's value (instructions, tools, harness, model, context limit);
- identity, role, schedule and ui are always the definition's.

The daemon maps today's stored profiles (provider, model, instructions) onto that shape. The protocol's `AgentProfile` is
unchanged; folding tools and context limit into the stored profile is additive later work.

**No field names a ticket, board, worktree or branch.** Binding a session to work is an orchestration capability (AR-3), never an
agent field.

`daemon.api.agents.Agents` (DaemonApi **1.5.0**, additive: a default getter answers `NoAgents`):

- `list()` and `get(id)`, which return `AgentEntry {definition, contributor, source, enabled, effectiveInstructions}`.
- `contribute(def): AutoCloseable`. Only ids the module's own manifest declares.
- `setEnabled(id, enabled)`. Core and official only.

### 3.2 Contributions

1. **The role catalog** (contributor `roles`, source `roles`). Each role is one agent `{id, name, description, role = id, ui}`.
   The persona is *referenced*, so a role edit applies exactly as before. First holder of its ids.
2. **module.json `agents`** (source `manifest`). Data only, no jar needed. `AgentModules.sync` runs on every resolution, like
   `HarnessModules`, and **before** the daemon halves start, so `contribute` finds the declared ids. Community modules need:
   - the grant `agents.contribute`;
   - ids under their vendor prefix.

   One bad row refuses that module's whole block (fail closed), never the daemon.
3. **Code** (source `service`). `api.agents.contribute(def)` replaces the module's own manifest entry of that id while the handle
   is open. Example use: instructions that carry fetched state.

Precedence: first holder wins (roles, then modules in resolution order). A shadowed id is logged.

### 3.3 Override layer

There is one user layer, behind the `AgentOverrides` interface (`K/agents/registry/AgentOverrides.kt`). **Owner O5:** overrides
belong in the unified config JSON (CONFIG-MANIFEST-DESIGN.md). The interim store is `FileAgentOverrides`, which writes
`<state dir>/agents/overrides.json` (`{"disabled": [...]}`).

**Swap point:** the `overrides = ...` argument where DaemonMain builds the `AgentRegistry`. A config-backed `AgentOverrides`
replaces the file store there, and nothing else changes. A disabled agent starts no new session:
core's spawn gate checks it before the plugins' gates. Running sessions carry on.

**With no file, every agent is enabled, so behaviour is unchanged.** Per-field overrides (harness, model, context limit per
agent) are the natural next fields. They are not in this slice: today the role binding (ROLE-1b) already does that for
role-agents.

### 3.4 Surface

| Tool (Infantry_Agents, 2S) | Action | Rule |
|---|---|---|
| `agenthq_agent_list`, `agenthq_agent_get` | `agents.registry.read` | everyone |
| `agenthq_agent_enable`, `agenthq_agent_disable` | `agents.registry.write` | refused for an agent caller (same rule as role create/delete) |

CLI: `agenthq agent-def list | show <id> | enable <id> | disable <id>`. It makes one `plugin_tool_call` per command, so there is
one gate and one answer. `agents` stays the live-sessions command.

### 3.5 A session is an instance of an agent

The daemon's spawn path still takes a role id, so a registry agent's id is accepted where a role id is:

- **`roleExists`**: catalog ids, plus registry ids.
- **`rolePersona`**: the catalog persona, else the registry agent's `effectiveInstructions`.

This is additive: catalog ids resolve exactly as before, and only ids no role holds gain an answer. A news agent's session is
therefore `spawn(role = "acme-news")` with its instructions as the persona. It is a plain chat with no ticket, worktree or
branch: when no orchestration binding asks for one, the session runs in the project's directory or the default working
directory.

**Schedule.** It is declared data in this slice. *Running* a scheduled turn spawns or resumes an LLM session on a timer, which
costs money unattended. Who may do that is owner item O2. The registry exposes the schedule. The runner (Triggers, row 24) is
AR-1. A plugin can already run its own non-LLM periodic work with `api.schedule` (the news agent fetches its feed this way).

**Tools narrowing.** `tools` is declared but not yet enforced. Enforcement needs the harness to receive an allow-list
(the MCP `tools/list` filtered by the session's agent). AR-8.

### 3.6 Guard: `AgentRegistryLayerGuardTest`

The test enforces the layer direction for:

- `daemon.api.agents`;
- `plugin.api.AgentDefinition`;
- `K/agents/registry`;
- `daemon/plugins/AgentModules.kt`;
- the Infantry_Agents and Infantry_Roles daemon halves;
- Acme_NewsAgent.

None of them may import or name `backlog`, `worktree`, `orchestration`, `pipeline`, `epics`, `ticketfiles`, `projectgit`,
`daemon.api.board` or `daemon.api.orchestrator`. The registry package also may not name `role` (roles depend on the registry,
not the reverse).

---

## 4. Decision taken in this ticket: who contributes today's built-in agents

The ticket asked for a choice between the orchestration plugin and a core-agents contribution. I chose **neither yet: the role
catalog contributes them, as source `roles`.**

**Why.** No orchestration plugin module exists to hold them (row 26: no official `orchestratorBinding`). Copying 17 personas
into a module.json now would fork the persona text away from the role editor (ROLE-EDIT-1). The adapter (`role/RoleAgents.kt`)
gives identical behaviour, which the golden tests prove.

**Where they go.** Rows 12-13 say the personas *are* orchestration's agents. When the orchestration plugin exists, its
module.json `agents` block lists them (`role` references or persona files) and core's `RoleIds.CANONICAL` becomes data (AR-2).

## 5. Follow-ups (filed as tickets)

| Id | What | Layer |
|---|---|---|
| AR-1 | Schedule runner for registry agents (Triggers), behind owner decision O2 | 2S |
| AR-2 | Move the bundled personas + canonical ids into the orchestration plugin's `agents` block | 3S |
| AR-3 | Session binding capability: worktree, ticket, brief, deadline, twin rule and gate lock out of `SessionManager` into an orchestration-provided binding. First-turn context providers | 1A + 3S |
| AR-4 | Roles' daemon code into Infantry_Roles. `RoleIdMigration`'s ticket SQL to the board | 2A |
| AR-5 | `DaemonApi.tickets/epics/board/onTicket/orchestratorBinding` behind an optional service lookup provided by orchestration | 1A/3A |
| AR-6 | Split the MCP/CLI tools: communication plugin (2S) vs orchestration (3S). Move the ticket wording out of `agenthq_list_harnesses` | 2S/3S |
| AR-7 | Agents/Roles UI: Board button, ticket tags and branch label injected by orchestration | 2S/3S |
| AR-8 | Enforce `AgentDefinition.tools` (per-session MCP allow-list) | 1A |

## 6. Owner decisions (answered 2026-10-30, relayed by the PM)

| Item | Question | Answer |
|---|---|---|
| **O1** | the licence line | Platform core (layer 1) is MPL-2.0. Every plugin is Apache-2.0 (section 1.3) |
| **O2** | unattended scheduled agents | Schedules stay data-only in this ticket. The PM presents the runner policy to the owner as AR-1: default off, per-agent opt-in, cost cap |
| **O3** | registry as core or as a plugin abstract | **Core abstract (1A)**, as built |
| **O4** | what an agent profile is | A behaviour preset that a definition references. Folded in as `profile` + field override (section 3.1) |
| **O5** | where overrides live | In the unified config JSON. `overrides.json` is the interim store behind `AgentOverrides` (section 3.3) |

## 7. Example: Acme_NewsAgent (`plugins/examples/Acme_NewsAgent`)

This is a community example built with no orchestration at all:

- an agent `acme-news`, declared in module.json `agents`, with a schedule;
- a tool `acme_news_fetch` that fetches a plain-HTTP RSS/Atom/text feed and returns item titles;
- a plugin `api.schedule` that refreshes the cached feed every N minutes;
- `contribute` to keep the agent's instructions carrying the latest headlines.

Grants: `daemon.code`, `daemon.tool`, `agents.contribute`.
