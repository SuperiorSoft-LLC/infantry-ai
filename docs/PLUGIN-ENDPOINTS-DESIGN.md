# PLUGIN-ENDPOINTS: client plugins call their own backend, never the owner's credentials

Ticket 6f0cb0e8. Design only. Owner, 2026-10-26: client side first, daemon later. Builds on PLUGINS-1 (`routes`, `RouteGate`,
`ServiceRegistry`, `PluginDaemon`), 25d2b689 PLUGIN-PLATFORMS-1, S7, P1 step 2, SECRETS-2 (named keystore fields, 1.135.0),
SEC-FIX-1 (owner.token, FrameGate, 1.136.0) and LICENSE-0 (6910b7f, the `routes` row: exact match only, the daemon does not
enforce it, the call carries the window's bearer, and `PluginDaemon` is a public object, so a jar can skip the gate).

## Facts found in the code
- `PluginDaemon` (agenthqUiCore commonMain) is a public `object` that holds the window's base URL and bearer (the access token,
  not owner.token). Any class can call `PluginDaemon.send(...)` and get past `RouteGate`.
- `PluginClassLoader(dir, jar, parent)` uses the host loader as its parent, so a plugin jar can see every host class
  (`PluginDaemon`, `DaemonClient`, `RealDaemonMain`, `OwnerCredential`), plus `System.getenv` and the file system.
- The owner token stays in `ShellConfig.ownerToken` -> `DaemonClient.ownerFor`. Keystore values never leave the daemon:
  clients see only `FieldInfo(name, tail)`.
- Under the demo lock, `ModuleResolver` already sets community modules to LOCKED. `RouteGate` lets a route through only if it is
  listed in `demoLock.allowRoutes`.

## Decisions (D1-D5)
**D1. Endpoints are declared in module.json and called by id, never by URL.** New field:
```json
"endpoints": [{ "id": "api", "url": "https://api.example.com/v1/**", "methods": ["GET","POST"],
                "auth": { "kind": "bearer", "field": "apiKey" } }],
"services": ["endpoints"]
```
- `url` is one origin plus a path pattern. `*` matches one segment and `**` matches the rest of the path. The host must be exact
  (no wildcard hosts). Only `https` is allowed, except `http://localhost`, `127.0.0.1` and `10.0.2.2` for development.
- `auth.kind` is one of `none | bearer | header` (`header` adds `"name"`) and `basic` (uses fields `user` and `password`).
- For each install, the owner may override `url` in modules.json (`modules.<name>.endpoints.api.url`) to point at localhost or a
  staging server. The manifest value is only the default.
