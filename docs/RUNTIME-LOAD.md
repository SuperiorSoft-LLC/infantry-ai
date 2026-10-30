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
| `agenthq_harness_install` | `cli/mcp/McpTools.kt` | no | S, blocked: a plugin tool call is cut off at `limits.plugins.toolTimeoutMs`, and the MCP client waits only 1.25x the global value; an npm install runs minutes. Needs per-tool timeouts first (below, F1) |
| 32 other built-in MCP tools (roles 5, tickets 9, mail 3, pipeline 2, ask, project config 2, harnesses/sessions/spawn 9, watch_file) | `cli/mcp/Mcp*Tools.kt`, ratchet `BuiltinToolsListGuardTest` | no | M each family (DAEMON-PLUGINS.md section 6) |
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
| F1 | Per-tool timeout in the plugin tool contract (`McpPluginTools.answerWaitMs` reads the tool's declared `toolTimeoutMs`); then move `agenthq_harness_install` | S | Unblocks the last two harness tools; removes the edge above |
| F2 | `agenthq_list_harnesses` + `agenthq_list_sessions` / `agenthq_get_session` / `agenthq_pod_state` into the Infantry_Agents half (read-only frames) | S-M | Same pattern, no new API |
| F3 | Roles tools (5) into an Infantry_Roles daemon half (agent-caller persona rule moves with it) | M | Next family with a ready owner module |
| F4 | `agenthq_ask` into Infantry_Attention; project config tools into Infantry_Settings | S each | One frame each |
| F5 | Tickets/epic/board/progress tools into Infantry_Board (`ProjectScope` as an API helper) | M | Biggest family, riskiest scope rules |
| F6 | Mail, pipeline, `watch_file` (needs a DaemonApi surface for watch hooks) | M | Needs new API surface |
| F7 | A folder source for `HarnessPluginSource` + a harness API module; move `pi`, then `claude`, then `opencode` | L | Contract design; `pi` first, fewest daemon internals |
| F8 | Core Config `ConfigEntry` fallback removed (G13) | S-M | Independent of the daemon |
| F9 | The 20 UI modules, one per project (B1-B6 of the audit), kit exported to the filtered loader (contract bump) | L | UI-first per D11 once F1-F6 settle |
| F10 | Daemon feature services as plugins (D11: later) | L | Parked by the owner |

## 5. Not run

The full `agentHq:test` suite, `agentHqUi` and wasm/Android builds, `BlankShellE2ETest`, any live harness install. Run: the five test
classes named in section 3 plus `*DaemonPlugin*` and `*HarnessInstall*`; and `installDist` with both measurement runs above.
