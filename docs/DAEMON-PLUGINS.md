# DAEMON-PLUGINS-1: daemon-side plugins (ticket fb588e94)

Owner principle (2026-10-29): core is the abstraction layer. The daemon plugin **mechanism** (loader, `DaemonApi`, hooks, tool
registry, routes, limits, tiers and grants) is core; **concrete** behaviours and tools are plugins built on it, and once a
concrete thing is migrated it is deleted from core. Daemon tools are plugins too, so anyone can write their own.

Before this ticket the daemon loaded no plugin code: `DaemonPlugins.kt` resolved the modules (same folders, modules.json and
trust rules as the desktop) only to serve `GET /plugins` and compile the daemon config manifest (CONFIG-8). See
PLUGIN-OVERRIDES.md section 8 and GAP-AUDIT.md rows 1, 4, 6, 8, 9 and gaps 5, 6.

## 1. Layout and manifest

A daemon half lives next to the module's UI half, in the same `plugins/<folder>/<Vendor_Module>/` folder, as its own Gradle
project in `daemon/` (project path `:daemon-plugin-<folder>-<Module>`, so the UI builds that iterate `:plugins:official:*`
never see it). It compiles against `superiorSoftLibrary/agentHqDaemonApi` only (`compileOnly`) and builds
`daemon/build/libs/<Module>-daemon.jar`, which carries a copy of the module's `module.json` (the signed one, as for UI jars).

module.json grows **optional** keys under the existing `daemon` object (NS3/CONFIG-8 already own `needs`, `wants`, `config`);
older hosts ignore them, a module without them loads no daemon code (default: nothing loaded):

```json
"daemon": {
  "entry": "com.acme.hello.daemon.HelloDaemon",      // a class implementing DaemonPlugin, with a no-arg constructor
  "jar": "daemon/build/libs/Acme_Hello-daemon.jar",  // inside the module folder
  "apiAtLeast": "1.0.0",                             // DaemonApi version gate (contractAtLeast)
  "tools":  [ { "name": "acme_hello", "action": "acme.hello.read", "kind": "read", "description": "..." } ],
  "frames": [ { "type": "acme_hello_ping", "action": "acme.hello.read", "kind": "read" } ],
  "hooks":  [ "frameGate", "httpGate", "admission", "mailGate", "ready", "spawnGate", "turns", "sessions", "policyProvider" ],
  "routes": [ "GET /demo/visitor-token" ],           // top-level routes, official/core only; plugin routes go under /plugins/<id>/
  "publicRoutes": [ "GET /demo/visitor-token" ],     // answered without the bearer token (official/core only)
  "env": [ "AGENTHQ_DEMO_NOTICE" ],                  // env vars the plugin may read through DaemonApi.env
  "secrets": [ "acme.apiKey" ],                      // keystore secrets the plugin may read (granted, never pushed)
  "limits": { "callTimeoutMs": 2000, "cpuMsPerCall": 2000, "routeRequestsPerMinute": 600, "memoryMbHint": 64 }
}
```

Everything a plugin registers at runtime must be declared here: an undeclared tool, frame, hook, route, env var or secret is
refused (logged), so module.json is the complete, reviewable list of what a daemon plugin touches.

## 2. Trust: tiers and grants (same model as the UI loader)

The daemon host runs after `ModuleResolver` (the same folders, modules.json, enable list, safe mode and dependency rules):
only a **LOADED** module's daemon half is considered.

| tier | code loads when | may use |
|---|---|---|
| core | always | everything declared |
| official | the daemon jar passes `OfficialTrust` (signed by the pinned Infantry key, signed module.json equal to the disk one), or `-Dagenthq.plugins.devTrust=true` (dev runs), or it is in the install's **bundled** root (owner question 97479106, recommended answer: the install's own read-only folder is as trusted as `lib/`) | everything declared |
| community | enabled in modules.json **and** granted `daemon.code` in its `allow` list | only what is granted: `daemon.tool`, `daemon.frame`, `daemon.route`, `daemon.hook.<kind>`, `daemon.env:<NAME>`, `daemon.secret:<name>`; never top-level or public routes, never owner-only frames, never the `admission` hook, tool names only with its own vendor prefix (`acme_...`) |

