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
| F5 | Tickets/epic/board/progress tools into Infantry_Board (`ProjectScope` as an API helper); `agenthq_ticket_get` DONE in BOARD-API-2, rest in section 9 | M | Biggest family, riskiest scope rules |
| F6 | Mail, pipeline, `watch_file` (needs a DaemonApi surface for watch hooks) | M | Needs new API surface |
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
