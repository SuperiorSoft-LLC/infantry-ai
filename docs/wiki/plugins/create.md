# Create a plugin

This page writes `Acme_Hello`, a plugin with one daemon tool, `acme_hello`. [Build and load](build.md) builds it,
loads it into a daemon and calls the tool.

## 1. The folder

```
Acme_Hello/
  module.json
  Cargo.toml
  src/lib.rs
  wit/daemon-plugin.wit      # a copy of rust/crates/infantry-plugin-wit/wit/daemon-plugin.wit
```

The module name is `Vendor_Module` style (`Acme_Hello`). It is also the folder name under `official/`.

Copy the WIT file from the Infantry AI repository. A plugin inside the repository (`rust/plugins/official/<Module>`)
points `wit_bindgen::generate!` at `../../../crates/infantry-plugin-wit/wit` instead of copying it.

## 2. module.json

```json
{
  "name": "Acme_Hello",
  "version": "0.1.0",
  "description": "Says hello: one daemon tool, acme_hello.",
  "daemon": {
    "component": "daemon.component",
    "config": [ { "key": "hello.greeting", "scope": "daemon", "default": "Hello" } ],
    "tools": [
      { "name": "acme_hello", "kind": "read", "description": "Greets someone by name.",
        "inputSchema": { "type": "object", "properties": { "name": { "type": "string" } } } }
    ]
  }
}
```

### Top-level keys

| Key | Meaning |
|---|---|
| `name` | The module id. Required. |
| `version` | Shown in the plugin manager; compared on update. |
| `description` | One line for the plugin manager. |
| `capabilities` | `[{id, runs, action}]`. A `runs: "daemon"` capability is answered by a daemon tool: the tool with the same `action`, else the tool named after the id with `.` and `-` turned into `_` (`news.headlines` -> `news_headlines`). |
| `ui` | The web UI half: see [UI pages](ui-pages.md). |
| `tabs` | `[{id, container}]`: the tabs the module adds (`container: "center"` for a centre tab). |
| `data` | Folders shipped as they are (`stage.sh` copies them). |
| `requires` | Module ids this one needs; the installer installs them first and refuses a cycle. |
| `cards`, `roles`, `actions` | Chat card specs, roles and permission actions the module brings (read by the app, the role catalog and the policy). |

### `daemon` keys

| Key | Meaning |
|---|---|
| `component` | Path of the `.wasm` component inside the module folder. |
| `sidecar` | `{exe, args}`: a native program inside the module folder (`.exe` is added on Windows when the name has no extension). |
| `needs` | Imports the plugin cannot run without: `settings`, `storage`, `events`, `http`, `frames`. |
| `wants` | Imports it can run without. A refused one is linked to a stub whose every call fails with `<name> not granted`. |
| `apiAtLeast` | The lowest daemon plugin ABI it needs. The host speaks `0.1.0`. |
| `tools` | `[{name, action, kind, description, inputSchema, timeoutMs}]`. `name` must match `^[a-z][a-z0-9_]{2,63}$`; `kind` is `read` (the default), `write` or `destructive`. |
| `frames` | `[{type, action, kind, ownerOnly, timeoutMs}]`: WebSocket frame types it answers. |
| `ownerFrames` | Frame types it may send into the daemon with the owner's authority (official and core modules only). |
| `hooks` | Hook kinds it answers: `frameGate`, `connected`, `httpGate`, `admission`, `mailGate`, `incomingMail`, `spawnGate`, `ready`, `turns`, `sessions`, `policyProvider`, `orchestratorBinding`, `runnerBinding`, `usageReading`, `runnerLinkTransport`, `seats`, `approvalRules`, `status`. |
| `routes`, `publicRoutes` | HTTP routes it serves (`publicRoutes` need no daemon token). |
| `config` | Settings it declares: see [Configure](configure.md). |
| `endpoints` | Outbound addresses per environment: see [Endpoints](endpoints.md). |
| `env` | Sidecar only: names of environment variables passed through from the daemon's environment. |
| `limits` | `callTimeoutMs`, `toolTimeoutMs`, `cpuMsPerCall`, `routeRequestsPerMinute`, `memoryMbHint`. |
| `storage.adopt` | Existing core tables an official module takes over. |

The daemon ignores every other key. Older manifests may still carry `entry`, `jar`, `buildOrder` or `apiVersion`;
they do nothing in the Rust daemon.

### Why a module is refused

Loading fails with `refused: <reason>` (the plugin manager and the daemon log show it) when:

- `component` and `sidecar` are both set, or neither is;
- a path points outside the module folder (absolute, or with `..`);
- a tool name breaks the pattern, a hook kind is unknown, or a frame type is one core answers itself
  (`plugin_tools`, `plugin_tool_call`, `plugin_tool_result`, `replay_request`, `ready`, `error`);
- an endpoint is not `http://` or `https://`, or a `prod` endpoint is not `https://`;
- `apiAtLeast` names a `0.x` version above the host's.

## 3. Cargo.toml

```toml
[package]
name = "acme-hello"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]

[dependencies]
wit-bindgen = "0.46"
serde_json = "1"

[workspace]
```

The empty `[workspace]` keeps the crate out of any parent workspace. Inside the Infantry AI repository a plugin is a
member of the `rust/` workspace instead, with `crate-type = ["cdylib", "rlib"]` so its tests can link it, and
`wit-bindgen` only under `[target.'cfg(target_arch = "wasm32")'.dependencies]`.

## 4. src/lib.rs

```rust
wit_bindgen::generate!({ path: "wit", world: "daemon-plugin" });

use infantry::plugin::{config, log};

struct Hello;

impl Guest for Hello {
    fn start() -> Result<(), String> {
        log::write(log::Level::Info, "hello plugin started");
        Ok(())
    }
    fn stop() {}
    fn call_tool(name: String, args: String, _ctx: CallContext) -> Result<String, String> {
        match name.as_str() {
            "acme_hello" => {
                let a: serde_json::Value = serde_json::from_str(&args).unwrap_or_default();
                let who = a.get("name").and_then(|v| v.as_str()).unwrap_or("world");
                let greeting = config::get("hello.greeting").unwrap_or_else(|| "Hello".into());
                Ok(format!("{greeting}, {who}! (env {}, api {})", config::environment(), config::api_version()))
            }
            other => Err(format!("unknown tool {other}")),
        }
    }
    fn handle_frame(_f: Frame, _c: Caller) -> Result<Option<Frame>, String> { Ok(None) }
    fn handle_route(_r: RouteRequest) -> Result<HttpResponse, String> { Err("no routes".into()) }
    fn on_hook(_k: String, _p: String) -> Result<String, String> { Err("no hooks".into()) }
    fn on_event(_s: u64, _e: NamedEvent) {}
}

export!(Hello);
```

All seven exports must be implemented; the ones you do not use return an error or nothing. `args` is the JSON object
of the tool's arguments; `Ok(text)` is the tool's answer, `Err(text)` an error result.

Next: [Build and load](build.md).