A plugin's code sees only a declared surface: its jar is put on its **own** `URLClassLoader` whose parent
(`DaemonApiClassLoader`) exposes only `java.*`/`javax.*`, `kotlin.*`, `kotlinx.serialization.*`, the daemon API
(`com.superiorsoftllc.agenthq.daemon.api.*`) and the plugin SDK data model (`com.superiorsoftllc.agenthq.plugin.api.*`:
`PolicyPreset`, `SeatsSpec`, ...). Every other daemon class (`SessionManager`, `AccessPolicy`, the stores) is
`ClassNotFoundException` to a plugin. Removing the module folder removes the module, its tools, frames, routes and hooks at
the next start (`GET /plugins` and `plugin_tools` stop listing it).

## 3. DaemonApi (version 1.0.0, `DaemonApi.VERSION`)

`interface DaemonPlugin { fun start(api: DaemonApi); fun stop() {} }`. `start` runs once at daemon start, after the stores and
sessions exist and before the server binds. What `DaemonApi` gives:

* identity and housekeeping: `moduleId`, `tier`, `apiVersion`, `log(line)`, `stateDir` (`<state dir>/plugin-data/<id>/`, the
  plugin's own folder), `config(key)` (the module's `daemon.config` values compiled by CONFIG-8), `env(name)` and
  `secret(name)` (declared + granted names only; anything else is null and logged), `schedule(name, periodMs, job)` (a job
  runs under the same limits; at least 1 s period).
* data contract (abstract, no daemon class crosses): `projects` (`list`, `create`), `sessions` (`list`, `get`, `spawn`,
  `resume(id, prompt)` composed as a visitor's first turn, `restart`), `policy` (`active()` as a `PolicyPreset`,
  `onChange`, `actionOf(frame)` = the catalog's action id/kind/scope, `refusal(frame)` = the active preset's rules,
  `refusalText(frame)`, `reportSeating(error)`), `events.on(type) { frame -> }` (frames the daemon broadcasts).
* extension: `tools.register(DaemonTool)`, `frames.handle(type) { frame, caller -> reply }`, `routes.handle(method, path)`
  (relative paths answer under `/plugins/<module id>/<path>`), and the hooks of section 4.