- The gate is the pure `EndpointGate.refusal(endpoint, method, path, manifest, tier, config, demoLock)` in agenthqPlugins
  commonMain, beside `RouteGate`, so every platform shares it. It checks these, in order:
  1. The id is declared.
  2. The method is listed.
  3. The path is normalised: percent-decoded once, with no `..`, `//` or `\`.
  4. The resolved URL matches the pattern.
  5. A community module needs `"endpoints"` in its modules.json `allow`, exactly like the other services.
  6. The demo lock rule (D5).
- Redirects are never followed. A 3xx comes back to the plugin as the reply.

**D2. Plugins get a per-module `EndpointAccess` object, and `PluginDaemon` stops being reachable.**
- New service `Services.ENDPOINTS = ServiceKey<EndpointAccess>("endpoints")` with
  `suspend fun call(endpoint: String, method: String, path: String, body: String? = null, headers: Map<String,String> = emptyMap()): EndpointReply`
  (`Ok(status, body, headers)`, `Refused(reason)`, `Failed(reason)`).
- The host builds it per module on a dedicated HttpClient: no default headers, no cookie jar, redirects off, a 30 s timeout and a
  4 MiB reply cap.
- The host removes these headers from the plugin's request: `Authorization`, `Cookie`, `Host`, `Proxy-*` and every
  `X-AgentHQ-*`. After that it adds the credential from D3 itself.
- Fixing the bypass:
  - (a) `PluginDaemon` becomes an `internal class DaemonLink(base, bearer)`. The window creates it, and only the
    `Services.DAEMON` factory closure holds it. No static field holds a token any more.
  - (b) `PluginClassLoader` gets a filtering parent that exposes only `com.superiorsoftllc.agenthq.plugin.api.*`, `kotlin*`,
    `kotlinx*`, `androidx.compose*`/`org.jetbrains.compose*` and `java*`. Every other host class gives a
    `ClassNotFoundException`. Core-tier compiled modules are not affected.
  - (c) Later, on the daemon side: the daemon issues a per-module token that is limited to the module's declared `routes`
    (and enforces them). That closes the "the daemon does not enforce it" gap.
- Honest limit: code running in-process on the JVM can still use reflection or read `owner.token` / `AGENTHQ_TOKEN` from disk or
  the environment. That is the same caveat SEC-FIX-1 records for agents. Steps (a) and (b) close the accidental and easy paths,
  not a determined attacker. The trust boundary for community code stays: owner consent (off until enabled, then `allow`), safe
  mode, and the demo lock.

**D3. A plugin's own credentials live in the keystore as secret `plugin.<module>`, with named fields (SECRETS-2). They are never
in module.json, storage or settings.**
- The owner sets them through the existing Secrets UI (`secret_field_set`, owner connection only). A config row that the
  module's `auth.field` declares tells the owner which fields are missing.
- How the client gets the value (weekend): a new frame `plugin_secret_get {module, field}`.
  - Owner connection only, refused under the daemon demo lock.
  - Limited to names matching `plugin.<module>` where the module is the caller's own. Fields `AGENTHQ_*` are refused.
  - The value lives only inside that module's `EndpointAccess` closure. It is never returned to the plugin, logged or stored.
  - A window without the owner token (a remote window) gets `Refused("credential needs the owner connection")` for endpoints that
    need auth. Endpoints with `none` still work.
- Later: an `endpoint_call` frame, where the daemon itself makes the call using the D1 gate. The value then never leaves the
  daemon, and CORS no longer applies.

**D4. Same contract on every platform; the transport differs.**
- Desktop: as above.
- Web (wasm): the call is a browser `fetch` with `credentials: "omit"`. The developer's server must answer the preflight with
  `Access-Control-Allow-Origin: <app origin>` (never `*` with credentials), `-Allow-Methods`, and
  `-Allow-Headers: Authorization, Content-Type`. A CORS failure becomes `Failed("blocked by CORS: <origin> not allowed by <host>")`.
  Today web loads only compiled and bundled modules (no jar loading), so this affects core-tier code until the daemon proxy (D3,
  later) removes CORS entirely.
- Android: https only (the platform blocks cleartext; the dev exceptions above apply only in debug builds). It loads only
  compiled modules, with the same service and gate.

**D5. Demo lock: plugins from users never run, and endpoints are closed by default.**
- Community modules are already LOCKED by `ModuleResolver`, so their `EndpointAccess` is never built.
- On top of that, under the lock the gate refuses every endpoint call unless the module is core tier and the endpoint id is listed
  in `demoLock.allowEndpoints`.
- `plugin_secret_get` is refused by the daemon's FrameGate, because it is not on `DEMO_LOCK_ALLOWED`.
- An `.ihq` from a user is a community bundle, so it gets the same refusal.

## (6) User-facing docs: a new section "Your own backend in an hour" in plugins/GUIDE.md
1. Run any HTTPS server, or `http://localhost:8080` while developing.
2. Add `endpoints` and `"services": ["endpoints"]` to module.json, as in D1.
3. In code: `services.get(Services.ENDPOINTS)?.call("api", "GET", "/items")`. A null means the service is not declared or not
   allowed.
4. If the endpoint needs a key: in Secrets, create `plugin.<yourModule>` with field `apiKey`. Never put keys in module.json or code.
5. Enable the module in modules.json and add `"endpoints"` to its `allow`. Optionally override `url` there for localhost.
6. Web only: add CORS headers using the snippet provided for Express, FastAPI and Ktor.
7. Debugging: each refusal has a one-line reason in `[plugins]` logs (`endpoint 'api': method DELETE not declared`).

The guide also ships a 30-line `examples/echo-backend` and a matching plugin. Their test is the definition of done for the
hour-long path.

## (7) Ordering
- **Weekend-critical (desktop):**
  - D1: manifest field and `EndpointGate` with unit tests.
  - D2: `EndpointAccess` service, the `PluginDaemon` -> `DaemonLink` fix, and the filtering class loader.
  - D5: the demo-lock rule.
  - D3: `plugin_secret_get` (a small daemon frame, contract bump).
  - The GUIDE section and the echo example.
- **Can follow:**
  - Web fetch transport and CORS messages; Android transport.
  - Per-module scoped daemon tokens with daemon-side route enforcement (D2c).
  - The `endpoint_call` daemon proxy (D3 later).
  - Path parameters in `routes` (LICENSE-0 finding).
  - A per-endpoint rate limit.

## Expensive to reverse
- Calling by endpoint id (not URL) and the `plugin.<module>` secret naming. Both become plugin API contract once third-party
  plugins ship.
- The filtering class loader. It may break community jars that already reach into host classes. Ship it now, while there are none.

## Implemented in PLUGIN-ENDPOINTS-1 (a073edfa): D1, D2, D5
`EndpointGate` (agenthqPlugins host), `JvmEndpointAccess` (desktop), `DaemonLink` (internal; the public `PluginDaemon` is gone), `PluginClassLoader(filtered=true)` for
community jars (allows `plugin.api`, `ui.plugin` except DaemonLink/loader internals, kotlin, kotlinx, compose, java). Core jars/bundles are unfiltered.
Limit: `DaemonLink.window` is still a process-wide holder (internal; hidden from plugin jars by the loader filter), not per-window. The in-process JVM limit
(reflection, owner.token on disk, AGENTHQ_TOKEN) is NOT closed. `auth` is declared and validated; the credential (D3) is not sent yet.
Demo lock: `demoLock.allowEndpoints` + core tier required.
