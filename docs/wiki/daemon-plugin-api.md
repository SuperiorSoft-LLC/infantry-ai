[← Wiki index](index.md)

# Daemon plugin API

A daemon plugin is the part of a module that runs inside the Infantry AI daemon. It adds tools (for the CLI and for
agents over MCP), HTTP routes, client frames, hooks, scheduled jobs, settings and its own database tables. It is
optional. Most modules have only a UI half.

Source of the API: `superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/`
(`DaemonApi.kt` holds the core; the sub-packages hold the larger services). Host side: `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/`
(`DaemonPluginHost.kt`, `PluginGuard.kt`). The version is `DaemonApi.VERSION`, currently **1.36.0**.

A daemon half compiles against the module `:superiorSoftLibrary:infantryDaemonApi` with `compileOnly`. The daemon
loads the jar in its own class loader, which hides every daemon class except the API.

Tutorial: [Make a plugin](make-a-plugin.md). UI half: [UI plugin API](plugin-api.md).

## 1. The shape of a daemon plugin

```kotlin
interface DaemonPlugin {
    fun start(api: DaemonApi)   // once at daemon start, before the server binds
    fun stop() {}               // when the daemon closes, or the host disables the plugin
}
```

`module.json` `daemon.entry` names the class (public no-argument constructor) and `daemon.jar` the jar, relative to
the module folder. Everything the plugin registers must be declared in the `daemon` object of `module.json`. An
undeclared registration throws `IllegalStateException`; the host logs it and disables the plugin.

The smallest real example is `plugins/examples/Acme_DaemonHello`:

```kotlin
class HelloDaemon : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(Hello(api))
        api.routes.handle("GET", "hello") { HttpReply(body = """{"hello":"world"}""", contentType = "application/json") }
        api.hooks.ready(object : ReadyHook { override fun fields() = mapOf("acmeHello" to "hello") })
        api.log("started")
    }
}
```

## 2. `DaemonApi`: what you get

| Member | Meaning |
|---|---|
| `moduleId`, `tier`, `apiVersion` | your module name, `core`/`official`/`community`, and the API version |
| `stateDir: File` | your own folder, `<state dir>/plugin-data/<moduleId>/`, created on first use |
| `log(line)` | a line in the daemon log, prefixed with your module name |
| `config(key): String?` | a value of your `daemon.config` (fields with `"scope": "daemon"`), or null when unset |
| `env(name)` | an environment variable you listed in `daemon.env` (community: and was granted), else null |
| `secret(name)` | a keystore secret you listed in `daemon.secrets` (community: and was granted), else null |
| `scope: PluginScope` | (1.49.0) the half's lifetime: coroutines, resources and cleanups started through it end right after `stop()` on unload ([Plugin lifecycle](Plugin-lifecycle.md)) |
| `schedule(name, periodMs, job): AutoCloseable` | run `job` every `periodMs` (at least 1000) under the plugin's limits; close the handle to stop |
| `whenStarted(block)` | run `block` once the whole daemon is up (every daemon half started, every harness registered, before the server binds); `start` runs while later modules still load, so boot work that needs another plugin's service or a harness (spawning a session) goes here. A live-loaded plugin runs it at once. Since 1.39.0 |
| `displayStamp(epochMs)` | a time as people read it (`2026-11-03 20:41 -05:00`) in the daemon's `display.timeZone`. A daemon half's class loader links only the API, so it never names core classes such as `DisplayZone` (the first call would throw NoClassDefFoundError and the host would mark the plugin failed). Since 1.40.0 |
| `projects` | `list()`, `create(name, goal, directory)` |
| `sessions` | `list()`, `get(id)`, `spawn(provider, model, role, projectId)`, `resume(id, prompt)`, `restart(id)`, `status(id)`, `recentLines(id, max)`, `interrupt(id)`, `setAutomation(id, mark)`, `suppressNotice(name, sessionId, ms)` |
| `policy` | the active policy preset: `active()`, `onChange`, `actionOf(frame)`, `refusal(frame)`, `refusalText(type)`, `claimSeats()` |
| `events` | frame and named events (section 5) |
| `tools`, `frames`, `routes`, `hooks` | registration surfaces (sections 3 and 4) |
| `services` | typed service registry between plugins (section 6) |
| `storage` | your own database tables (section 7) |
| `settings` | read-only access to `limits.*`, `watch.*`, `processes.*` settings: `limit(key)`, `stored(key)`, `hint(key)` |
| `notifications` | (1.53.0) the core notification bus ([Notifications](Notifications.md)), the same `Notifications` type a UI plugin gets: `notify(Notification)` posts as your module (the hub stamps id, time and source, drops a repeat of the same id or `dedupeKey` within `notifications.dedupeWindowSec`, and pushes it to every client, whose bus routes and shows it), `onAction(listener)` hears the actions pressed on your notifications (also published as the named event `notification.action`), `dismiss(id)` stops a sticky one from being replayed. `present` is a no-op here: presenters live on the clients. A daemon without the bus gives `NoNotifications`. |

Further services, each with its own sub-package and each answering "unavailable" on a daemon that lacks it. Where
the source comment names a grant, a community module needs it in `modules.json` `allow`:

| Member | Package | Notes |
|---|---|---|
| `tickets`, `epics`, `board`, `boardRuntime` | `api.board` | tickets, epics and the board; `events.onTicket` needs `tickets.read` |
| `agents` | `api.agents` | the agent registry |
| `sessionReads` | `api.sessions` | read model; grants `sessions.read`, `sessions.transcript` |
| `spawns` | `api.spawns` | spawn, ticket start and kill with the guardrails; grant `sessions.spawn` |
| `sessionNotifier` | `api.sessions` | notify one session or every live session of a role; grant `sessions.notify` |
| `processes`, `launchEnv` | `api.processes` | OS process table, checked kill, environment for session launches; grant `daemon.processes` (never `kill`) |
| `meters` | `api.usage` | plan meters, read-only; grant `usage.read` |
| `files(projectId)` | `api.files` | the project's `.infantry/` folder; official, or grant `files` |
| `runs` | `api.runs` | run a process in a working root; grant `runs` |
| `triggerHost` | `api.triggers` | trigger store and run events |
| `runnerLinks` | `api.runnerLinks` | RUNNER-POOL-6 (1.45.0): the paired runners by name (`paired()`) and one link frame to one of them (`call(runner, type, data)`); core keeps the url, pin and credential. Official / core plugins only; a community plugin gets `NoRunnerLinks` |
| `updates` | `api.updates` | the update engine ([Updates](Updates.md)): components, sources, check / fetch / apply; declare source types in `daemon.updateSources`, grant `daemon.updateSource:<type>` |

The grants and the version each member was added in are in the comments of `DaemonApi.kt` (the history list inside
`companion object`).

## 3. Tools