A **tool** is a named callable with a JSON schema (`DaemonTool`: `name`, `description`, `inputSchema`, `annotations`,
`call(ToolCall) -> ToolResult`). It is listed to clients (`plugin_tools` frame) and to agents (`agenthq mcp serve` appends the
daemon's plugin tools to `tools/list` and routes an unknown `tools/call` to `plugin_tool_call`), and it has its **own policy
action id** (declared in module.json, merged into `ActionCatalog`), so POLICY-1 gates it like any frame: a `plugin_tool_call`
is the action of the tool it names. A tool reaches the daemon through `ToolCall.request(type, data)`: the frame is
dispatched **on the caller's own connection**, through every gate (FrameGate, policy, tier, the frame-gate hooks), so a tool
can never do more than the client that called it.

## 4. Hooks

Each hook is declared in module.json and runs under the plugin's limits (timeout, CPU, exception isolation). A plugin that
throws or overruns is **disabled** (logged once, `GET /plugins` reports it `failed` with the reason) and the daemon keeps
running. What a disabled plugin's hook then does is the hook's fail mode:

| hook | when | answer | fail mode |
|---|---|---|---|
| `frameGate` | every client frame, after FrameGate and the policy | null = pass, else the refusal text | **closed**: a failed gate refuses every non-owner frame (owner keeps `policy_set`/`policy_get` and the rest, to switch away) |
| `httpGate` | every HTTP request before routing | null = pass, else 403 text | **closed** (as above, owner-token requests excepted is not possible over HTTP: closed for all but `/health`) |
| `admission` | the access check | extra guest bearer tokens admitted now (official/core only; checked like the visitor token: >= 32 chars, not the owner/agent token) | **open**: a failed admission admits no guest |
| `mailGate` | `mail_send` frame and `POST /mail/send` | null = pass, else the refusal | **closed** |
| `spawnGate` | every new session (frames, mail, triggers, ticket starts) | null = pass, else the refusal | **closed** |
| `ready` | each `ready` and `policy` frame | extra `ready` keys + the pinned notice | **open**: no keys |
| `turns` | a client turn accepted (`prompt`/`resume`/`mail_send` reached an agent) | observer | **open** |
| `sessions` | session created / state changed / closed | observer | **open** |
| `policyProvider` | `PolicyRuntime.presets()` | presets (POLICY-1's `PolicyProvider`, one method, unchanged) | **open**: no presets |

The policy provider interface is POLICY-1's (`policy/PolicyProvider.kt`, unchanged): a plugin's provider is wrapped and appended
to the runtime's providers, so the switch, pinning and fail-closed rules of docs/POLICY.md apply to plugin presets as is.

## 5. Safety and limits

* per call: `callTimeoutMs` (default 2000; a call runs on the plugin's own worker and is abandoned past it) and `cpuMsPerCall`
  (thread CPU time, default = the timeout); either breach disables the plugin;
* routes: `routeRequestsPerMinute` per plugin (default 600, 429 past it);
* `memoryMbHint` is reported, not enforced (one JVM cannot cap a class loader's heap; documented gap);
* secrets: never passed unless declared in `daemon.secrets` and (community) granted `daemon.secret:<name>`; env likewise;
* versioning: `apiAtLeast` above `DaemonApi.VERSION` refuses the module (logged);
* unsigned (community) plugins cannot add owner-only frames, top-level or public routes, or admission tokens.

## 6. Built-in tools: audit and migration plan

The daemon's built-in agent tools are the `agenthq_*` MCP tools of `agenthq mcp serve` (`cli/mcp/*`): CLI-side wrappers over
daemon frames. The frames are the **abstract daemon data contract** (core); a tool is a concrete composition over it (plugin).

| tool(s) | frames | class | plan |
|---|---|---|---|
| `agenthq_ticket_files`, `agenthq_ticket_related_files` | `ticket_files` | concrete | **MIGRATED** (this ticket): `plugins/official/Infantry_Board/daemon` (`board.files.read`), deleted from core |
| `agenthq_usage` | `usage_get` | concrete | next: needs `UsageText.render` moved into the plugin (the CLI `agenthq usage` shares it) |
| `agenthq_processes`, `agenthq_processes_reap` | `processes`, `processes_reap` | concrete | Infantry_Processes daemon half; `ProcessesCli.render` moves with it |
| `agenthq_role_*` | `role_*` | concrete | Infantry_Roles daemon half (the agent-caller persona/description rule moves with it) |
| `agenthq_ticket_create/update/get/move/delete/start`, `agenthq_epic_base`, `agenthq_board` | `ticket_*`, `epic_*`, `sessions` | concrete | Infantry_Board daemon half; needs the ProjectScope guard as a shared helper in the API |
| `agenthq_inbox`, `agenthq_read`, `agenthq_message` | `mail_*` | concrete | Infantry_Chat (or a Mail) daemon half |
| `agenthq_pipeline`, `agenthq_pipeline_record` | `pipeline_*` | concrete | a Pipeline official plugin |
| `agenthq_ask` | `question_ask` | concrete | Infantry_Attention daemon half |
| `agenthq_progress` | `progress` | concrete | stays with the progress bar owner (Infantry_Board) |
| `agenthq_project_config_get/set` | `project_*` | concrete | Infantry_Settings daemon half |
| `agenthq_list_harnesses`, `agenthq_harness_status`, `agenthq_harness_install` | `providers`, `harness_install*` | concrete | Infantry_Agents daemon half |
| `agenthq_list_sessions`, `agenthq_get_session`, `agenthq_pod_state`, `agenthq_spawn`, `agenthq_prompt`, `agenthq_kill`, `agenthq_session_restart`, `agenthq_session_remove` | `sessions`, `replay_request`, `pod_*`, `new`, `prompt`, `kill`, ... | concrete over abstract session primitives | Infantry_Agents daemon half; McpGuardrails (depth/concurrency/kill scope) moves with them |
| the frames themselves, `SessionManager`, transport (`/ws`, `/mail/*`, `/hooks/*`, `/health`), `AccessPolicy`, `FrameGate`, `PolicyRuntime`, `ActionCatalog` | - | **abstract core** | stay |
| triggers (`TriggerService`), harness install (`HarnessInstaller`), watch hooks | - | concrete daemon behaviour, not tools | later DAEMON-PLUGINS tickets: they need `DaemonApi` scheduling and harness surfaces first |

Why only one tool family moves now: each move must keep the tool working where agents run today. The ticket-files family
needs nothing but `ToolCall.request`; the others need helpers (`ProjectScope`, `McpGuardrails`, renderers shared with the
CLI) moved into the API or the plugin first. Those are named in the table, not hacked.

## 7. The demo lock is a plugin now

DEMO-MERGE-1 left the demo lock (`DemoLock`, `DemoTurnLimit`, the visitor token route, `withDemoToken`,
`SessionManager.demoLocked`, `FrameGate.DEMO_LOCK_ALLOWED`, RunManager/Keystore "under the demo lock" texts) in core. It is
now the daemon half of `plugins/demo/Demo_Lock` (`:daemon-plugin-demo-Demo_Lock`), built only on the API:

| former core code | now |
|---|---|
| `DemoLock` seed + warm-up | `DemoLock.seed` / `warmUp` over `api.projects` / `api.sessions`, on start and on `api.policy.onChange` |
| `DemoLock.frameRefusal` (seat scoping, mail between seats, per-project reads) | the plugin's `frameGate` hook (after core's own policy refusal) |
| `DemoLock.httpRefusal` | the plugin's `httpGate` hook |
| `DemoLock.mailRefusal` (`POST /mail/send`) | the plugin's `mailGate` hook |
| `SessionManager.demoLocked` | the plugin's `spawnGate` hook (core: the generic `SessionManager.spawnGate`) |
| visitor token (`AccessPolicy.withDemoTokens`, `GET /demo/visitor-token`) | the plugin's `admission` hook (core: generic guest tokens) + its declared public top-level route |
| `DemoTurnLimit` (per-IP turns) | the plugin's `frameGate` (refusal) + `turns` hook (record) |
| `readyFields` / `notice` (`AGENTHQ_DEMO_NOTICE`) | the plugin's `ready` hook |
| `AGENTHQ_DEMO_*` env reads | declared in the plugin's `daemon.env`, read through `api.env` |
| RunManager / Keystore "locked" | core: the active preset's generic `restricted` flag, neutral text |
| `FrameGate.DEMO_LOCK_ALLOWED` (env set, no preset installed) | gone; core's generic rule: a preset with `seats` that no loaded plugin seats is **fail closed** (every frame refused but the switch), and `AGENTHQ_POLICY=<id>` pinned to a missing preset denies everything (POLICY-1) |

`NoDemoInDaemonCoreGuardTest` fails when daemon core source (`superiorSoftLibrary/agentHq/src/main`) names a demo symbol.

## 8. How to write your own daemon tool

Copy `plugins/_template` (its `daemon/` folder is the daemon half), rename, and follow `plugins/_template/daemon/README.md`.
The reference plugin `plugins/examples/Acme_DaemonHello` has one tool, one route and one hook. In short:

1. `module.json`: add `daemon.entry`, `daemon.jar`, and declare your tool (`name`, `action`, `kind`, `description`).
2. `daemon/build.gradle.kts`: `compileOnly(project(":superiorSoftLibrary:agentHqDaemonApi"))`, jar named `<Module>-daemon.jar`.
3. A class implementing `DaemonPlugin` registers a `DaemonTool` in `start(api)`; inside `call`, use `call.request(frame, data)`
   to read through the daemon's frames as the caller.
4. Drop the folder under the plugins root; a community module also needs `"enabled": true` and `"allow": ["daemon.code",
   "daemon.tool"]` in modules.json (plus `daemon.route` for a relative route, `daemon.hook.<kind>` per hook, `daemon.env:<NAME>`
   per env var). The tool appears in `agenthq mcp serve`'s `tools/list` and in the `plugin_tools` frame.

`DaemonPluginMechanismTest` runs Acme_DaemonHello from its built jar as a community module: loaded, listed, called, routed,
its hook run; a throwing hook disables only it; refused without grants; gone when its folder is removed.
verifyBase: OK. 'feature-agenthq-harness4' (HEAD adc32204) contains expected base 9534c95ecbe516c7af587247400300a067c12cd0.

`plugins/examples/Acme_PolicyHook` is the second reference: a tool that asks the policy for a frame's action and refusal
(`api.policy.actionOf` / `refusal`) and a `frameGate` hook that counts frames by action kind (`PolicyHookExampleTest` runs
it from its built jar). Its `module.json` declares the hook; a community module needs `daemon.hook.frameGate` granted.

## 9. Where the daemon halves come from, and what changed

* **Installs and the docker image** carry the official daemon halves under `<install>/modules/official/<Module>/`
  (`installDist` / `dockerContext` copy each module.json, `policies/` and built `<Module>-daemon.jar`; the image adds the demo
  halves). The daemon sets `agenthq.plugins.bundled` to that folder when unset, so they load as **bundled = trusted** (owner
  answer to question 97479106) without a signature. Without them the ticket-files tools are not listed and a seated preset is
  fail closed: nothing is lost silently.
* **The bundled root, one place per install layout** (PACKAGE-1; `installModules()` in DaemonMain, `AGENTHQ_BUNDLED_MODULES`
  or `-Dagenthq.plugins.bundled` win when set):

  | layout | jars | bundled root | set by |
  |---|---|---|---|
  | `installDist`, headless archive | `<install>/lib/` | `<install>/modules` | the daemon (the `lib/` parent) |
  | docker image | `/opt/agenthq/lib/` | `/opt/agenthq/modules` | the daemon (the `lib/` parent) |
  | desktop app image (jpackage: createDistributable, MSI/EXE) | `<app>/app/` | `<app>/app/resources/modules` | the launcher (`-Dagenthq.plugins.bundled=$APPDIR/resources/modules`); the daemon falls back to `<compose.application.resources.dir>/modules` |

  The desktop image carries the daemon halves in the same module folder as the UI jar: `stageOfficialModules` copies each
  official module's module.json `daemon.jar` and `policies/` next to its signed UI jar and signs the daemon jar with the same
  key (`-Pihq.keystore` / `-Pihq.devSign`); `verifyOfficialModulesPackaged` checks the signature. Demo halves
  (`plugins/demo/*/daemon`) never enter the desktop image (it stages `plugins/official` only); the docker context keeps them.
* **The guard**: what a daemon half is lives in one place, `gradle/daemon-halves.gradle.kts` (a module.json with a `daemon`
  block; its `daemon.jar`). `stageOfficialModules`, `prepareDistributableResources` (what every desktop package task packs),
  `installDist` and `dockerContext` fail the build when a staged module declares a daemon jar that is not there, or a module with
  a half is not staged; `verifyDaemonHalvesGuard` (in agentHq `check`) tests that check. At run time a declared half whose jar
  is missing is logged at startup and listed in `GET /plugins` as `refused: jar missing: daemon.jar <path> is not in <folder> ...`.
* **Dev runs** scan the repo's `plugins/` (modules.json folders `core`, `official`, `community`), with `devTrust` for official
  jars. `plugins/demo` is not a modules.json folder, so a dev demo needs the bundled root (or a copy into `official/`).
* **Gradle projects** are flat, `:daemon-plugin-<folder>-<Module>` (Gradle 9 refuses directory-less parents), plus
  `:daemon-plugin-_template`; the agentHq test task depends on the jars the daemon tests load.
* **Behaviour changes** (contract 1.144.0, CONTRACT.md): `AGENTHQ_DEMO_LOCK` alone no longer locks anything; the demo seed
  pins `AGENTHQ_POLICY=demo`, and a missing preset fails closed (POLICY-1). A preset is `locked` (keystore, RunManager) when
  it is `restricted` **or** has `seats`. The per-IP turn file is now `<state>/plugin-data/Demo_Lock/demo-turns.tsv` (a one-time
  reset of the counts). A seated preset without a `projectDir` uses the plugin's own state folder.
* **Gaps**: `memoryMbHint` is not enforced (one JVM); the SDK's `PluginFolders.demoLock()` / the UI's demo-lock module rules
  (`DemoLockConfig`) are UI-side and not part of this ticket; the remaining built-in tools of section 6 stay in core until
  their helpers move.

What the daemon core is, the target list for the migration above, and the orchestrator/runner split: [DAEMON-CORE.md](DAEMON-CORE.md).
