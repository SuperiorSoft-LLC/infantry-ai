# Daemon tools and the rest of the daemon half

Truth: `rust/crates/infantry-plugin-wit/wit/daemon-plugin.wit` (every function below),
`rust/crates/infantry-plugin-host/src/imports.rs`, `rust/crates/infantry-daemon/src/compose/tools.rs` (the tool list).

## Tools: what agents, the CLI and scripts call

Declare each tool in `daemon.tools`:

```json
"tools": [
  { "name": "acme_hello", "action": "acme.hello.read", "kind": "read",
    "description": "Greets someone by name.",
    "inputSchema": { "type": "object", "properties": { "name": { "type": "string" } } },
    "timeoutMs": 5000 }
]
```

- `name`: `^[a-z][a-z0-9_]{2,63}$`, prefixed with your module's word (`acme_`), unique across loaded plugins.
- `kind`: `read` (the default), `write` or `destructive`. MCP clients see a `read` tool as read-only.
- `description` and `inputSchema`: what MCP clients show (no schema = any object).
- `timeoutMs`: this tool's budget, capped by `limits.plugins.maxDeclaredTimeoutMs`.

The daemon adds every tool of every healthy loaded plugin to its tool list, unless a core tool already has that
name. The export `call-tool(name, args, ctx)` answers: `args` is the JSON arguments object, `ctx` says who called
(owner, agent, session, project, role). `Ok(text)` is the result, `Err(text)` an error result.

Ways to call a tool:

| From | How |
|---|---|
| An agent | as an MCP tool through `infantry mcp serve` (the agent's MCP bridge) |
| Any MCP client | `infantry mcp serve` (MCP over stdio): `tools/list`, then `tools/call` with `{"name":"acme_hello","arguments":{...}}` (see [Build and load](build.md)) |
| A Lua script | the scripting tool bridge, like any daemon tool |
| The wire | frame `plugin_tool_call {name, arguments}`, answered by `plugin_tool_result {text, isError}`; `plugin_tools` lists them |

A `capabilities` entry with `runs: "daemon"` is answered by a tool too (see [Create](create.md)); agents reach it with
`infantry_capability_call`.

## Frames: what the app sends

`daemon.frames: [{type, action, kind, ownerOnly, timeoutMs}]`. The export `handle-frame(frame, caller)` answers with
`Ok(Some(frame))` (the reply), `Ok(None)` (no reply) or `Err(e)` (sent as `<type> refused: <e>`). A plugin may not
declare a frame core answers (`plugin_tools`, `plugin_tool_call`, `plugin_tool_result`, `replay_request`, `ready`,
`error`).

## Routes

`daemon.routes` (behind the daemon token) and `daemon.publicRoutes` (no token) name HTTP routes; `handle-route`
receives `{method, path, query, headers, body}` and returns `{status, headers, body}`.
`limits.routeRequestsPerMinute` caps them.

## Hooks

`daemon.hooks` names hook kinds: `frameGate`, `connected`, `httpGate`, `admission`, `mailGate`, `incomingMail`,
`spawnGate`, `ready`, `turns`, `sessions`, `policyProvider`, `orchestratorBinding`, `runnerBinding`, `usageReading`,
`runnerLinkTransport`, `seats`, `approvalRules`, `status`. `on-hook(kind, payload)` gets and returns JSON, per kind.
An unknown kind refuses the module.

## Imports

`log` and `config` are always linked. The rest are linked when `daemon.needs` or `daemon.wants` names them. A module
whose `needs` cannot be granted does not load; a refused `wants` entry fails each call with `<name> not granted`.

| Import | Functions | Notes |
|---|---|---|
| `log` | `write(level, line)` | to the daemon log, prefixed with the module id |
| `config` | `module-id`, `tier`, `api-version`, `environment`, `get(key)`, `endpoint(name)` | [Configure](configure.md), [Endpoints](endpoints.md) |
| `settings` | `get`, `set`, `remove` | stored as `<Module>.<key>` |
| `storage` | `kv-get`, `kv-set`, `kv-delete`, `kv-keys(prefix)`, `execute(sql, params)`, `query(sql, params)` | SQL may name only tables prefixed `<module id in lower case>_` (plus `storage.adopt` tables) |
| `events` | `publish(name, payload, cause-id)`, `subscribe(glob)`, `unsubscribe(id)`, `broadcast(type, data)` | subscribed events arrive at `on-event`; `broadcast` pushes a frame to every client, only of a type listed in `daemon.pushFrames`; core types (session*, ticket*, auth*, agent*, prompt*, vault, plugin*, ...) are refused for every tier, and a community module may push no core protocol name at all. The envelope carries `origin: {type:"system", kind:"plugin:<id>"}` |
| `http` | `send(request)` | only to `daemon.endpoints` addresses |
| `frames` | `request(type, data, session-id, timeout-ms)` | a frame into the daemon, as a non-owner unless the type is in `ownerFrames`; also the way to [secrets](secrets.md) |

## Sidecars

A sidecar (`daemon.sidecar: {exe, args}`) implements the same world as JSON lines over stdin and stdout (the `jsonl`
module of `infantry-plugin-wit`); callers cannot tell it from a component. It runs with no console window, a scrubbed
environment (only `daemon.env` names pass), and its stderr goes to the daemon log. Use one when the plugin needs
something WASM cannot do (SSH, a native library). The Rust side of a sidecar builds with `infantry-plugin-sdk`.

`infantry-plugin-sdk` (and `infantry-plugin-wit`, which it needs) are not on crates.io (`publish = false`). Outside the
repository, take them from git, pinned to a commit or tag:

```toml
[dependencies]
infantry-plugin-sdk = { git = "https://github.com/SuperiorSoft-LLC/infantry-ai", rev = "<commit>" }
```

or a `path` dependency into a local checkout (`<checkout>/rust/crates/infantry-plugin-sdk`), or a vendored copy of both
crates. Publishing them to crates.io needs the owner (both crates flipped to publishable and released together).
