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
   Release threads and handles there.

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