```kotlin
interface DaemonTool {
    val name: String
    val description: String
    val inputSchema: JsonObject               // a JSON Schema object
    val annotations: JsonObject               // MCP annotations, e.g. readOnlyHint; default empty
    val timeoutMs: Long?                      // this tool's own timeout; default null
    val localFiles: List<String>              // argument keys that name files on the caller's machine
    fun call(call: ToolCall): ToolResult
}
interface ToolCall { val arguments: JsonObject; val caller: Caller; fun request(type, data, sessionId, timeoutMs): Frame; ... }
data class ToolResult(val text: String, val isError: Boolean = false)
```

- Register with `api.tools.register(tool)`. Declare it in `daemon.tools`:
  `{ "name": "acme_hello", "action": "acme.hello.read", "kind": "read", "description": "..." }`.
- `kind` is `read`, `write`, `destructive` or `spawn`. A policy preset can refuse an `action`; a refused call never
  reaches `call`.
- A core or official tool may use the `infantry_` prefix. A community tool name must start with its own vendor prefix
  (`acme_...`).
- `ToolCall.caller` says who called: `owner`, `agent`, `remoteAddress`. `clientSessionId` and `clientRole` are the
  MCP process's own claims: use them only to narrow what a call does, never to widen it.
- PROJECT-ISOLATION-1 (1.42.0): the host refuses, before `call` runs, a call from a project's session (or from a caller with
  `Caller.project` set, which a project hook's script carries) that names another project in `projectId` / `project`
  (or `'all'`), a ticket of another project in `ticketId`, or a session of another project in `sessionId` /
  `targetSessionId`. The owner (no session) and the owner's own roles (`owner`, `assistant`) cross projects. A tool that
  declares an `allowCrossProject` property in its input schema keeps that opt-in (cross-project mail); name your target
  arguments with these keys and the gate scopes your tool for free.
- `call.request("<frame>", data)` sends a frame on the caller's own connection, through every gate the caller's own
  frames pass, and returns the answer frame. Use it to read as the caller.
- `api.tools.catalog()` lists every live tool; `api.tools.invoke(name, arguments, as)` runs one exactly as an agent's
  call would (official and core only). The scripting plugin uses both.

```kotlin
override fun call(call: ToolCall): ToolResult {
    val who = (call.arguments["name"] as? JsonPrimitive)?.contentOrNull ?: "world"
    return ToolResult("Hello, $who! ${api.sessions.list().size} session(s).")
}
```

## 4. Routes, frames and hooks

### Routes (HTTP)

```kotlin
api.routes.handle("GET", "hello") { call: HttpCall -> HttpReply(200, """{"ok":true}""", "application/json") }
```

- A **relative** path (`hello`) answers at `/plugins/<moduleId>/hello`, behind the access policy. Any module may do
  this.
- An **absolute** path (`/hooks/{id}`) must be declared in `daemon.routes` and only core and official modules may use
  it. It may contain `{name}` segments; values arrive in `HttpCall.pathParams`. An exact route wins over a template.
- `daemon.publicRoutes` lists routes answered without the bearer token (core and official only).
- `HttpCall`: `method`, `path`, `query`, `headers`, `body`, `remoteAddress`, `pathParams`. `HttpReply`: `status`,
  `body`, `contentType`, `headers`.
- A route is rate limited to `limits.plugins.routeRequestsPerMinute` requests a minute (600; 0 = unlimited), then 429.

### Frames (client protocol)

```kotlin
api.frames.handle("my_frame") { frame: Frame, caller: Caller -> Frame("my_frame_reply", """{"x":1}""", requestId = frame.requestId) }
```

Declare `{ "type": "my_frame", "action": "my.read", "kind": "read" }` in `daemon.frames`. Return null for no direct
answer. `ownerOnly` frames are refused to a community module. `api.frames.table` registers a typed family (used by
`Infantry_Board`).

RUNNER-POOL-7 (1.46.0): `side` is `runner` (the default) or `orchestrator`. Set it on one tool or frame declaration, or
once for the whole daemon half as `daemon.side` (`"side": "orchestrator"` next to `apiAtLeast`). An `orchestrator` frame
or tool reads or writes orchestrator state (the board, knowledge, roles, scripts): when a worker runs on a remote runner
(docs/RUNNER-POOL.md 2.3), that runner forwards it over the link and the orchestrator answers it as that agent. A daemon
that is its own orchestrator never reads the tag.

### Hooks

Register with `api.hooks.<kind>(...)`; list the kinds in `daemon.hooks`; a community module needs `daemon.hook.<kind>`.

| Hook | Interface | On failure | Notes |
|---|---|---|---|
| `frameGate` | `FrameGateHook.refusal(frame, caller): String?` | fail closed | after the preset's own refusal; null = pass |
| `httpGate` | `HttpGateHook.refusal(method, path, webAsset)` | fail closed | every HTTP request before routing |
| `admission` | `AdmissionHook.guestTokens()` | fail open | official/core only; extra guest tokens, 32+ chars |
| `mailGate` | `MailGateHook.refusal(from, to, allowCrossProject)` | fail closed | each mail send |
| `incomingMail` | `IncomingMailHook.incoming(mail): MailVerdict?` | fail open | official/core only; runs before the recipient is woken; `MailVerdict(handled = true)` marks it read and wakes nobody; `MailVerdict(text = ...)` replaces the delivered text |
| `spawnGate` | `SpawnGateHook.refusal(role, projectId)` | fail closed | every new session |
| `ready` | `ReadyHook.fields()` / `notice()` | fail open | extra keys in the `ready` frame and a pinned notice |
| `turns` | `TurnHook.accepted(frame, caller)` | fail open | a client turn reached an agent |
| `sessions` | `SessionHook.changed(kind, session)` | fail open | kind `created`, `state`, `closed` |
| `policyProvider` | `PresetProviderHook.presets()` | fail open | contribute policy presets |
| `connected` | `ConnectedHook.frames(sessionId)` | fail open | official/core only; frames replayed to one client on connect |
| `status` | `StatusHook.fields()` | fail open | official/core only; extra fields in the health answer |
| `approvalRules` | `ApprovalRulesHook.match/remember` | fail open | official/core only; stored "allow always" rules |
| `orchestratorBinding(providerId, binding)` | `OrchestratorBinding` | | selected when the setting `orchestrator.provider` names it |
| `usageReadings(affectsPacing, readings)` | `() -> Boolean`, `(now) -> Map<String, List<LimitWindow>>?` | | USAGE-MOCK-1 (1.52.0): `usageReading` for several providers; `affectsPacing` false = pacing keeps judging the real readings while usage, limits and the limits frame carry yours |
| `usageReading(provider, windows)` | `(now) -> List<LimitWindow>?` | | supplies the plan-limit reading of `provider`; while any plugin offers one the daemon reads only those, in place of the provider endpoints, so usage, limits and the pushed limits frame all carry them (null = off now; DEMO-USAGE-HIDE-1: `Demo_Lock` offers fixed meters) |
| `runnerBinding(bindingId, binding)` | `RunnerBinding` | | builds the link for each paired runner (RUNNER-POOL-6: `Infantry_RemoteRunner` offers `remote`, one frame per operation over `api.runnerLinks`); a ticket placed on that runner (pin, rule or `runners.default`) starts through it (docs/RUNNER-POOL.md 3, 4) |
| `runnerLinkTransport(transportId, transport)` | `RunnerLinkTransport` | | official/core only (1.44.0); the runner link's transport: core keeps pairing, credentials, rekey, detach, audit and allow-lists. None loaded = no link served and `infantry runner pair/add/rotate/detach` say so (RUNNER-POOL-6a, docs/RUNNER-POOL.md 5) |

