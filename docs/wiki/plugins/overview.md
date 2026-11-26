# Plugins: overview

A plugin (a *module*) is a folder with a `module.json`. Infantry AI's features (the news card, the notes, the
installer, the triggers tab, hosting) are plugins; the daemon core only offers generic seams that a plugin fills.

## The halves of a plugin

| Half | What it is | Declared by | Runs where |
|---|---|---|---|
| Daemon half, WASM | a WebAssembly component built for `wasm32-wasip2` against the WIT world `daemon-plugin` | `daemon.component` | inside the daemon (wasmtime), sandboxed |
| Daemon half, sidecar | a native program that speaks the same world as JSON lines over stdio | `daemon.sidecar` | a child process of the daemon |
| Web UI half | an ES module built with `@infantry/ui-plugin-api` | `ui` | inside the app (desktop, browser, phone) |
| Data | folders the module ships as is (a role catalog) | `data` | read by the daemon |

A plugin may have any mix of these. A daemon half has **either** a component **or** a sidecar, never both.

Truth: `rust/crates/infantry-plugin-wit/wit/daemon-plugin.wit` (the ABI),
`rust/crates/infantry-plugin-host/src/manifest.rs` (the `module.json` keys the daemon reads),
`rust/crates/infantry-plugin-host/src/host.rs` (load rules), `app/packages/ui-plugin-api` (the UI half).

## What a daemon half can do

It exports seven functions, and the daemon calls them:

| Export | Called for |
|---|---|
| `start` / `stop` | load and unload (also a hot swap) |
| `call-tool` | a declared `daemon.tools` entry: an MCP tool for agents, the CLI and Lua scripts |
| `handle-frame` | a declared `daemon.frames` entry: a WebSocket frame type the app sends |
| `handle-route` | a declared `daemon.routes` / `daemon.publicRoutes` HTTP route |
| `on-hook` | a declared `daemon.hooks` kind (`frameGate`, `mailGate`, `spawnGate`, ...) |
| `on-event` | an event-bus event matching a pattern it subscribed to |

It imports what the daemon lends it. `log` and `config` are always there; `settings`, `storage`, `events`, `http` and
`frames` only when `daemon.needs` or `daemon.wants` names them. See [Daemon tools](daemon-tools.md).

## Where plugins live

The daemon loads every module under its plugins folder:

```
<plugins>/official/<Module>/module.json
<plugins>/core/<Module>/module.json
```

`<plugins>` is the `INFANTRY_PLUGINS_DIR` environment variable, else the `plugins` folder beside the daemon executable.
The tier (`core`, `official`, `community`) comes from the folder, never from the manifest.

`official/`, `core/` and `community/` are loaded (`TIERS` in `rust/crates/infantry-daemon/src/compose/plugins.rs`).
A third-party plugin goes under `community/`. A community module loads only with the grants the owner lists for it in
`modules.json` in the daemon's state dir (read at start; restart the daemon after editing it):

```json
{ "Acme_Hello": { "allow": ["daemon.code", "daemon.tool", "daemon.frame", "daemon.import.http"] } }
```

`daemon.code` lets its daemon half run at all; `daemon.tool`, `daemon.frame` and `daemon.hook.<kind>` cover what it
declares; `daemon.import.<name>` covers each import in `daemon.needs` (a refused `wants` entry is linked denied);
`daemon.native` allows a native sidecar. A missing grant refuses the module (`missing grant '<g>' in modules.json`).
Whatever it is granted, a community module may never declare routes or public routes, admit tokens, send or declare
owner frames, or adopt core tables, its web UI half is not served yet, and its `account.providers` are ignored
(plugin host rules: `rust/crates/infantry-plugin-host/src/host.rs`).

## Lifecycle

- At start the daemon reads each `module.json`, checks it (see the refusals in [Create](create.md)), instantiates the
  daemon half and calls `start`. An error from `start` marks the module `failed: <error>`.
- Each module's status is `loaded`, `refused: <why>`, `failed: <why>` or `unloaded`, shown in the app's plugin manager (Plugins) and by `infantry health`.
- Loading a module that is already loaded is a **hot swap**: the new version starts and takes over; the old one
  finishes its calls and stops.
- Disable and enable (plugin manager, or the `plugin_admin` ops `disable` and `enable`) keep a module off or on across restarts (the list is
  `plugins-disabled.json` in the state dir).
- A plugin that keeps failing or timing out is switched off for a cool-down by its breaker, then probed again.

## Pages

1. [Create a plugin](create.md): the folder, `module.json`, the Rust crate.
2. [Build and load](build.md): `wasm32-wasip2`, `stage.sh`, `INFANTRY_PLUGINS_DIR`, calling the tool.
3. [Configure](configure.md): `daemon.config` and the `settings` import.
4. [Secrets](secrets.md): the vault, per plugin.
5. [Endpoints](endpoints.md): prod, staging and dev addresses.
6. [UI pages](ui-pages.md): tabs, panels, settings cards.
7. [Daemon tools](daemon-tools.md): MCP tools, frames, routes, hooks, events, storage, HTTP.
8. [Publish](publish.md): sources, install and update.
9. [Official plugins](official-plugins.md): what ships.
