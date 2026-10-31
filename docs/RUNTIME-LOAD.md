# RUNTIME-LOAD-1: what loads at runtime, measured

Ticket 3e3186b5. Base `feature-agenthq-mvp` @ 55ebe83a. Builds on `RUNTIME-PLUGINS-AUDIT.md` (be38a342), `CONFIG-FIELDS.md` G13
(aa17b1d2) and `SHELL-LEAKS.md`. The old audits were not trusted: every row below was measured on this tree.

## 1. How it was measured

`:superiorSoftLibrary:agentHq:installDist`, then the installed daemon (`lib/*`, main class `DaemonMainKt`) on port 8812 with a temp
state dir, twice, with the same `AGENTHQ_STATE_DIR` / `AGENTHQ_PORT` and no other `AGENTHQ_*` inherited:

| Run | `AGENTHQ_PLUGINS_ROOT` | `AGENTHQ_BUNDLED_MODULES` |
|---|---|---|
| (a) full | empty temp dir | not set: the install's own `modules/` (3 daemon halves) |
| (b) empty | empty temp dir | an empty temp dir |

Read back: `GET /plugins` (modules, daemon state, tools) and `agenthq harnesses` (what a ticket can pin). Daemon log: `[plugins] ...` lines.
The UI side is not re-measured here: `BlankShellE2ETest` covers it, and `RUNTIME-PLUGINS-AUDIT.md` section 1a stands (0 of 20 UI
modules are runtime-loaded in the shipped desktop app; they ride `runtimeOnly(agenthqAppUi)`).