Fail closed means a throwing or slow hook refuses the request. Fail open means it reads as "no opinion" and the
request goes ahead. A mail is never dropped by a hook.

## 5. Events

```kotlin
api.events.on("*") { frame: Frame -> /* every frame the daemon broadcasts */ }                 // returns AutoCloseable
api.events.onTicket("my-consumer") { event -> }       // ordered, at-least-once; needs tickets.read
api.events.onSessionRemoved { sessionId -> }          // a session was killed or removed: drop your per-session data
api.events.broadcast("my_event", """{"x":1}""")        // push one frame to every client; official/core only
val e = api.events.publish("ticket.moved", """{"id":"t1"}""")                  // a named event
val handle = api.events.subscribe("ticket.*") { e: NamedEvent -> }            // exact name, "prefix.*", or "*"
```

A `NamedEvent` has `name` (dotted, lower case), `payload` (JSON text), `source` (plugin id, `session:<id>`, `cli` or
`daemon`), `id`, `causeId` (the root event's id), `depth` (hops from the root) and `at`. Delivery is off the
publishing thread, in publish order. The core carries `causeId` and `depth` but enforces no loop policy: a loop guard
is the plugin's job (the scripting plugin has one, see [Scripting](Scripting.md)).

### Job logs (JOBFEED-1, DaemonApi 1.50.0)

`Sessions.jobs(id)` lists the watched jobs (`infantry bg run`) of a session as `JobInfo` (running ones and the last few that ended, with
their exit code). `Sessions.jobLog(id, jobId, fromOffset, maxBytes)` is a bounded read of a job's log and answers a `JobLogChunk`: whole
lines only, `nextOffset` to pass to the next read, `reset` true when the log was cut or replaced. `limits.jobs.maxReadBytes` caps one read;
`limits.jobs.remembered` is how many ended jobs stay readable. Nothing runs between reads: a plugin pulls, the daemon keeps no stream.

### Capabilities (PLUGIN-BRIDGES-1, DaemonApi 1.48.0)

`api.capabilities` (`Capabilities`) answers this module's `runs: daemon` capabilities and calls any installed one through the
host's gates. The full story, with the client side and the Lua form, is on [Bridges](Bridges.md).

```kotlin
val handle = api.capabilities.handle("acme.lookup") { call: CapabilityCall -> lookup(call.args) }   // AutoCloseable
val r: CapabilityResult = api.capabilities.call("readaloud.speak", buildJsonObject { put("text", "hi") }, target = CapabilityTarget.ALL)
val all: List<CapabilityInfo> = api.capabilities.list()
```

- `Capabilities`: `handle(id, CapabilityHandler)`, `call(id, args, target)`, `list()`. `handle` throws for an id the module
  does not declare with `runs: daemon`. `call` never throws for a refusal.
- `CapabilityHandler`: the fun interface `call(CapabilityCall): JsonElement`; throw to fail the call.
- `CapabilityCall`: `capability`, `args`, `caller` (a `Caller`), `source` (`plugin:<module>`, `session:<id>` or `cli`).
- `CapabilityResult`: `Ok(value, host)` (host `daemon:<module>`, a client host id, or `all`) or `Failed(error)`.
- `CapabilityInfo`: one installed capability as `infantry_capability_list` shows it, with its `hosts` (`CapabilityHostInfo`:
  id, platform, label, lastActiveAt) and `available`.
- `CapabilityTarget`: `DEFAULT` (the most recently active desktop, else any host) and `ALL`; any other target is a host id.
- `NoCapabilities`: the default of the interface property, for a daemon without the bridge: nothing listed, every call fails.

## 6. Services between plugins

Each plugin has its own class loader, so plugins cannot share classes. Two mechanisms exist (source: `Services.kt`):

- **Typed:** `api.services.provide(MyApi::class, impl)` publishes an implementation of an interface that both sides
  can see (an interface from an API module). `api.services.find(MyApi::class)` returns it or null. One provider per type;
  a second `provide` throws. Official/core, or a community module with the grant `services.provide`.
- **String endpoints:** `api.services.publishEndpoint("scripting.engine/lua", ServiceEndpoint { op, request -> "..." })`
  and `api.services.endpoints("scripting.engine/")`. Operation, request and answer are plain strings. This is how an
  extension point is declared as an id prefix that other plugins contribute under. Closing the returned handle, or
  unloading the plugin, removes the provider.

## 7. Settings and storage

### Settings

Your own settings are `daemon.config` fields with `"scope": "daemon"`. They use the same field schema as UI config
(see [Make a plugin](make-a-plugin.md)). Read one with `api.config("key")`. The owner edits them in Settings. The
module's own limits (`daemon.limits`) override the global limits; the global limits are the `limits.plugins.*` settings
(section 8). `api.settings.limit("limits.x")` reads a global limit.

### Storage

`api.storage` gives you tables in the daemon's SQLite file (source: `storage/PluginStorage.kt`):

```kotlin
api.storage.migrate(1, listOf("CREATE TABLE acme_notes_item (id TEXT PRIMARY KEY, body TEXT)"))
api.storage.execute("INSERT INTO acme_notes_item VALUES (?, ?)", listOf("a", "hello"))
val rows = api.storage.query("SELECT * FROM acme_notes_item WHERE id = ?", listOf("a"))   // List<Map<String, Any?>>
api.storage.transaction { /* statements commit together; an exception rolls back */ }
```

Every table, index and view name must start with the module id lowercased, non-alphanumerics replaced by `_`, plus
`_` (so `acme_notes_`). An official module may also name the core tables it lists in `daemon.storage.adopt`. Values are
null, Long, Double, String or ByteArray (Int and Boolean bind as Long). `migrate(version, sql)` runs once and records the
version; a version already recorded or higher is a no-op. A community module needs the grant `storage`.

## 8. Lifecycle, timeouts and the guard

### Lifecycle

1. The daemon finds the module, checks the tier and grants, and loads `daemon.jar` in its own class loader.
2. It calls `start(api)` (limit `limits.plugins.startTimeoutMs`, 30 000 ms). A throw or a timeout disables the plugin.
3. The plugin serves calls. Every call goes through the guard.
4. On daemon close, or when the plugin is disabled, `stop()` runs (limit `limits.plugins.stopTimeoutMs`, 5 000 ms).
   Release threads and handles there. Then the half's `scope` closes (DaemonApi 1.49.0).
5. PLUGIN-HOTSWAP-1: on a live unload or reload, the host waits up to `limits.plugins.unloadCheckMs` (5 000 ms, 0 = no
   check) for the old class loader to be collected. One still reachable adds the note "<plugin> did not unload cleanly"
   to the answer; the daemon does not restart. See [Plugin lifecycle](Plugin-lifecycle.md).

### Grants for a community module

A community module is off until `enabled: true` in `modules.json`, and gets only what is listed in `allow`:
`daemon.code` (needed to load any daemon jar), `daemon.tool`, `daemon.frame`, `daemon.route`, `daemon.hook.<kind>`,
`daemon.env:<NAME>`, `daemon.secret:<name>`, `daemon.updateSource:<type>`, and the service grants named above. Without `daemon.code` the module
loads its UI half only and shows "community daemon code needs the 'daemon.code' grant in modules.json". Source:
`DaemonPluginHost.kt` and `docs/DAEMON-PLUGINS.md`.

```json
"modules": { "Acme_DaemonHello": { "enabled": true,
  "allow": ["daemon.code", "daemon.tool", "daemon.route", "daemon.hook.ready", "daemon.env:ACME_HELLO_FAIL"] } }
```

### Limits and the guard

Each plugin gets its own bounded thread pool and a circuit breaker (`PluginGuard.kt`). The daemon keeps running
whatever the plugin does. The limits are daemon settings (`limits.plugins.*` and `limits.plugin.guard.*`, defined in
`superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/settings/LimitRegistry.kt`). A module's
`daemon.limits` can override `callTimeoutMs`, `toolTimeoutMs`, `cpuMsPerCall`, `routeRequestsPerMinute`;
`memoryMbHint` is reported, not enforced.

| Setting | Default | Meaning |
|---|---|---|
| `limits.plugins.callTimeoutMs` | 2 000 ms | A hook, frame or route call longer than this is logged as **slow**. It is never a strike. |
| `limits.plugin.guard.timeoutMs` | 30 000 ms | The hang ceiling: a call with no result by then is a true hang and counts **one strike**. Never lower than the call's own timeout. |
| `limits.plugin.guard.strikes` | 3 | Consecutive hang-ceiling timeouts that open the breaker. |
| `limits.plugin.guard.cooldownMs` | 30 000 ms | How long an open breaker refuses calls, then one probe call is let through. A successful probe closes it again with no restart. 0 = probe at once. |
| `limits.plugins.toolTimeoutMs` | 60 000 ms | Default wall clock for one tool call. |
| `limits.plugins.toolMaxTimeoutMs` | 1 800 000 ms | The cap on a tool's own `timeoutMs`. The MCP client waits a quarter longer than the effective value. |
| `limits.plugins.poolSize` | 8 | Worker threads per plugin. |
| `limits.plugins.queueLimit` | 256 | Calls allowed to wait for a worker. A call past this is refused at once as "busy"; it is not a strike and the plugin stays loaded. |
| `limits.plugins.routeRequestsPerMinute` | 600 | Route requests a minute, then 429. |
| `limits.plugins.startTimeoutMs` | 30 000 ms | `start` and policy-change callbacks. |
| `limits.plugins.stopTimeoutMs` | 5 000 ms | `stop` on close. |

What the guard does, from `PluginGuard.kt`:

- A call that throws **disables** the plugin (reason "threw ..."): its tools and routes go away and `GET /plugins`
  shows `failed: ...`. The daemon carries on.
- A call whose own thread burns more CPU than `cpuMsPerCall` is a runaway and disables the plugin at once. The
  default CPU budget is the larger of the call timeout and the tool timeout (60 000 ms by default).
- A call that reaches the hang ceiling counts a strike. After `strikes` consecutive strikes the breaker **trips**:
  calls refuse fast ("cooling down, retry in Ns") until the cooldown passes. A single successful call resets the
  strike count.
- A call's clock starts when one of the plugin's workers picks it up, not when it is queued (BOARD-BREAKER-1). A burst
  of calls larger than the pool waits in the queue, and that wait never counts toward a strike. A call still queued at
  the hang ceiling fails alone with "plugin X is busy ..." and gives no strike. Before this change, a board of ~150
  cards that each read their ticket at once tripped the breaker on fast calls.
- A call refused by the guard comes back to the caller as a failure with the reason text. How each hook kind treats that failure is its fail-open or fail-closed rule in the table in section 4 (the host code that applies it was not traced for this page).

Practical rules: keep hook, frame and route handlers well under two seconds; put slow work in a tool (give it
`timeoutMs`) or a `schedule` job; never block a handler on the network without a timeout.

Test the guard with `Acme_DaemonHello`: the tool argument `sleepMs` blocks on purpose, and the env var
`ACME_HELLO_FAIL=1` makes its ready hook throw so the host disables it.

## Open questions

- `DaemonApi.kt` lists the version history out of order inside `companion object` (1.32.0 to 1.36.0 are interleaved
  with older entries). The constant `VERSION` is 1.36.0; the order of the comments is only cosmetic.
- The description text of `limits.plugins.callTimeoutMs` in `LimitRegistry.kt` mentions `limits.plugins.hangCeilingMs`, but
  the hang-ceiling limit's key in the registry is `limits.plugin.guard.timeoutMs` (singular `plugin`). This page uses the registry key.
- The tool-name rule (community names carry the vendor prefix) is stated in `DaemonApi.kt` and `docs/DAEMON-PLUGINS.md`;
  this page did not find the line in the host that enforces it.

## Type index (every other public type)

Generated for the `infantryDaemonApi` guard `SdkTypesDocumentedGuardTest`: every public top-level type of the module is named on this page. Types above are explained in full; the rest are listed by source file with the first line of their KDoc. Read the file for the members.

### `AgentsApi.kt`

- `AgentEntry`: One registry row: the effective [definition], who contributed it, and the user's override.
- `Agents`: AGENT-REGISTRY-1 (DaemonApi 1.5.0, additive; docs/AGENT-REGISTRY.md): the daemon's agent registry as a plugin sees it.
- `NoAgents`: A daemon without an agent registry: lists nothing, refuses every change.

### `Attention.kt`

- `Attention`: ATTN-1 (DaemonApi additive minor; docs/DAEMON-CORE.md section 8): attention as a provided service, published in the AR-5 registry (`services.find(Attention::class)`).
- `AttentionEntry`: One attention item as the service shows it ([kind] is an [AttentionKinds] wire name).
- `AttentionKinds`: The stable lower-snake-case kind names (identical to the wire names of the attention records).
- `QuestionsOpen`: ATTN-2a (DaemonApi 1.24.0, additive): published by the plugin that owns the owner questions.

### `NoNotifications.kt`

- `NoNotifications`: NOTIFY-CORE-1: the default of `DaemonApi.notifications` for a daemon without the bus: posts are dropped, the id is answered.

### `BoardApi.kt`

- `Board`
- `BoardColumn`
- `BoardResult`: The error model, as [com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply] plus the board's refusal.
- `EpicInfo`
- `Epics`
- `NoBoard`: What a daemon without a board answers: every operation is [BoardResult.Unavailable].
- `NoTicketEvents`: A daemon without a board has no events.
- `Page`: One page of a listing: [next] is the opaque cursor of the following page, null on the last.
- `PromptStart`: What a `prompt` hand-out got: the session it started, or the reason it was refused.
- `StartResult`: The answer to a start: the ticket, and what the board decided (`started`, `queued`, `held`, `start_refused`, `already_in_progress`).
- `TicketBinding`: The session a ticket is bound to.
- `TicketDraft`: A new ticket.
- `TicketEvent`: DP-4b (BOARD-API-2): one typed ticket event.
- `TicketEvents`: DP-4b: the ticket event stream.
- `TicketInfo`: A ticket as plugins see it: the fields a board reads and an agent sets, flat and plain.
- `TicketLinkInfo`
- `TicketPatch`: An edit: a null field keeps the stored value.
- `TicketQuery`
- `Tickets`

### `BoardHost.kt`

- `BaseChoice`: The ref a start cuts from and the setting that named it (e.g.
- `BoardGit`: Git work for tickets: cut or reattach a ticket's own branch and worktree, and remove them.
- `BoardHost`: DP-5c (BOARD_API_VERSION 1.6.0, docs/DAEMON-BOARD-API.md section 9): what the board PLUGIN needs from the HOST, the reverse of [Tickets] / [Epics] / [Board] (those are what the host offers a caller of
- `BoardSessions`: Session operations the board needs; richer reads stay on `Sessions` / `SessionReads`.
- `BoardSettings`: Reads of the host's settings by key (the JSON text the settings table stores); null when unset.
- `BoardStorage`: Typed access to the board's tables (section 4.5: they stay in the host store for now).
- `BoardTables`: The board's tables, as the repositories the board uses today (`TicketRepository`, `TicketRunRepository`) plus the event append.
- `BranchHandoffFacts`: AGENT-TASKS-2: [BoardGit.handoffFacts]' answer.
- `BranchOutcome`
- `BranchRequest`
- `ContextSourceInfo`: One source of a first turn's context and its size, as the session record keeps it.
- `LaunchVerdict`: The launch gate's answer: [refusal] stops the start, [warning] only qualifies it; both null = allow.
- `NoBoardHost`: What a host without board support answers; each member's own defaults apply.
- `SessionStart`: Everything the board asks a session to start.
- `SessionStartResult`
- `StoredLink`: One stored link, read "[ticketId] [type] [relatedId]".
- `StoredRun`: One start of a ticket and, once its session ended, the outcome.
- `StoredTicket`: A ticket as the tickets table stores it (the persistence row, flat and plain; the plugin builds its own views over it).
- `TicketEventAppender`: Records the typed events of one commit inside the open transaction (before / after map a ticket id to its column; the host derives created / moved / started / done / updated / deleted); the host deliv
- `TicketRunTable`
- `TicketTable`

### `BoardHostPorts.kt`

- `BoardAgent`: One agent profile (a named, role-bound worker identity), flat and plain.
- `BoardAgents`: The agent profiles the board assigns tickets to.
- `BoardContextSources`: The text a first turn carries from the host's knowledge base and skill library; null = no section.
- `BoardExecutor`: A resolved provider / model / effort triple; null = the daemon default applies.
- `BoardImages`: A ticket's image files under its project.
- `BoardProviderInfo`: DP-5f-2b: a provider a start may name.
- `BoardRoleInstance`: DP-5f-2b: the role instance a session is a clone of, frozen once at start (what `RoleInstance` is in core).
- `BoardRunHealth`: Reads of how a session's run ended, and of whether a ticket's worker can carry it.
- `BoardRunOutcome`: How a finished run ended.
- `BoardSessionDetail`: DP-5f-2b: a session plus what the stall and busy checks read: the last observed activity and whether a run is in flight.
- `BoardSessionEvent`: What the board hears of sessions.
- `BoardSessionListener`: Receives [BoardSessionEvent]s in arrival order, on the host's thread: hand work off rather than block.
- `BoardTrait`: DP-5f-2b: one trait frozen into an instance; the check is flattened (null [checkKind] = no check).
- `SessionDelivery`: DP-5f-2b: which thread a [BoardSessionListener] runs on.

### `BoardLines.kt`

- `BoardLines`: RUNTIME-LOAD-5: the whole board in a few lines (moved unchanged from core's `BoardSummary`, which delegates here), so `infantry board` and the `infantry_board` tool print the same thing.

### `BoardRuntime.kt`

- `BoardPacing`: [over] non-null = the start is refused with that reason; [warning] is advice on an allowed start.
- `BoardRuntime`: DP-5h-3a (DaemonApi 1.12.0, additive; docs/DAEMON-BOARD-API.md section 10): everything a BOARD PLUGIN needs from the daemon to build its board, beyond the [BoardHost] ports: the stored settings the bo
- `NoBoardRuntime`: A daemon without a board runtime.
- `BoardRunner`: RUNNER-POOL-6: one runner as placement sees it (`BoardRuntime.runners()`): name, `up` / `degraded` / `down`, free slots, the projects it accepts, the harnesses signed in there.

### `BoardRuntimeInputs.kt`

- `BoardCoreAccess`: DP-5h-3e-b (DaemonApi 1.14.0, additive; docs/DAEMON-BOARD-API.md section 10): what the BOARD PLUGIN hands back to core once it has built the board, so core's own consumers (start charge, orchestrator 
- `BoardCoreTicket`: DP-5h-3e-b (1.14.0): what core reads of a ticket without any board type.
- `BoardEpicInputs`: DP-5h-3a2 (1.13.0): inputs of the epic store: persistence as `Epic` JSON (the plugin owns the type), and the project checks.
- `BoardRelatedDraft`: DP-5h-3a2 (DaemonApi 1.13.0, additive): KNOWLEDGE-PTR-1 (C): a ticket as a save or link is about to store it, with the board rows and links.
- `BoardStoryInputs`: DP-5h-3a2 (1.13.0): inputs of the deprecated story frames.

### `DaemonApi.kt`

- `ActionInfo`: The daemon's catalog entry of a frame: its action id, kind (`read/write/destructive/spawn`) and scope (`GLOBAL/PROJECT/SESSION`).
- `ApprovalRuleMatch`: The rule that matched: its [tool] and, for a shell tool, the command [prefix].
- `AutomationMark`: SCRIPT-VIS-1b: which script is running for a session ([hookId] names the hook, [since] is epoch millis).
- `FrameBinding`: DP-5h-2: register a handler for every one of [types]; [handler] gets the frame and whether it is `direct`.
- `IncomingMail`: 1.30.0 (MAIL-HOOK-1): a mail accepted for delivery, shown BEFORE the recipient is woken.
- `Policy`
- `ProjectInfo`: [description] (additive, XPROJ-1): the project's one-line description, its data key `project.description` ("" = none).
- `Projects`
- `QueuedMessage`: CHAT-QUEUE-1: one message held for a busy session: its [id], the [text], when it was queued ([at], epoch millis).
- `SessionInfo`
- `SessionStatus`: SCRIPT-SESSION-1: [state] is the state name; [lastAction] a short text; the times are epoch millis (null = none yet).
- `Sessions`
- `ToolInfo`: One entry of [Tools.catalog].
- `ToolInvoker`: Who a [Tools.invoke] call runs as: the identity of the agent on whose behalf it runs, and a free label for logs (`script:<name>`).

### `HarnessCatalogModel.kt`

- `HarnessCatalogText`
- `PinnableHarness`
- `ProviderInfo`: RUNTIME-LOAD-2: the `providers` / `models` answers as a daemon plugin reads them (same fields and defaults as `ProviderInfo` and `ProviderModels` in `infantryProtocol`, which stay the wire's definitio
- `ProviderModels`

### `HarnessInstallModel.kt`

- `HarnessInstallOutcome`
- `HarnessInstallReport`
- `HarnessInstallResult`
- `HarnessInstallStatus`: RUNTIME-LOAD-1: the `harness_install_status` / `harness_install` answers as a daemon plugin reads them.

### `HarnessInstallText.kt`

- `HarnessInstallText`: RUNTIME-LOAD-1: the printed tables of `harness-status` / `harness-install`, shared by the CLI and the Infantry_Agents tools.

### `Knowledge.kt`

- `Knowledge`: DP-7b (DaemonApi 1.17.0, additive; docs/DAEMON-CORE.md section 8): the read-only knowledge port, a service published in the AR-5 registry (`services.find(Knowledge::class)`).
- `KnowledgeDecision`: An active decision: [ref] is `D<n>`, [summary] its one line.
- `KnowledgeDigestView`: What [Knowledge.digest] returns: counts, and the display lines already filtered (superseded decisions / fixed issues are out).
- `KnowledgeHit`: One [Knowledge.search] hit.
- `KnowledgeIssue`: An open issue: [ref] is `I<n>`, [location] the path it is about ("" for none).
- `KnowledgeRecords`: DP-7c (DaemonApi 1.17.0, additive): the decisions / issues half of [Knowledge], a service published in the AR-5 registry by the plugin that owns those logs (Infantry_Notes).
- `KnowledgeWriter`: SCRIPT-ARSENAL-2 (DaemonApi 1.51.0, additive): the write half of [Knowledge], published by Infantry_Notes; `save`/`delete`/`names` of generated notes; `GLOBAL_PROJECT` entries are found by every project's note search and show.

### `LocalFiles.kt`

- `LocalFile`: RUNTIME-LOAD-4 (DaemonApi 1.4.0, wire contract 1.147.0, additive): a tool argument that names files on the CALLER's machine.
- `LocalFiles`

### `MetersApi.kt`

- `MeterReading`: One provider window as read now.
- `MeterRequest`: USAGE-FIT (DaemonApi 1.20.0, additive): one recorded model request, read-only, from the daemon's usage meter.
- `Meters`
- `NoMeters`: A daemon without meters: reads nothing.

### `OrchestratorLink.kt`

- `LocalOrchestratorLink`: The binding for `orchestrator.provider = local`: answers in this process.
- `LocalOrchestratorServices`: The orchestrator-side functions the local binding answers from.
- `OrchestratorHub`: The binding registry plus the link every caller holds.
- `OrchestratorLink`: ORCH-1 (docs/DAEMON-CORE.md section 6): the ONE seam between the runner side of the daemon (sessions, harnesses, working copies, the watch runner, CLI process handling) and the orchestration side (boa
- `OrchestratorReply`: The error model: an answer, or "the orchestrator could not be reached / did not understand", never an exception.
- `RunnerLink`: The ORCHESTRATOR -> RUNNER interface: the only way orchestrator-side code reaches sessions. RUNNER-POOL-2: the runner command contract (`CONTRACT_VERSION` 1.0.0): `hello`, `startSession`, `stopSession`, `deliverMail`, `health`, each an `OrchestratorReply`; an operation a binding does not offer answers `Unavailable`.
- `TicketRef`: The session and project a ticket is bound to.
- `FrameSide`: RUNNER-POOL-7 (1.46.0): the frame table's generic side attribute, `runner` (default) or `orchestrator`; `of(side)` reads anything else as `runner`.
- `AgentIdentity`: RUNNER-POOL-7: the agent a forwarded frame speaks for: the runner (named by the link, never the frame) and its session id.
- `ForwardedFrame`: RUNNER-POOL-7: one frame a remote runner forwards for its agent, and the orchestrator's answer (`OrchestratorLink.forwardFrame`, `LocalOrchestratorServices.forwardFrame`; link contract 1.2.0).

### `RunnerPool.kt`

RUNNER-POOL-2 (docs/RUNNER-POOL.md 2.1, 2.2): the plain data of the runner command contract, the local binding and the pool.

- `RunnerInfo`: What a runner can run, answered by `RunnerLink.hello`.
- `RunnerHarness`: One harness a runner offers: its id, its models, whether its CLI is signed in.
- `StartSpec`: A session start; `startId` is the natural key, a null field is the runner's default. RUNNER-POOL-6: `gitRemote` (the runner clones it and cuts the ticket worktree), `base` / `branch`, `prompt`, `title`, and `envNames` (names only, never values).
- `StartWorktree`: The ticket worktree a runner cut for a remote start (path, branch, base, repo root), handed to its local binding in `StartSpec.worktree`.
- `RunnerEvent`: One event a runner queued for its orchestrator (`status`, `finished` after the branch push), read with `RunnerLink.events(sinceSeq)`.
- `SessionRef`: The answer to `RunnerLink.startSession`: the session started, a refusal, or neither (the spec names no agent and no role).
- `MailItem`: One composed mail turn for `RunnerLink.deliverMail` (the sender as `MessageOrigin` JSON, the folded message ids).
- `MailResult`: `delivered`, `queued` (a run is in flight: keep it queued) or `failed`.
- `RunnerHealth`: A runner's heartbeat: state, running sessions, free slots, load.
- `LocalRunnerServices`: The runner-side functions the local binding answers from (the daemon builds one over its session manager).
- `LocalRunnerLink`: The binding for the runner in this process (`local`).
- `RunnerEntry`: A runner the pool knows: name, binding id, last health and info.
- `RunnerPool`: The orchestrator-side view of every runner: `runners()`, `link(name)`, `runnerOf(sessionId)`; `local` is always present.
- `RunnerBinding`: A plugin's runner binding, offered with `hooks.runnerBinding`.
- `RunnerHub`: The pool core holds: the `local` runner and the registry of runner bindings plugins offer.

### `runners/RunnerLinkTransport.kt`

RUNNER-POOL-6a (DaemonApi 1.44.0, additive), package `com.superiorsoftllc.infantry.daemon.api.runners`; the official
`Infantry_RemoteRunner` plugin offers the wss implementation.

- `RunnerLinkTransport`: The runner link's transport, offered with `hooks.runnerLinkTransport`: `call` (one orchestrator -> runner frame and its answer, the runner's certificate checked against the pinned fingerprint), `serve` (the runner's end, every request to a `LinkHandler`) and `fingerprint` (the certificate's SHA-256 pin).
- `LinkAnswer`: One link answer: a reply or error frame type and its data.
- `LinkFailure`: A link call that got no answer; `presentedFingerprint` is set when the pin refused the runner's certificate.
- `LinkTlsMaterial`: Where the runner certificate lives (the link folder) and the keystore password core keeps; the transport makes and loads the certificate there.
- `LinkHandler`: Core's runner-side handler the transport calls: one request (type, data, orchestrator id, credential, peer address) in, one answer out; `malformed()` for an unreadable frame.
- `LinkServing`: A served link: its `port`; closing it stops serving.

### `runners/RunnerLinkWire.kt`

RUNNER-POOL-6 (DaemonApi 1.45.0, additive), package `com.superiorsoftllc.infantry.daemon.api.runners`.

- `RunnerLinkWire`: The link frames (contract 1.2.0) and their JSON bodies: hello (harnesses and sign-in yes/no, accepted projects, capacity), health, start, stop, deliver, events, transcript; RUNNER-POOL-7 (1.46.0): `forward_pull` / `forward_answer`, the orchestrator taking the frames a runner holds for it and answering them. A runner binding encodes them; core's runner side answers them.
- `RunnerLinks`: The orchestrator side's paired runners, offered as `api.runnerLinks`: `paired()` and `call(runner, type, data)` (a `LinkFailure` when the runner cannot be reached).
- `NoRunnerLinks`: No paired runner (a community plugin, or a daemon without the link service).

### `OwnerAsks.kt`

- `OwnerAsks`: PLUGIN-MCP-1 (DaemonApi 1.36.0, additive): the owner-question service as a provided service (`services.find(OwnerAsks::class)`), published by the plugin that owns the owner questions.

### `PluginCatalogReader.kt`

- `CatalogSummary`: One catalog entry's latest compatible version.
- `NoPluginCatalog`: A daemon without a plugin manager.
- `PluginCatalogReader`: PLUGIN-MCP-1 (DaemonApi 1.36.0, additive; docs/PLUGIN-MANAGER.md section 10): a READ-ONLY view of the plugin manager on this host's plugins root, plus [request], the only way an agent asks for an inst
- `PluginRequestResult`: [questionId] null = no question was raised ([message] says why).
- `PluginShowResult`: An inspection (nothing installed).
- `PluginSummary`: One plugin on this host.

### `PluginStorage.kt`

- `NoPluginStorage`: The default: every call refuses.

### `ProcessesApi.kt`

- `GateLockView`
- `GateOwner`: The machine-wide gate lock's owner (`owner.txt`, else the `pid` file).
- `KillResult`: What a [Processes.kill] came to.
- `LaunchEnv`
- `LaunchInfo`: The per-launch facts a [LaunchEnv] contributor may read.
- `NoLaunchEnv`
- `NoPluginSettings`
- `NoProcesses`
- `PluginSettings`: Read access to the settings a plugin's own limits live in: `limits.*`, `watch.*` and `processes.*` keys only.
- `ProcInfo`: One live OS process.
- `ProcMem`: One process's memory as the OS lists it.
- `ProcessDaemonDir`: PROC-LEAK-2 (1.39.0): how to learn which project a detached daemon serves when its client is gone.
- `ProcessEnvTemplate`: An env template of a tool profile (`${maxWorkers}` / `${maxParallelForks}` in [value]).
- `ProcessToolProfile`: What the watch reads of a tool profile (data, never code: which processes are build daemons, shared, heavy, and what env a launch gets).
- `Processes`: The host process service.
- `ProcessesHandler`: The plugin's answer to a `processes` frame.
- `SessionClosed`: One session closing (kill, remove, restart, a failed turn): [keepPids] and everything under them must survive.
- `WatchedSession`: A session as the watch sees it.

### `ProcessesModel.kt`

- `CpuAlertConfig`: Ticket 66153313: raise an attention item when agent processes hold [percent] % of all cores for [minutes] (0 = off).
- `ProcessReport`
- `ProcessesAnswer`: The `processes` answer: the report after the op, what it killed, and why a `reap` was refused (null = not refused).
- `ProcessesRequest`: Contract 1.120.0: one `processes` request.
- `TrackedProcess`: One process as the detector reports it.

### `ProcessesText.kt`

- `ProcessesText`: DAEMON-PLUGINS-3: the shared plain-text rendering of a `processes` answer, used by `infantry processes` and the `infantry_processes` / `infantry_processes_reap` tools (byte-identical for both).

### `ProgressModel.kt`

- `ProgressReport`: One `progress` request (the CLI, a marker line and the MCP tool all build this).
- `ProgressText`: How a progress value reads as text and as a countdown (pure; [now] is the caller's clock).
- `SessionProgress`: Contract 1.117.0 (ticket-1371d44b): how far along an agent's long job is, as the AGENT measured it.

### `ProjectConfigModel.kt`

- `ProjectConfigPatch`
- `ProjectConfigView`
- `RoutingRuleRecord`: RUNTIME-LOAD-3: the `project_config` request and the part of the `project` answer the Infantry_Settings daemon half reads.

### `ProjectFiles.kt`

- `DirectoryProjectFiles`: The java.io binding of [ProjectFiles] over [projectDirectory]; the host hands this to plugins, tests build it over a temp dir.
- `NoProjectFiles`: The default of a daemon with no file surface: every call refuses.
- `ProjectFileEntry`: One regular file in a [ProjectFiles] folder: its name, size and last-modified time (epoch millis).
- `ProjectFiles`: DP-10a (DaemonApi 1.21.0, additive): a project-scoped file surface, rooted at `<project directory>/.infantry/`.

### `RoleModel.kt`

- `RetiredRoleIds`: Role ids renamed by the id redraw (contract 1.90.0): the one table, read by the daemon's `RoleIds` and the roles tools.
- `RoleRecord`: RUNTIME-LOAD-3: the wire shape of a role (`roles` / `role` frames) and of `role_save`, for the Infantry_Roles daemon half.
- `RoleSaveRequest`
- `Roles`: AR-4b (DaemonApi 1.32.0, additive; docs/DAEMON-CORE.md section 8): the read-only role catalog port, a service in the AR-5 registry (`services.find(Roles::class)`).

### `RolePorts.kt`

- `RoleBindingRecord`: AR-4b-ii-b (DaemonApi 1.34.0): the port DTOs are the API's own, never the wire module's, because a plugin's class loader sees the daemon API but not `com.superiorsoftllc.infantry.protocol`, so a proto
- `RoleBindings`: AR-4b-ii-a (DaemonApi 1.33.0, additive; docs/DAEMON-CORE.md section 8): the ports the role binding / trait handlers and the spawn path read through, so the role store can move into Infantry_Roles.
- `RoleEnvironment`: What validating a binding / trait list / role delete needs from core: providers, models, traits and live sessions.
- `RoleTraitLists`
- `RoleTraitsRecord`

### `Runs.kt`

- `NoRuns`: The default of a daemon with no run surface.
- `RunHandle`
- `RunListener`: Output callbacks, on the run's own drain threads.
- `RunSpec`
- `Runs`: CMD-1 (DaemonApi 1.31.0, additive): the run surface.

### `Services.kt`

- `NoServices`: A daemon without a registry: nothing can be published, nothing is found.
- `ServicesApi`: AR-5 (DaemonApi 1.16.0, additive; docs/DAEMON-CORE.md section 8): the typed optional service registry.

### `SessionBinding.kt`

- `FirstTurnContextProvider`: A contributor to a session's first turn.
- `SessionBinding`

### `SessionNotifier.kt`

- `NoSessionNotifier`: A daemon without a mail layer: nothing is delivered.
- `SessionNotifier`

### `SessionText.kt`

- `SessionText`: RUNTIME-LOAD-5: how a session's activity reads, in one place, so `infantry board`, the session tools and the board tool print the same words (moved unchanged from core's `BoardSummary`; core delegates

### `SessionsApi.kt`

- `NoSessionReads`: A daemon without sessions to read: every call Unavailable.
- `ReadScope`: The project a read is scoped to ([projectId] null = every project) and why the call may not have what it asked for, if so.
- `SessionReads`: The daemon's sessions, read through the HOST's project scope: the rule of the board API (docs/DAEMON-BOARD-API.md), seeing the caller as the daemon's session record for [Caller.activeSession], never a
- `SessionView`: RUNTIME-LOAD-5 (DaemonApi 1.6.0, additive): the READ model of the daemon's sessions (docs/RUNTIME-LOAD.md section 11).
- `SessionsResult`: The answer of a [SessionReads] call.
- `Transcript`: A session's replayed output as readable lines (oldest first), and a note when the daemon holds none or only part of it.

### `SpawnText.kt`

- `SpawnText`: RUNTIME-LOAD-7 (DaemonApi 1.9.0, additive): the answer texts of a ticket start, shared by `infantry_ticket_start` (Infantry_Board) and `infantry_spawn` given a `ticketId` (Infantry_Agents), the very J

### `SpawnsApi.kt`

- `Admission`: The limits in force for one call: the depth the new session would get, the caller's open spawns, and the two maxima.
- `NoSpawnCharge`: A daemon without a spawn guard: nothing is charged, [create] simply runs.
- `NoSpawns`: A daemon without a spawn service: every call Unavailable.
- `SpawnCharge`: DP-5h-2 (DaemonApi 1.11.0, additive): the spawn guard's admit / charge semantics for a plugin that starts workers itself (a board moving a ticket to In Progress).
- `SpawnKind`: What a spawn is for: the refusal texts name the new session, or the ticket's worker.
- `SpawnNarrowing`: The calling client's own guardrail values (`infantry mcp serve --max-depth / --max-spawns / --mcp-depth`, `.infantry/mcp.json`).
- `SpawnRequest`: A session to create, as the `new` frame's options.
- `Spawned`: The session [spawn] created, at [depth], and the caller's open spawns counting it.
- `Spawns`: RUNTIME-LOAD-6 (DaemonApi 1.7.0, additive): the HOST spawn service (docs/RUNTIME-LOAD.md section 12).
- `TicketStartOutcome`: What a ticket start did.

### `TicketImages.kt`

- `ContextLimits`: RUNTIME-LOAD-4 (BOARD_API_VERSION 1.3.0): the one rule for a context limit value (CONTEXT-LIMIT-1, docs/CONTEXT-LIMIT.md), moved here from the daemon so the CLI, the daemon and a board plugin's tools 
- `EffortLevels`: EFFORT-1 (BOARD_API_VERSION 1.4.0): the shared rule for a reasoning-effort level (docs/EFFORT.md), the same list the daemon keeps (`session.EffortLevels`), so a board plugin's tools read a level the s
- `TicketImages`: RUNTIME-LOAD-4 (BOARD_API_VERSION 1.3.0): the rules of a ticket's images (IMG-AGENT-1), shared by `infantry ticket create/update --image` (CLI) and Infantry_Board's ticket tools.

### `TicketPlacement.kt`

- `PlacedTicket`: RUNTIME-LOAD-4 (BOARD_API_VERSION 1.3.0, additive): the board's two pure rules every client of it shares, here so the CLI (`ticket move`, every `<ticketId>` argument) and a board plugin's tools cannot
- `TicketPlacement`: Where in its column a `ticket_move` puts a ticket: ONE of [top], [bottom], [before] / [after] (just above / below that ticket) or [position] (0 = top), turned into the `position` the daemon takes.
- `TicketRefs`

### `TicketReports.kt`

- `TicketReports`

### `TriggerPorts.kt`

- `TriggerHost`: The host side of triggers.
- `TriggerMailRequest`
- `TriggerMailResult`
- `TriggerRunEvent`: A run's end as the trigger sees it.
- `TriggerSession`: One session as a trigger sees it.
- `TriggerStore`: The durable trigger tables (rows, events, webhook secrets, the pending nudge mail).

### `Updates.kt`

- `Credential`: A secret a source needs (a read token for a private repo, a vendor's license key).
- `NoUpdates`: The engine of a daemon without one: no components, every call refused.
- `SourceOffer`: A newer version a source found.
- `SourceRequest`: What the engine hands a source: the component, its declaration and the resolved credential (null = none needed or none found).
- `UpdateApplier`: Installs a verified, staged file for every component of one [kind].
- `UpdateComponent`: One thing the engine can update.
- `UpdateSourceType`: How one type of release location is read.
- `UpdateState`: Where one component stands.

### `UsageModel.kt`

- `LimitWindow`: One plan-limit window of one provider.
- `ManualUsage`: A percent typed in by hand for a meter (setting `usage.manual`), valid until [expiresAt].
- `MeterUsage`: One meter in a [UsageReport].
- `PacingRule`: The pacing rule (setting `usage.pacing`).
- `PacingTier`: Below [below] percent used (exclusive), [workers] ticket workers may run; [below] null = every percent left.
- `UsageMeters`: Maps a run's (harness, model) to the meter it spends.
- `UsageOverride`: A hand-set allowance for a meter (setting `usage.overrides`): [workers] null = no limit, until [until] (epoch millis).
- `UsageReport`: The `usage_report` frame (contract 1.119.0).
- `UsageSet`: The `usage_set` frame (contract 1.121.0), answered by `usage_report`: a meter's manual percent and/or override set or cleared, and/or the pacing rule replaced.
- `UsageText`: Plain-text rendering of a [UsageReport], shared by `infantry usage` and `infantry_usage`.
