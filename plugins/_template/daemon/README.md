# The template's daemon half

Optional. A module that only adds UI needs no `daemon/` folder; delete it. A daemon half runs **inside the daemon**: tools
agents can call, frames, HTTP routes and hooks. The design is in [docs/DAEMON-PLUGINS.md](../../../docs/DAEMON-PLUGINS.md); the
reference plugin with a tool, a route and a hook is [plugins/examples/Acme_DaemonHello](../../examples/Acme_DaemonHello).  A second one, with a policy lookup and a frameGate hook, is [plugins/examples/Acme_PolicyHook](../../examples/Acme_PolicyHook).

## Make it yours

1. Copy the module (this folder comes with it) to `plugins/community/<Vendor_Module>/`.
2. Rename the package `com.example.starter.daemon`; set `daemon.entry` in `module.json` to the new class.
3. In `daemon/build.gradle.kts`, set `archiveFileName` to `<Vendor_Module>-daemon.jar` and `daemon.jar` in `module.json` to
   `daemon/build/libs/<Vendor_Module>-daemon.jar`.
4. Name your tools with your vendor prefix (`acme_...`; a community tool without it is refused) and declare each under
   `daemon.tools` with its policy action (`{ "name", "action", "kind": "read|write|destructive|spawn", "description" }`).
5. Build: `./gradlew :daemon-plugin-community-<Vendor_Module>:jar`.
6. Grant it in `plugins/modules.json`: `"<Vendor_Module>": { "enabled": true, "allow": ["daemon.code", "daemon.tool"] }`,
   plus `daemon.frame`, `daemon.route` (a relative route), `daemon.hook.<kind>`, `daemon.env:<NAME>`, `daemon.secret:<name>` for what you declare. Restart
   the daemon.

The tool then appears in `infantry mcp serve`'s `tools/list`, in the `plugin_tools` frame, and `GET /plugins` shows the
module's `daemon` status (`loaded`, `refused: <why>` or `failed: <why>`) and its `tools`.

## The `daemon` keys of module.json

| key | what it means |
|---|---|
| `entry` | the class implementing `DaemonPlugin` (no-arg constructor) |
| `jar` | the built jar, inside the module folder |
| `apiAtLeast` | the lowest `DaemonApi.VERSION` the plugin needs; a newer requirement than the daemon's is refused |
| `tools` | every tool the plugin registers, with its policy `action` and `kind` |
| `frames` | client frame types the plugin answers (`{ "type", "action", "kind", "ownerOnly" }`; community: never owner-only) |
| `hooks` | `frameGate`, `httpGate`, `mailGate`, `spawnGate`, `ready`, `turns`, `sessions`, `policyProvider`, `seats` (`admission`: official only) |
| `routes` / `publicRoutes` | absolute routes (`GET /x`), official only; a relative `api.routes.handle("GET", "x")` needs none and answers at `/plugins/<id>/x` |
| `env` / `secrets` | the environment variables / keystore secrets `api.env` / `api.secret` may read |
| `limits` | `callTimeoutMs`, `toolTimeoutMs`, `cpuMsPerCall`, `routeRequestsPerMinute`, `memoryMbHint` |

Anything registered but not declared is refused. A plugin that throws or overruns its limits is disabled (its tools and
routes go away; its gates fail closed) and the daemon keeps running.