Result (before this ticket's change):

| Thing the daemon reports | (a) full | (b) empty |
|---|---|---|
| Plugin modules | Infantry_Board, Infantry_Processes, Infantry_Usage, all `loaded`, daemon half `loaded` | none |
| Plugin tools | `agenthq_ticket_files`, `agenthq_ticket_related_files`, `agenthq_processes`, `agenthq_processes_reap`, `agenthq_usage` (5) | none |
| Harnesses | `opencode` (default), `claude`, `pi` | **`opencode`, `claude`, `pi` (identical)** |
| Providers (`/health`) | `opencode` | `opencode` |
| Built-in MCP tools (cli/mcp) | 34 | 34 |
| Policy presets | none read back (not probed over HTTP; by the build files only the docker image adds `plugins/demo/Demo_Lock/policies`) | same |

An empty plugins dir removes exactly 5 agent tools and nothing else: the daemon, every harness, every provider, every feature
service and 34 MCP tools are still there. That is the gap to "the whole app installable into a blank shell".

## 2. Table: what is still wired by code

Size: S = under a day, one ticket; M = a few tickets; L = a design plus a contract bump.

| Item | Wired in (file) | Loaded from data? | Size to fix |
|---|---|---|---|
| Daemon plugin host + tools from `modules/official/*` | `daemon/plugins/DaemonPluginHost.kt` | **yes** (3 halves before, 4 after this ticket) | done |
| `agenthq_harness_status` | was `cli/mcp/McpTools.kt`; now `plugins/official/Infantry_Agents/daemon` | **yes (this ticket)** | done |
| `agenthq_harness_install` | was `cli/mcp/McpTools.kt`; now `plugins/official/Infantry_Agents/daemon` | **yes (RUNTIME-LOAD-2, F1)** | done |
| `agenthq_list_harnesses` | was `cli/mcp/McpTools.kt`; now `plugins/official/Infantry_Agents/daemon` | **yes (RUNTIME-LOAD-2, F2)** | done |
| `agenthq_list_sessions`, `agenthq_get_session`, `agenthq_pod_state` | `cli/mcp/McpTools.kt` | no | M, see section 6: they are not pure frame reads |
| 30 other built-in MCP tools (roles 5, tickets 9, mail 3, pipeline 2, ask, project config 2, sessions/spawn 7, watch_file) | `cli/mcp/Mcp*Tools.kt`, ratchet `BuiltinToolsListGuardTest` | no | M each family (DAEMON-PLUGINS.md section 6) |
| Harness plugins `claude`, `pi` | `harness/claude`, `harness/pi`, `META-INF/services/...HarnessPlugin` in the daemon jar | no (ServiceLoader on the daemon's own classpath) | L: needs a harness API module, a folder source for `HarnessPluginSource` (none exists), trust rules |
| Harness plugin `opencode` (provider) | `provider/ProviderFactory.kt` `builders` map | no | L, same as above; `stub` is test/E2E only |
| Harness descriptors (JSON) | `harness/descriptor/HarnessDescriptorLoader.kt` | **yes** | done (cannot express `pi`'s session adapter or `claude`'s model catalog, so it does not replace them) |
| Daemon feature services (tickets, mailbox, knowledge, roles, pods, triggers, epics, prompts, stats, usage, projectgit, commands, questions, stories, update) | `agentHq/src/main/kotlin/.../{backlog,mailbox,memory,...}`, `daemon/DaemonMain.kt` | no | L each; D11 parks this until the UI plugins are done |
| 20 desktop UI modules | `agenthqAppUi` srcDirs, `CoreModules.kt`, ServiceLoader | no (only the `App_UI.ihq` drop-in bundle, opt-in) | L: blockers B1-B6 of RUNTIME-PLUGINS-AUDIT.md |
| Trust sections of the core Config (Plugins, Secrets, Pair, Updates, About, Logs) | `agenthqUiCore` Config | no, by design | core (D4) |
| `ConfigEntry` fallback in the core Config | `agenthqUiCore` ConfigDialog | no | S-M (CONFIG-FIELDS.md G13) |

## 3. What moved: `agenthq_harness_status` -> Infantry_Agents daemon half

Why this one: smallest tool in a family the guard test already assigned to "Infantry_Agents daemon half"; one read-only frame
(`harness_install_status`), no write, no long call in the default path, and `Infantry_Agents` already existed as an official module.

- New `plugins/official/Infantry_Agents/daemon/` (`AgentsDaemonPlugin`, jar `Infantry_Agents-daemon.jar`), declared in the module's
  `module.json` (`daemon` block; action `harness.install.read`, the same action that gated the old tool).
- `agentHqDaemonApi` gains `daemon.api.harness` (`HarnessInstallModel`, `HarnessInstallText`): the printed tables of
  `harness-status` / `harness-install`, now shared by the CLI (`HarnessInstallClient.render` calls it) and the plugin.
- Deleted from core: the descriptor, the dispatch, the `harnessStatus` handler and `HARNESS_STATUS` in `cli/mcp/McpTools.kt`.
- `module.json` `daemon.limits.toolTimeoutMs = 900000`: `checkLatest=true` runs `npm view` per harness (the CLI allowed 15 minutes).
- One daemon fix: the async `harness_install_status` / `harness_install` answers now echo the request id (a plugin tool waits by
  request id; the CLI matches by answer type and is unaffected). No wire change, no contract bump.

Parity (tests, all green): `McpServeTest.harnessStatusToolComesFromInfantryAgentsUnchanged` (descriptor equals the deleted one,
and the call's text equals what the old in-core code built from the daemon's own frame, byte for byte) and
`harnessStatusToolIsGoneWithoutInfantryAgents` (plugin absent: tool not listed, `agenthq_harness_install` still there, no error).

Guard: `BuiltinToolsListGuardTest` (the list only shrinks, `CEILING` 34 -> 33) plus
`harness status is not wired in core and the Infantry_Agents module declares it`.

Known edge: the MCP client waits 1.25x the GLOBAL tool timeout (75 s by default) for any plugin tool, so a `checkLatest=true` call
that takes more than 75 s now errors where it used to wait 15 minutes. `npm view` for three packages takes seconds. F1 fixes it.

## 4. Remaining items, in order

| # | Follow-up | Size | Why this order |
|---|---|---|---|
| F1 (done, RUNTIME-LOAD-2) | Per-tool timeout in the plugin tool contract (`McpPluginTools.answerWaitMs` reads the tool's declared `toolTimeoutMs`); then move `agenthq_harness_install` | S | Unblocks the last two harness tools; removes the edge above |
| F2 (list_harnesses done, RUNTIME-LOAD-2; the three session tools: section 6) | `agenthq_list_harnesses` + `agenthq_list_sessions` / `agenthq_get_session` / `agenthq_pod_state` into the Infantry_Agents half (read-only frames) | S-M | Same pattern, no new API |
| F3 (done, RUNTIME-LOAD-3) | Roles tools (5) into an Infantry_Roles daemon half (agent-caller persona rule moves with it) | M | Next family with a ready owner module |
| F4 (done, RUNTIME-LOAD-3) | `agenthq_ask` into Infantry_Attention; project config tools into Infantry_Settings | S each | One frame each |
| F5 (all but one done, RUNTIME-LOAD-4/5: sections 10, 11) | Tickets/epic/board/progress/session-read tools into Infantry_Board / Infantry_Agents; left: `agenthq_ticket_start` (RUNTIME-LOAD-6, with spawn/kill and the host spawn service) | M | Biggest family, riskiest scope rules |
| F6 (mail and pipeline done, RUNTIME-LOAD-8: section 14; `watch_file` left) | Mail, pipeline, `watch_file` | M | `watch_file` needs a client-run tool seam |
| F7 | A folder source for `HarnessPluginSource` + a harness API module; move `pi`, then `claude`, then `opencode` | L | Contract design; `pi` first, fewest daemon internals |
| F8 (stopped, RUNTIME-LOAD-3: section 7) | Core Config `ConfigEntry` fallback removed (G13) | S-M | Independent of the daemon |
| F9 | The 20 UI modules, one per project (B1-B6 of the audit), kit exported to the filtered loader (contract bump) | L | UI-first per D11 once F1-F6 settle |
| F10 | Daemon feature services as plugins (D11: later) | L | Parked by the owner |

## 6. RUNTIME-LOAD-2 (F1, F2)

**F1: per-tool timeout as data.** `DaemonTool.timeoutMs` (default null = the module's `daemon.limits.toolTimeoutMs`, else
`limits.plugins.toolTimeoutMs`, 60 s). The daemon runs the call under `min(timeoutMs, limits.plugins.toolMaxTimeoutMs)` (new limit,
default 30 min, so a plugin cannot opt in to an unbounded wait) and lists the effective value as `timeoutMs` in its `plugin_tools`
descriptors (additive key; `agenthq mcp serve` strips it from `tools/list` and waits 1.25x that value for the call, `McpPluginTools.answerWaitMs`).
`agenthq_harness_status` (15 min, as the CLI allowed; fixes the >75 s regression above) and `agenthq_harness_install` (15 min) declare it;
the module-wide `limits.toolTimeoutMs` in Infantry_Agents' `module.json` is gone, so the other tools keep the 60 s default. No frame
changes shape (a new optional key in a descriptor the MCP server consumes), so no contract bump; CONTRACT.md notes the limit.
- `agenthq_harness_install` moved: descriptor byte-identical to the deleted one (`McpServeTest.harnessInstallToolComesFromInfantryAgentsUnchanged`),
  text from the shared `HarnessInstallText`, a failed harness still makes the result an error, gated by `harness.install` (write).
  Absent plugin: not listed, no error (`harnessStatusToolIsGoneWithoutInfantryAgents`). Ratchet `CEILING` 33 -> 32.

**F2: `agenthq_list_harnesses` moved** (frames `providers` + `models`, action `harness.read`). The merge of the two answers is the API's
`HarnessCatalogText.merge` (models `ProviderInfo` / `ProviderModels` in `daemon.api.harness`), which the CLI's `HarnessCatalog` now
calls too, so `agenthq harnesses` and the tool cannot drift. Descriptor byte-identical; the answer keeps its keys and `howToUse`.
One wording difference: if the `models` frame fails, the note says the daemon's error text where the old code said the stream's
exception message. Ratchet 32 -> 31.

**Not moved, and why:** `agenthq_list_sessions`, `agenthq_get_session`, `agenthq_pod_state`. They are not "pure reads of what DaemonApi
already exposes": all three depend on `ProjectScope` (reads `AGENTHQ_SESSION_ID`, the privileged-role rule), on the wire `SessionRecord`
(about 40 fields, `BoardSummary`, `ProgressReducer`), and `get_session` streams a replay fenced by a second `sessions` frame, which a
one-request `ToolCall.request` cannot do. Moving them needs an API-side session model plus a `ToolCall` streaming/fence primitive and
`ProjectScope` as an API helper (the same blocker as the Infantry_Board family, F5). Do it with F5, not as a stopgap.

## 5. Not run (RUNTIME-LOAD-1; RUNTIME-LOAD-2 ran agentHq (2737 tests), agentHqUi, agenthqUiCore jvmTest incl. BlankShellE2ETest: green after fixing two DEMO-FIX-1 leftovers; not run: wasm/Android builds, a live harness install)

The full `agentHq:test` suite, `agentHqUi` and wasm/Android builds, `BlankShellE2ETest`, any live harness install. Run: the five test
classes named in section 3 plus `*DaemonPlugin*` and `*HarnessInstall*`; and `installDist` with both measurement runs above.

## 7. RUNTIME-LOAD-3 (F3, F4, F8)

**F3: roles tools** (`agenthq_role_list/get/update/create/delete`) moved to a new `Infantry_Roles` daemon half (actions `roles.read`,
`roles.save`, `roles.delete`, the ones the frames already had). **F4:** `agenthq_ask` to `Infantry_Attention` (`questions.ask`),
`agenthq_project_config_get/set` to `Infantry_Settings` (`projects.save`, as the `project_config` frame, read included, so Demo_Lock and
presets behave as before). Each half is `daemon/build.gradle.kts` + a `daemon` block in `module.json`; packaging picks it up through
`gradle/daemon-halves.gradle.kts` unchanged (`verifyDaemonHalvesGuard` green). Core classes `McpRoleTools`, `McpQuestionTools`,
`McpProjectConfigTools` deleted; ratchet `CEILING` 31 -> 23; `OrchestratorBoundaryGuardTest` baseline `cli>role` 3 -> 1.

DaemonApi 1.1.0 (additive): `ToolCall.clientSessionId` / `clientRole`, the MCP process's `AGENTHQ_SESSION_ID` / `AGENTHQ_ROLE`, sent by
`agenthq mcp serve` as `client` in `plugin_tool_call`. They carry the agent-caller persona rule (agent = either set, or the connection holds
the agent token) and the asker of `agenthq_ask`. A client's own claim, used only to narrow. New API models: `daemon.api.roles`
(`RoleRecord`, `RoleSaveRequest`, `RetiredRoleIds`, now also read by core `RoleIds`) and `daemon.api.project` (`ProjectConfigPatch/View`).
Daemon fix: `role_save` / `role_delete` answered only by broadcast; a plugin request (`plugin:` id) now also gets a direct, id-echoing reply.
Plugin tool lists are sorted by name, so `tools/list` order of these tools changed (set unchanged).

**F8: not done.** The `ConfigEntry` fallback is not independent. `ConfigDialog` draws from the snapshot plan only when the daemon has sent
one (`planDeclarations`); with none (daemon not yet connected, or an older daemon) it shows the hand-written `ConfigEntry` tabs, which hold
the client-local settings (theme, blur, markdown, voice, sound...). Removing it would leave the Config dialog empty offline and on a blank
install before the first snapshot. `ConfigEntry` is also the `present` key set the generated path takes (`ConfigPlan.of(snap, present, ...)`)
and anchors 13 UI test files. Prerequisite: client-local sections declared in a snapshot the client ships itself, then `ConfigEntry` can go.

## 8. BOARD-API-1 (DP-4a): the API the board family (F5) needs now exists

`DaemonApi` 1.2.0 (additive) adds `tickets`, `epics` and `board` (`agentHqDaemonApi/.../api/board/BoardApi.kt`, `BOARD_API_VERSION` 1.0.0),
bound locally over `TicketBoard` / `EpicStore` (`daemon/plugins/LocalBoardServices.kt`) and wired in `DaemonMain` through
`CoreDaemonServices`. Nothing moved or was deleted: frames, CLI and MCP are unchanged. The host applies policy and project scope to
every call; community plugins need the grants `tickets.read|write|delete|start`, `epics.read|write` (`GrantedBoard`).

* **F5 is unblocked in this respect:** a board/ticket tool can now be written as a plugin half over `api.tickets` (pass
  `call.caller.copy(activeSession = call.clientSessionId)` as the `Caller`, so the host scopes it to the calling session).
* **Not migrated (the "prove it" item):** `agenthq_ticket_get` stays in the CLI MCP server. Deleting it breaks the tests that call it
  through `McpTicketTools.GET` against plain daemons that load no plugins (`McpTicketToolsTest`, `McpBoardShortIdE2eTest`,
  `McpTicketImagesE2eTest`, `McpTicketDeleteE2eTest`, `AgentTicketStartE2eTest`, `McpServeTest`), which must be re-pointed at a daemon
  with `Infantry_Board` loaded first. Instead `LocalBoardServicesTest` proves the API carries every field the tool answers
  (byte-identical JSON rendered from `TicketInfo`). That re-pointing is the first job of DP-5 / the F5 ticket.
* The old `BuiltinToolsListGuardTest` reason ("needs ProjectScope as an API helper") is now only the test re-pointing above.

## 9. BOARD-API-2: the first F5 slice (ticket read tools) and what is left

`agenthq_ticket_get` is now Infantry_Board's daemon tool (`TicketGetTool`, over `api.tickets.get` with
`call.caller.copy(activeSession = call.clientSessionId)`; policy action `board.read`, kind `read`, the action of the `ticket_get` /
`ticket_list` frames it used to send). `agenthq_ticket_files` / `agenthq_ticket_related_files` were already there (TICKET-FILES). The six
tests that called `McpTicketTools.GET` on plain daemons now load the built Infantry_Board jar (`TestDaemonPlugins.boardRoot`);
`McpServeTest.ticketGetComesFromTheBoardPluginUnchanged` holds the byte-for-byte descriptor, the answer's field order and the action; the
plain-daemon `tools/list` no longer has the tool (vanishes cleanly). `BuiltinToolsListGuardTest` ceiling 23 -> 22.

What is left of F5 in core, and why each stays for now:

| tool | why it stays |
|---|---|
| `agenthq_board` | NOT a pure read of tickets: `BoardSummary.render` also needs the daemon's `SessionRecord`s (who works on which card, git commit of the sessions). Needs a sessions read model in the API (or `Board.summary` rendered by the host) first. |
| `agenthq_ticket_create`, `_update`, `_move`, `_delete` | The WRITE side: image upload/attach (`TicketImageFiles`, local file paths the MCP process reads and uploads over `image_upload`), `TicketPlacement` (before/after/top/bottom), and weight/role/provider/model validation texts must stay byte-identical. The Tickets API covers the operations (DP-4a); the MCP process's local file access is the blocker: a plugin runs in the daemon and cannot read the agent's files. Next ticket. |
| `agenthq_ticket_start` | `McpWriteTools.startTicket` runs under `McpGuardrails` (spawn depth, concurrency, kill scope), which is host-side CLI code (DP-11), and polls the worker via `TicketStarter`. |
| `agenthq_epic_base` | Needs `Epics.setBase` plus the same scope and refusal texts; small once the write tools move (same ticket). |
| `agenthq_progress` | Not a board tool; a session-progress report. Moves with the Agents family. |

Not run for this slice: wasm/Android builds.

## 10. RUNTIME-LOAD-4 (F5, ticket 61177938): the board write tools, progress; what is left

One commit per group, ordered by risk. Each: descriptor byte-identical to the pre-move `tools/list` (golden file
`agentHq/src/test/resources/golden/core-mcp-tools-before-f5.json`, checked by `McpServeTest.movedBoardToolsComeFromTheBoardPluginUnchanged`
and the progress test), deleted from `cli/mcp`, the tests that call it re-pointed at a daemon with the built plugin jar, ratchet lowered.

| group | tools | where | how | ratchet |
|---|---|---|---|---|
| G1 (5418e3d5) | `agenthq_epic_base` | Infantry_Board | the same `epic_base` frame on the caller's connection; action `epics.save` | 22 -> 21 |
| G2 (8b02fdba) | `agenthq_ticket_move`, `agenthq_ticket_delete` | Infantry_Board | ticket found through `api.tickets.get` (host scope); write = the same `ticket_move` / `ticket_delete` frame; placement and short ids are ONE rule in the API (`board.TicketPlacement`, `TicketRefs`), the CLI's `ticket move` uses it too | 21 -> 19 |
| G4 (76799eba) | `agenthq_progress` | Infantry_Agents | the same `progress` frame for the MCP process's session; model + text moved unchanged to `daemon.api.progress` (core keeps aliases) | 19 -> 18 |
| G3 (dea23ca0) | `agenthq_ticket_create`, `agenthq_ticket_update` | Infantry_Board | `api.tickets.create/update` (+`contextLimit`), `Tickets.mayFile` checks an explicit project BEFORE any upload; images over the new local-file contract | 18 -> 16 |

**API (DaemonApi 1.4.0, BOARD_API_VERSION 1.3.0, all additive):** `TicketRefs`, `TicketPlacement`, `TicketImages`, `ContextLimits`,
`LocalFiles`/`LocalFile`, `DaemonTool.localFiles`, `ToolCall.clientProjectId`, `TicketDraft/TicketPatch.contextLimit`, `Tickets.mayFile`
(default `Unavailable`; `GrantedBoard` needs `tickets.write`), `daemon.api.progress` (`SessionProgress`, `ProgressReport`, `ProgressText`).

**Wire 1.147.0 (additive, CONTRACT.md):** the blocker of section 9 (a plugin runs in the daemon and cannot read the agent's files) is
solved universally, not for images only: a tool declares which arguments hold local paths; `plugin_tools` lists them as `localFiles`;
`agenthq mcp serve`, which runs where the files are, sends each such file inline (`{path, size, base64?}`, bytes up to 10 MB, size only
above) and its default project as `client.projectId`. Location-transparent: it works the same against a remote daemon.

**Deviations (deliberate, all narrowing):** (1) as for `ticket_get`, the host scope is fail closed: an agent connection with no session,
or a session the daemon does not know, is refused where the CLI path widened it. (2) A create whose project comes from the MCP
server's default (`--project` / `mcp.json`) is now scope-checked too; before, only an explicit `projectId` was. A worker whose config
names a foreign project is refused (`ticket_save refused: ...`). (3) A host wait that times out words it `<frame> got no answer in N ms`
instead of the CLI stream's `no answer to <frame> within Nms`. (4) An agent may hand a tool a pre-encoded file object; it is read as
the bytes it carries (the caller's own bytes either way).

**Left in core, and why (not moved, no stopgap):**

| tool | blocker |
|---|---|
| `agenthq_ticket_start` | Runs under THIS MCP process's guardrails: spawn depth from its local lineage file and the set of sessions it opened (`McpGuardrails`, `spawned`), state that lives in the client process, and it is the same code path as `agenthq_spawn ticketId`. Needs DP-11: guardrails as a host service keyed by the calling session, then both move together. |
| `agenthq_board` | `BoardSummary.render` needs the daemon's session records (worker live state, activity, git commit): needs a sessions read model in the API. |
| `agenthq_list_sessions`, `agenthq_get_session`, `agenthq_pod_state` | Same session read model (`SessionRecord` ~40 fields, `BoardSummary.liveState/activity`); `get_session` streams a replay fenced by a second `sessions` frame, which a one-request `ToolCall.request` cannot do (needs a stream/fence primitive). `pod_state` also reads the roster and pod frames. |

Not run: wasm/Android builds, a live agent session against an installed daemon.

## 11. RUNTIME-LOAD-5 (F5 remainder, ticket 11c26ffe): the sessions read model; session and board reads moved

**API (DaemonApi 1.6.0, additive):** `api.sessionReads` (`daemon.api.sessions.SessionReads`), bound by core's `LocalSessionReads`
over the daemon's session records and replay buffer, behind `GrantedSessionReads`.

* `SessionView`: the read-only projection of a session that the tools print (ids, role, provider/model, project, state, run phase,
  live, last action/output/event, background-work label, progress, run count, last exit/error, ticket, checkout paths, spawner).
  It carries no provider session id, environment, credentials, persona or transcript.
* `caller(by)`, `scope(by, requested, default)`, `list(by, projectId|null)`, `find(ref, by)`: the HOST applies the project scope with
  `ProjectScope`'s rules, the same implementation as the board API, seeing the caller as the record of `Caller.activeSession`. It
  fails closed: an agent connection without a session, or with a session the daemon does not know, is refused. Each read is gated
  by the active preset like the frame it replaces (`sessions`, `replay_request`).
* `transcript(id, by)`: the host renders the replay buffer (the text-delta join, turn origin, stderr/error/finish/gap marks the CLI used).
  This removes the client-side `sessions` fence and its 10 s timeout.
* Grants: bundled/official modules are trusted. A community module needs `sessions.read`, and `sessions.transcript` for transcripts
  (default deny).
* Shared pure text: `daemon.api.sessions.SessionText` (live state, ago, activity, status) and `daemon.api.board.BoardLines` (the board
  lines). Core's `BoardSummary` delegates to them, so `agenthq board` and the tool print the same lines.

**Moved (descriptors byte-identical to `golden/core-mcp-tools-before-f5.json`, `McpServeTest.movedSessionAndBoardReadsComeFromThePluginsUnchanged`; deleted from `cli/mcp`; ratchet 16 -> 12):**

| tool | where | action | timeoutMs |
|---|---|---|---|
| `agenthq_list_sessions` | Infantry_Agents | `sessions.read` | 30 000 |
| `agenthq_get_session` | Infantry_Agents | `sessions.replay` | 30 000 |
| `agenthq_pod_state` | Infantry_Agents (profiles, roster and pod over the same `agent_list` / `agent_roster` / `pod_query` frames) | `agents.read` | 30 000 |
| `agenthq_board` | Infantry_Board (tickets over `api.tickets.list`, each worker through `find` in the caller's scope, git tips bounded to 60 s in all) | `board.read` | 120 000 |

**MCP server fix (all plugin tools):** while the daemon is unreachable, a tool this server does not know is no longer "Unknown tool";
the call answers the fixed `DAEMON_UNREACHABLE` text, as core tools do. A plugin tool missing from the cached listing makes the server
list again (a daemon started after `tools/list` is picked up).

**Deviations (deliberate, all narrowing):** (1) fail closed, as for the board API: a session id the daemon does not know is refused
where `list_sessions` listed every project. (2) The host does not read the MCP process's `AGENTHQ_PROJECT_ID` as a session's own
project; it uses the session record, then the client's default project. (3) A worker session with no project cannot list every
project. (4) A board line's worker is the session the caller may see; one in a foreign project reads as `worker <id> gone`.

**Split out: RUNTIME-LOAD-6, the host spawn service + `agenthq_ticket_start`.** `ticket_start` shares guardrail STATE with
`agenthq_spawn` / `agenthq_kill`: the open-spawn set, the lineage depth file and the kill scope, all held in the MCP process
(`McpGuardrails`). Moving it alone would leave two guardrail states, so a worker started through the plugin would escape the client's
concurrency count. The seam:

* `api.spawns` (DaemonApi 1.7.0), a host service keyed by the CALLING session. It is built on the daemon's existing `SpawnGuard`
  (BUG-G2d, host-side for `new` / `pod_submit`) and extended to ticket starts (`ticket_move` -> handOut), which today it does not charge.
  Depth comes from the host lineage (`spawnerSessionId` chain, persisted, so it survives a restart, unlike the in-memory lineage).
  The open-spawn count comes from live sessions whose spawner is the caller. The client's `--max-depth` / `--max-spawns` /
  `--mcp-depth` only narrow (min / max with the daemon setting); `--allow-kill-any` holds only for an owner connection without a session.
* Operations: `admit(by, requested)` -> placement or refusal (the texts of `McpWriteTools`, wording "this session" for "this MCP
  server"); `startTicket(ref, by)` (admit + the board start + record, under one lock); `mayKill(sessionId, by)` (the kill rule of
  `McpWriteTools.killRefusal`).
* Then `agenthq_ticket_start`, `agenthq_spawn`, `agenthq_kill` (and prompt / restart / remove with them) move to Infantry_Agents /
  Infantry_Board, `McpGuardrails` + `McpLineage` are deleted, and the guardrail tests prove that a plugin cannot exceed depth,
  concurrency or kill scope (the limits live in the host and the plugin only passes narrowing values).

**What remains in core after this (12 tools), and the next seam of each:**

| family | tools | next seam |
|---|---|---|
| spawn / control | `agenthq_ticket_start`, `agenthq_spawn`, `agenthq_prompt`, `agenthq_kill`, `agenthq_session_restart`, `agenthq_session_remove` | `api.spawns` built (section 12, RUNTIME-LOAD-6 slice 1); the tools move in RUNTIME-LOAD-7 |
| F6 mail | `agenthq_inbox`, `agenthq_read`, `agenthq_message` | a `daemon.api.mail` read/send service with the `MailGateHook` already in the API; the caller is the session (`clientSessionId`), cross-project refusal in the host |
| F6 pipeline | `agenthq_pipeline`, `agenthq_pipeline_record` | the same frames through `ToolCall.request` (no new API); a Pipeline official plugin |
| F6 watch | `agenthq_watch_file` | a DaemonApi watch surface (the watcher runs where the files are: the `localFiles` idea for watches, i.e. the MCP client keeps the watcher and the daemon gets the events) |
| F8 | core Config `ConfigEntry` fallback | independent of the daemon (section 7) |
| F9 | the 20 UI modules, kit exported to the filtered loader | UI-first per D11 once F5/F6 settle |
| F10 | daemon feature services as plugins | parked (D11) |

Tests run for this slice: `LocalSessionReadsTest` (scope, fail closed, grants, policy gate, host transcript), `McpServeTest` (golden
descriptors + actions, read answers through the plugin, the unreachable-daemon text), `McpOrchestrationE2eTest`,
`McpBoardShortIdE2eTest`, `BacklogLaneE2eTest`, `BoardSummaryTest`, `LocalBoardServicesTest`, `BuiltinToolsListGuardTest`,
`McpPluginToolTimeoutTest`, agenthqUiCore `BlankShellE2ETest`. Not run: the full suite, wasm/Android builds, a live agent session
against an installed daemon.

## 12. RUNTIME-LOAD-6 (ticket 8f5509ad), slice 1: the host spawn service `api.spawns`

Split (the ticket allows it): this slice builds the host service and charges ticket starts. The tools move in RUNTIME-LOAD-7
(below). The service is additive, so nothing calls it until the tools move. Until then the CLI's `McpGuardrails` stays and the
daemon's own charge (below) applies to both paths.

**API (DaemonApi 1.7.0, additive):** `api.spawns` (`daemon.api.spawns.Spawns`), bound by core's `LocalSpawns` behind
`GrantedSpawns` (bundled/official trusted; community needs `sessions.spawn`, default deny).

| operation | what the host does |
|---|---|
| `admit(by, narrowing, kind)` | the limits in force for the caller, or the refusal (depth, concurrency, pod cap); reads only |
| `spawn(request, by, narrowing)` | admit + create (as `new` binds a session) + record the caller as spawner, under ONE lock inside the pod guard's; the task runs as the first turn |
| `startTicket(ref, by, narrowing)` | the ticket in the caller's scope (`api.tickets.get`); a healthy in-progress ticket passes and starts nothing; else admit + the board's start under the same lock |
| `mayKill(ref, by)` | contract 1.114.0's kill rule (own spawns; a pm its own project; never itself; an owner without a session, any) |
| `target(ref, by)` | the one session a prompt / restart / remove acts on (full id or unique prefix) |

* **Caller** = the daemon's record of `Caller.activeSession` (a tool sets it from `ToolCall.clientSessionId`). Fail closed, as the
  read model is: an agent connection without a session, or a session the daemon does not know, is refused.
* **Depth** = the caller's place in the `SpawnGuard` lineage. It is the persisted `spawnerSessionId` chain now (the in-memory map is
  only a fast path), so it survives a daemon restart. A spawner the daemon no longer holds still counts as one level. The client's
  `--mcp-depth` claim counts only when it is deeper.
* **Concurrency** = the caller's spawns (`spawnerSessionId == caller`) that hold a slot: not ended, not released, not killed unless
  running again. An owner connection without a session is counted per `SpawnNarrowing.clientKey` (one per `agenthq mcp serve`), in memory.
* **Limits** = `limits.concurrency.maxDepth` / `mcpMaxSpawns`. The client's `--max-depth` / `--max-spawns` only narrow (min). The
  pod cap (`maxPerPod`, BUG-G2d) applies under them.
* **Refusal texts** are `McpWriteTools`', bare (a tool prefixes `<tool> refused: `). "This MCP server" becomes "This session" (or
  "This connection" for an owner connection without a session).

**Ticket starts are charged (all paths):** `daemon.TicketStartCharge` is the one path of a session's ticket start. It covers the
`ticket_move` frame (`agenthq ticket move|start`, the CLI's MCP `ticket_start`), `api.tickets.start` and `api.spawns.startTicket`. A
move by a session the daemon holds that would run a worker (a todo ticket, or an in-progress one whose worker cannot carry it) is
checked against the depth limit and the pod cap. The board's move runs under the guard's lock, and the worker joins the starter's pod
with the starter recorded as its `spawnerSessionId`. A UI / person move, a queue drain or the repair sweep is not charged.

**Fixed on the way:** `api.tickets.start` (BOARD-API-1) started a worker with no guard at all, so a plugin could start tickets past
every limit. It now goes through the same charge.

**Release rule (new, so charging cannot stall the board):** a session that is not live and whose ticket left In Progress (done,
review, todo, deleted), or whose ticket now names another worker, no longer holds a pod or concurrency slot. Without this rule a pm
would fill its pod with finished, idle workers after four tickets.

**Deviations (deliberate):** (0) A killed session revived to rest stays released (BUG-KILL-IDLE-1): the host remembers, in memory, the sessions it cleared a kill for (`LocalSpawns.released`), since a new run clears `killOutcome`; a restart of the daemon forgets it. The `mcp serve` startup line no longer names the kill scope. (1) the client's `--max-depth` / `--max-spawns` used to REPLACE the daemon setting. They now only narrow
(a larger value is ignored). (2) `--allow-kill-any` is moot. An owner connection without a session may kill any session, as before.
A session-bearing connection is held to the rule whatever the flag says. (3) A pm's own project comes from the daemon's record, not
the MCP process's `AGENTHQ_PROJECT_ID`. (4) After a restart a session keeps its depth (before: every session was a root again).
(5) A session's `agenthq ticket move <id> in_progress` from depth >= the limit is now refused by the daemon (before: only the MCP
tool refused it).

Expensive to reverse: persisting depth through `spawnerSessionId`. Records written from now on carry the starter as the worker's
spawner, and the kill rule and the read model already read that field.

Tests run: `LocalSpawnsTest` (depth, narrowing, concurrency, release, ticket start outcomes, kill scope, fail closed, owner per-client,
policy gate, grant, pod cap), `SpawnGuardLineageTest`, `SpawnGuardLimitSettingTest`, `SpawnCascadeDaemonTest`, `TicketStartDaemonTest`,
`McpOrchestrationE2eTest`, `AgentTicketStartE2eTest`, `LocalSessionReadsTest`, `LocalBoardServicesTest`. Not run: the full suite,
wasm/Android, a live agent session against an installed daemon.

**Left for RUNTIME-LOAD-7 (the tools move), and its seam (done: section 13):**

| step | what |
|---|---|
| wire | `agenthq mcp serve` sends its narrowing in the `plugin_tool_call` client object (`maxDepth`, `maxSpawns`, `depth`, `clientKey`), surfaced as `ToolCall.clientNarrowing` (DaemonApi 1.8.0 or folded into 1.7.0 before release); contract bump |
| Infantry_Board | `agenthq_ticket_start` over `api.spawns.startTicket` (answers as `McpWriteTools.startAnswer`) |
| Infantry_Agents | `agenthq_spawn` (`ticketId` -> `startTicket`, with the `disagreement` checks), `agenthq_kill` (`mayKill`, then the `kill` frame), `agenthq_prompt` / `agenthq_session_restart` / `agenthq_session_remove` (`target`, then their frames on the caller's connection) |
| delete | `McpGuardrails`, `McpLineage`, those tools in `cli/mcp`; `BuiltinToolsListGuardTest` ratchet 12 -> 6 |
| tests | golden descriptors (`core-mcp-tools-before-f5.json`), `McpOrchestrationE2eTest`, `AgentTicketStartE2eTest`, `McpPluginToolTimeoutTest`, `BlankShellE2ETest` |

## 13. RUNTIME-LOAD-7 (ticket bb12002a): the spawn tools move to plugins over `api.spawns`

Closes F5. `agenthq mcp serve` now serves six tools itself (`agenthq_message`, `agenthq_inbox`, `agenthq_read`, `agenthq_pipeline`,
`agenthq_pipeline_record`, `agenthq_watch_file`); `BuiltinToolsListGuardTest`'s ratchet is 6.

| tool | plugin | host call | action (gate) |
|---|---|---|---|
| `agenthq_ticket_start` | Infantry_Board (`TicketStartTool`) | `spawns.startTicket` | `board.ticket.move` |
| `agenthq_spawn` | Infantry_Agents (`SpawnTools.Spawn`) | `spawns.spawn`; with `ticketId`: `tickets.get` + the `disagreement` checks + `spawns.startTicket` | `agents.spawn` |
| `agenthq_prompt` | Infantry_Agents | `spawns.target`, then the `prompt` frame | `chat.prompt` |
| `agenthq_kill` | Infantry_Agents | `spawns.mayKill`, then the `kill` frame | `agents.kill` |
| `agenthq_session_restart` | Infantry_Agents | `spawns.target`, then `session_restart` | `agents.restart` |
| `agenthq_session_remove` | Infantry_Agents | `spawns.target` (a miss = SESSION-GHOST-1 no-op), `mayKill` for force, then `kill` / `session_delete` | `agents.delete` |

**Wire (DaemonApi 1.9.0, additive, CONTRACT.md addendum):** the `plugin_tool_call` `client` object carries `maxDepth`, `maxSpawns`, `depth`
(from `--max-depth` / `--max-spawns` / `.agenthq/mcp.json` / `--mcp-depth` / `AGENTHQ_MCP_DEPTH`) and `clientKey` (a random id per `mcp serve`
process). `DaemonPluginHost.callTool` parses them into `ToolCall.clientNarrowing`; tools pass it to `api.spawns`, which only narrows. The caller
is `call.caller.copy(activeSession = call.clientSessionId ?: ...)`. Both modules need `apiAtLeast` 1.9.0.

**Answers:** same names, descriptors (byte-identical to `golden/core-mcp-tools-before-f5.json`) and answer JSON. The shared texts of a ticket start
are `daemon.api.spawns.SpawnText` (Board's tool and `agenthq_spawn ticketId` print through it). `SessionView` gained `contextLimit`,
`contextLimitSupport`, `killOutcome` (additive) for the spawn and kill answers. Each tool declares its own `timeoutMs` (spawn / start 120 s, prompt /
kill / restart 45 s, remove 90 s).

**Fence for frames that answer nothing on success:** `kill`, a named `prompt`, `session_restart` and `session_delete` answer nothing when they succeed
(a refusal is an `error`), so a plugin request would time out. For a request id `plugin:<uuid>` the daemon now answers with the `sessions` list after
the write (`pluginSessionsAck`, as `role_save` already answers a plugin). The connection handles frames in order, so it is the same fence the old
trailing `sessions` request drew. Other clients see no change.

**Deleted:** `McpGuardrails`, `McpLineage` (and `--lineage-dir`, `AGENTHQ_MCP_LINEAGE_DIR`), `McpTicketTools`, and the six tools in `McpWriteTools`
(it keeps `agenthq_message` until F6). `--max-depth` / `--max-spawns` / `--mcp-depth` are `McpNarrowing` values. The CLI's `session rm|kill` keep the
kill rule in `cli/mcp/KillScope`.

**Deviations (deliberate):** (1) `--allow-kill-any` and `allowKillAny` in `.agenthq/mcp.json` are accepted and ignored. (2) A session spawned by an
owner connection (no session id) is a root, depth 0 (before: the lineage file recorded depth 1 for it). Its own `mcp serve` therefore may spawn one
level deeper than it used to. (3) `--max-depth` / `--max-spawns` larger than the daemon's setting are ignored (section 12). (4) A queued ticket start
answers `queuedFor` with the lane only (`TicketInfo` has no `queuedFor`). (5) The spawn answer's `status` is the session state as the host lists it
after the task was sent.

Tests run: `McpServeTest` (incl. `movedSpawnToolsComeFromTheAgentsPluginUnchanged`, the golden check of all six), `McpWriteToolsTest` (the guardrail
tests through the plugins: depth, narrowing never widens, concurrency, kill scope), `McpOrchestrationE2eTest`, `AgentTicketStartE2eTest`,
`McpPluginToolTimeoutTest`, `BlankShellE2ETest`, `BuiltinToolsListGuardTest`, `LocalBoardServicesTest`, `LocalSpawnsTest`, `McpTicketToolsTest`,
`McpServeConfigTest`. Not run: the full suite, wasm/Android, a live agent session against an installed daemon.

## 14. RUNTIME-LOAD-8 (ticket ec7b9348): mail and pipeline move to plugins; `agenthq_watch_file` is the one tool left

Split, as the ticket allows: mail and pipeline need no new API (each tool is a frame the CLI already sends, on the caller's own connection
through `ToolCall.request`), so they move now. `agenthq_watch_file` needs a seam that does not exist yet and stays (below).

| tool | plugin | frame | action (gate) |
|---|---|---|---|
| `agenthq_inbox` | Infantry_Chat (`MailTools.InboxTool`) | `mail_inbox` | `mail.read` |
| `agenthq_read` | Infantry_Chat | `mail_inbox`, then `mail_read` | `mail.read` |
| `agenthq_message` | Infantry_Chat | `mail_send` | `mail.send` |
| `agenthq_pipeline` | Infantry_Git (`PipelineTools.ShowTool`) | `pipeline_show` | `pipeline.read` |
| `agenthq_pipeline_record` | Infantry_Git | `pipeline_record` or (with `check`) `pipeline_check` | `pipeline.save` |

Why these homes: Infantry_Chat owns the COMMS surface in the UI; Infantry_Git already owns the pipeline stage configuration (`pipeline.save`).
Both got a `daemon/` project and a `daemon` block in `module.json` (`apiAtLeast` 1.1.0, for `ToolCall.clientSessionId`); packaging picks them up
through `gradle/daemon-halves.gradle.kts` unchanged. No DaemonApi change, no wire change, no contract bump.

**Behaviour:** descriptors byte-identical to `golden/core-mcp-tools-before-f5.json` (`McpServeTest.movedMailAndPipelineToolsComeFromTheirPluginsUnchanged`);
the answers keep their keys and refusal texts. The plugin reads the wire `Message` as JSON (it cannot see core classes) and rebuilds `read` /
`delivered` / `failed` from the stored fields, as `Message` computes them. Identity is the MCP process's session (`ToolCall.clientSessionId`), else the
human handle `user`; the project of the pipeline tools is the argument, else `ToolCall.clientProjectId`. Core classes `McpMailTools`,
`McpPipelineTools` and `McpWriteTools` (which only held `agenthq_message` since RUNTIME-LOAD-7) are deleted. Ratchet `CEILING` 6 -> 1.

**Plugin absent:** the tools are not listed and a call to one is the usual error; the core, the CLI (`agenthq inbox|send|pipeline`) and every other plugin
are unaffected (`McpServeTest.mailAndPipelineToolsAreGoneWithoutTheirPlugins`). Each behaviour keeps its own gate: a policy preset that turns off
`mail.send` or `pipeline.save` refuses the tool as it refused the frame.

**Deviations (deliberate):** (1) the mail send no longer uses the `mail_outbox` fence: the plugin waits for the direct reply to its own request id,
which is the `mail` / `mail_refused` the daemon returns to `mail_send`. (2) A daemon wait that times out words itself `<frame> got no answer in N ms`
(as for every plugin tool). (3) The pipeline's default project is the client's (`--project` / `mcp.json` / `AGENTHQ_PROJECT_ID`), as for the board tools.

**Left: `agenthq_watch_file`.** The watcher must run where the watched file is: `agenthq watch-file` starts a detached wrapper process that follows
the file and registers a bg job. A daemon plugin runs in the daemon, which for a remote daemon is another machine, so moving the tool as a daemon tool
would silently watch the wrong file system (or none). The honest seam is a CLIENT-RUN tool: a plugin declares the tool and the MCP client executes
it locally and reports events to the daemon (the `localFiles` idea of section 10 applied to an action, not to bytes). That is a DaemonApi + wire
addition (a tool kind `client`, a way to report the job to the daemon) and its own ticket; no stopgap. Ratchet entry kept with that reason.

Tests run: the plugin unit tests (`MailToolsTest`, `PipelineToolsTest`), `BuiltinToolsListGuardTest`, `McpServeTest`, `McpMailToolsE2eTest`,
`McpWriteToolsTest`, `McpOrchestrationE2eTest`, `McpTicketToolsTest`, `TicketContextComposerTest`. Not run: the full suite, wasm/Android, a live
agent session against an installed daemon, `BlankShellE2ETest`.
