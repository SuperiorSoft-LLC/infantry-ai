# MCP-TOOLS: agents work well with MCP tools on every harness

Ticket MCP-TOOLS-1 (b3bfc255), 2026-11-14. Design only, no code. Core and SDK seams in this page need the owner's OK
before any ticket below starts (abstract tooling rule).

Owner, 2026-11-14: "we need a way where this app can work well with mcp tools" and "we dont want a hack method for this".

## 0. Why now (what broke)

STORE-SETUP-1 (abc76a76) needed a browser. The owner's Playwright server is a Claude user-scope server
(`~/.claude.json` top-level `mcpServers`). Interactive Claude workers run with `--strict-mcp-config` (CLI-PTY-1, so a
project `.mcp.json` approval dialog cannot hang the PTY), which loads only the `--mcp-config` list: the daemon's
`infantry` server and `INFANTRY_CLAUDE_MCP_CONFIG`. The user's servers were dropped. MCP-USER-1 (99cd8cbf, branch
`ticket/99cd8cbf-...`, commit b13fcd34) fixes that one case inside the Claude plugin (`ClaudeCommand.userScopeMcpConfig`).

The general problem is wider: today Infantry knows about exactly ONE MCP server, its own.

## 1. What exists today (from the code)

| Piece | Where | What it does |
|---|---|---|
| The infantry server entry | `infantry/.../daemon/InfantryMcp.kt` | builds `infantry mcp serve` (launcher, identity env, never the token) |
| One launch handed to harnesses | `HarnessHost.mcpLaunch(sessionEnv): HarnessProcess?` (`infantryHarnessApi/.../HarnessHost.kt:45`) | the only MCP seam in the harness SDK: one process, no name, no list |
| Config merge parts | `ConfigParts(mcp: HarnessProcess?, ...)` (`HarnessApi.kt:206`) | again a single server |
| Link state | `SessionOutput.mcpState(McpState)`, `McpState {CONNECTED, DISCONNECTED, RECONNECTING}`, `MCP_PID_FILE_ENV` | state of the infantry server only; shown as session `mcp` / `mcpChangedAt` (`DaemonApi.kt:319`) |
| Reconnect | `SessionConnection.reconnectMcp()`, protocol `session_mcp_reconnect` | opencode does it in place; Claude falls back to restart + resume |
| Claude | `Infantry_Claude/harness/.../ClaudeCommand.kt`, `ClaudeTtySession.kt:104-107` | `--mcp-config` list + `--strict-mcp-config` (always on interactive, `INFANTRY_CLAUDE_STRICT_MCP` on headless); extra configs only through the env var `INFANTRY_CLAUDE_MCP_CONFIG` |
| opencode | `Infantry_OpenCode/harness/.../OpenCodeConfig.kt`, `OpenCodePermissions.kt`, `OpenCodeServeSession.kt:123-140` | merges `mcp.infantry` into `OPENCODE_CONFIG_CONTENT` (opencode merges that on top of the user's global and project config, so the user's own servers already load); polls `GET /mcp` but reports only `infantry` |
| pi | `Infantry_Pi/harness/.../PiExtensions.kt:25-43` (`infantry-mcp.ts`, env `INFANTRY_PI_MCP`) | pi has no MCP of its own; Infantry's extension bridges the ONE infantry server into pi tools |
| pi experiment | `infantry/.../experiments/pitoolbridge/` | older, OFF-by-default bridge writing `.pi/mcp.json`; superseded by `infantry-mcp.ts` |
| Plugin tools | `DaemonTool` (`DaemonApi.kt:478`), module.json `daemon.tools` | a plugin's tools are served THROUGH the infantry server, so they already reach every harness |
| Health | `Infantry_Health` (`infantry_health`) | "MCP bridge per session": infantry link only |
| Role | `Infantry_Roles/.../Role.kt:48-49` `cards`, `tools` | "shape the briefing; never gate". No MCP field |

So: a plugin that wants to give agents TOOLS already can (a `DaemonTool`). What is missing is everything about MCP
servers that are not ours: the user's own (Playwright, GitHub, a DB), and third-party servers a plugin wants to provide.

## 2. Where each CLI really reads MCP servers

Read from each CLI's documented resolution (no private files copied).

| CLI | Scopes it reads | Official way to add servers per run | Live status source |
|---|---|---|---|
| Claude Code | user: `~/.claude.json` top-level `mcpServers` (or `$CLAUDE_CONFIG_DIR/.claude.json`); local: same file, `projects.<path>.mcpServers`; project: `<repo>/.mcp.json` (asks for approval; `enabledMcpjsonServers` / `enableAllProjectMcpServers` in settings pre-approve); enterprise `managed-mcp.json`; Claude Code plugins; Claude in Chrome (`--chrome` / extension, a built-in, not a config entry) | `--mcp-config <file or json>...`; `--strict-mcp-config` = only those | headless stream-json `system/init` carries `mcp_servers: [{name, status}]` and the tool list (seen in `docs/session-host-probe`); interactive: none on the wire (`/mcp` is a TUI screen) |
| opencode | global `~/.config/opencode/opencode.json[c]`, project `opencode.json[c]` (walks up to the git root), `OPENCODE_CONFIG` (a file), `OPENCODE_CONFIG_CONTENT` (inline, merged last). Key `mcp.<name>`: `{type: local, command[], environment, enabled}` or `{type: remote, url, headers}` | `OPENCODE_CONFIG_CONTENT` (already used); `enabled: false` turns a user server off for the run | `GET /mcp` on `opencode serve`: per-server status (connected / failed / disabled / needs auth) |
| pi | none; tools come from extensions | an extension (`-e file.ts`) | whatever the extension reports |

Consequence: the three CLIs differ only in TRANSLATION. That is the harness plugin's job; the description of a server and
the choice of which ones a session gets are generic.

## 3. The proposal in one picture

```
 harness plugins            daemon core (generic)                       plugins
 ---------------            ---------------------                       -------
 discover()  ---------->  McpCatalog: every server the user could have  <--- module.json daemon.mcpServers
 (each CLI's own              + infantry + plugin-contributed               (e.g. Infantry_Browser: playwright)
  config scopes)                     |
                          McpSelection: role / ticket / settings decide
                          which servers THIS session gets (mechanism in
                          core, values in role data, tickets, settings)
                                     |
 deliver(list) <--------  HarnessHost.mcpServers(sessionEnv): List<McpServerSpec>
 (--mcp-config file /                |
  OPENCODE_CONFIG_CONTENT /  SessionOutput.mcpServers(statuses) ---> session DTO, session details, infantry_health,
  pi extension args)                                                 infantry_mcp_status, notification bus on failure
```

## 4. Core vs plugin split (exact)

### 4.1 SDK: `infantryHarnessApi` (additive minor bump, every harness `module.json` contract updated in the same commit)

```kotlin
/** One MCP server, harness-neutral. Secrets live in env/headers and are never logged or shown. */
data class McpServerSpec(
    val name: String,                       // the key the agent sees: mcp__<name>__* / <name>_*
    val transport: McpTransport,
    val source: McpSource,                  // where it came from (shown to the owner)
    val provides: Set<String> = emptySet(), // capability tags, e.g. "browser"; free strings, plugins add their own
    val localOnly: Boolean = false,         // command/paths exist only on this machine (never sent to a remote runner)
)
sealed interface McpTransport {
    data class Stdio(val command: List<String>, val env: Map<String, String> = emptyMap(), val workingDir: File? = null) : McpTransport
    data class Http(val url: String, val headers: Map<String, String> = emptyMap()) : McpTransport
}
enum class McpSource { INFANTRY, HARNESS_USER, HARNESS_PROJECT, PLUGIN, ROLE }
enum class McpServerState { CONFIGURED, CONNECTED, FAILED, NEEDS_AUTH, DISABLED }
data class McpServerStatus(val name: String, val state: McpServerState, val detail: String? = null, val toolCount: Int? = null)
```

New members (all with defaults, so an older plugin keeps working):

| Member | Purpose | Default |
|---|---|---|
| `Harness.discoverMcp(project: String): List<McpServerSpec>` | the user's servers as THIS CLI would load them for that folder | empty |
| `HarnessHost.mcpServers(sessionEnv): List<McpServerSpec>` | the resolved, selected list for the session, infantry first | `listOfNotNull(mcpLaunch(...))` as one spec |
| `SessionOutput.mcpServers(statuses: List<McpServerStatus>)` | per-server state; generalises `mcpState` | dropped |
| `Harness.handlesUserMcp: Boolean` | true = the CLI loads the user's own servers by itself (opencode merges config), so the host does not re-send them, only turns off the denied ones | false |

`mcpLaunch`, `ConfigParts.mcp` and `mcpState` stay, documented as "the infantry entry of the list" (no removal).

### 4.2 Daemon core (`infantry` / `infantryCore`): mechanisms only

- `McpCatalog`: merges `discoverMcp` of the session's harness, the infantry entry (`InfantryMcp`), and plugin-contributed
  specs. Names collide: infantry wins, then plugin, then the user (same rule MCP-USER-1 and `definesInfantry` use today).
- `McpSelection`: a pure function `(catalog, selection rules) -> list`. Rules come from three open data sources, read
  through existing seams; core names no server:
  - role data (the role file front matter, Roles plugin),
  - the ticket (a field on the board, through the board API),
  - settings (`SettingScopes`, global / project).
- Secret hygiene: the generated per-session config goes in the state dir (`stateDir`, not the worktree, not `java.io.tmpdir`
  as today), owner-only permissions, deleted when the session is removed; specs are never written to transcripts, logs
  or the wire (the DTO carries name, source, state, detail only).
- Protocol: the session DTO gets `mcpServers: List<{name, source, state, detail, provides}>` (additive).
- Module.json: a daemon half may declare `daemon.mcpServers` (name, transport template, provides). The host starts nothing;
  it only adds the spec to the catalog. `PluginToolsDeclaredGuardTest` gets a sibling check.

### 4.3 Harness plugins: translation and discovery

| Plugin | discover | deliver | status |
|---|---|---|---|
| Infantry_Claude | user scope + local scope for the project (`~/.claude.json`, `CLAUDE_CONFIG_DIR`); project `.mcp.json` only for names already approved in the user's settings. Absorbs MCP-USER-1's `userScopeMcpConfig` | ONE generated `--mcp-config` file; `--strict-mcp-config` stays (no approval dialog) | headless: `system/init.mcp_servers`; interactive: `CONFIGURED` + infantry pid file + tool-error detection (a `ToolResult` error on `mcp__<name>__*` marks it FAILED) |
| Infantry_OpenCode | global + project opencode config | `handlesUserMcp = true`: send only infantry + plugin servers, plus `enabled: false` for user servers the selection denies | widen the existing `GET /mcp` poll to every server |
| Infantry_Pi | none (pi has no config) | generalise `infantry-mcp.ts` from one server (`INFANTRY_PI_MCP` = one `{command, env}`) to a list | the extension reports each server's handshake result back |

The pitoolbridge experiment is retired by this (its own README lists the removal steps).

### 4.4 Feature plugins

- Infantry_Roles: role field `mcp` (see 5).
- Infantry_Board: ticket field `mcp.require` (capability tags or names).
- Infantry_Agents (daemon half): agent tool `infantry_mcp_status` ("what MCP servers and tools do I have, and which failed").
- Infantry_Health: per-session server table instead of the infantry link alone.
- A new official plugin Infantry_Browser (section 7).
- CLI: `infantry mcp list [--session id | --role r --project p]` prints the resolved set with source and state (the "inventory").

## 5. Control: who gets which server

Data, never code (owner rule: preferences live in roles and data):

```yaml
# role front matter (Roles plugin)
mcp:
  user: all            # all | none | allow-list below
  allow: [playwright]  # only when user: list
  deny: [github]
  require: [browser]   # names or capability tags; missing = start refused (section 6)
```

- Ticket: `mcp.require: [browser]` adds to the role's require for that run (STORE-SETUP-1 would carry it).
- Agent override: the same block on an agent registry entry, for a one-off.
- Settings: `mcp.userServers` (global/project: `all` | `none`), the default when a role says nothing.
- Order: ticket require > agent > role > project setting > global setting. Deny beats allow; require beats deny (and
  logs why).
- Cost: every server adds tool definitions to the model's context. Claude defers MCP tools (tool search), so the cost there is
  small; opencode and pi load every definition each turn. Rule 5 says a cost trade-off defaults to the cheapest choice,
  which would be `none`; the owner's own words point at `all`. That is open question Q1.

## 6. Failure: loud, never a silent worker

1. Preflight at `ticket start` / session start: a `require` entry that the catalog cannot satisfy refuses the start with one
   line ("ticket needs MCP capability `browser`; no server provides it. Enable Infantry_Browser or add one to your Claude
   config") and posts it on the notification bus (the existing bus, no new UI).
2. Runtime: a required server that reports FAILED / NEEDS_AUTH posts a bus notice to the owner AND a chat note to the agent
   ("MCP server playwright failed: <detail>; ask, do not work around it"). An optional server that fails is shown in session
   details only.
3. The agent can always check: `infantry_mcp_status`. Role briefings for roles that require a server name the tool.
4. Recovery reuses `session_mcp_reconnect` (now per server where the CLI allows it: opencode connect/disconnect per name;
   Claude restart + resume).

## 7. Browser

Two real paths, for two different jobs:

| | Playwright MCP (`@playwright/mcp`) | Claude in Chrome |
|---|---|---|
| Harnesses | all (a stdio server) | Claude only (extension + `--chrome`) |
| Whose browser | its own browser and profile | the owner's real Chrome, his sign-ins |
| Runners / Docker | yes (headless) | no |
| Risk | isolated | acts as the owner; a worker click once hit his Chrome (no-synthetic-input rule) |
| Use for | worker testing (wasm app, demo QA), scraping docs | owner-attended tasks that need his logins (Store setup, dashboards) |

Recommendation:
- Infantry_Browser, an official plugin, contributes one Playwright spec with `provides: [browser]`, an isolated profile per
  session under the state dir, settings for headless/channel, installed through `Downloads` with progress on first use.
  Default browser path for every worker on every harness.
- Claude in Chrome stays opt-in: the Claude plugin exposes it as a discovered capability `browser.owner` only when the
  extension is set up, and only a ticket that names `browser.owner` (plus the owner present) gets `--chrome`.
- The owner's own user-scope Playwright keeps working (it is a `HARNESS_USER` server); when both exist, the plugin one wins
  for capability `browser` unless the role allow-lists the user's.

## 8. Tickets (small, ordered)

| # | Id | Layer | What | Depends |
|---|---|---|---|---|
| 1 | MCP-SPEC-1 | SDK + core (owner OK) | `McpServerSpec` / status types, `HarnessHost.mcpServers`, `SessionOutput.mcpServers`, contract bump in every harness module.json, docs `Harnesses.md` | - |
| 2 | MCP-CATALOG-1 | core | `McpCatalog` + `McpSelection` (pure, settings `mcp.userServers`), state-dir config files, `infantry mcp list`, session DTO field | 1 |
| 3 | MCP-CLAUDE-1 | Claude plugin | `discoverMcp` (fold in MCP-USER-1), deliver one file under strict, headless init status, interactive tool-error status | 1, 2 |
| 4 | MCP-OPENCODE-1 | opencode plugin | `handlesUserMcp`, `enabled:false` for denied, all-server `/mcp` status | 1, 2 |
| 5 | MCP-PI-1 | pi plugin | `infantry-mcp.ts` takes a list; per-server status; delete pitoolbridge experiment | 1, 2 |
| 6 | MCP-STATUS-1 | Agents, Health, UI | `infantry_mcp_status`, health table, session details rows (reuse the existing session details card) | 2 |
| 7 | MCP-SELECT-1 | Roles, Board | role `mcp` block, ticket `mcp.require`, agent override | 2 |
| 8 | MCP-FAIL-1 | core + Notify | preflight refusal, runtime notices on the bus, agent chat note | 6, 7 |
| 9 | MCP-PLUGIN-1 | core | module.json `daemon.mcpServers` + guard | 2 |
| 10 | BROWSER-1 | new plugin | Infantry_Browser (Playwright, isolated profile, Downloads install, runner-ready) | 9 |
| 11 | BROWSER-OWNER-1 | Claude plugin | Claude in Chrome as opt-in `browser.owner` | 3, 7 |

Each is one worker ticket with named test classes; 3, 4, 5 can run in parallel after 2.

## 9. Open questions for the owner

1. **Default for the user's own servers**: `all` (works like his own CLI, more tokens on opencode/pi) or `none` with per-role
   allow-lists (cheapest, rule 5). Recommendation: `all` on Claude (deferred tools make it cheap), `none` on opencode and
   pi, as one setting per harness.
2. **Interactive Claude status**: accept "configured" plus tool-error detection, or let the harness probe each stdio server
   at start (an `initialize` handshake, one short child process per server, setting `mcp.probeOnStart`, default off)?
   Recommendation: no probe; rely on errors, add the probe later if a silent failure is seen.
3. **Project `.mcp.json`** (a repo's own servers): load only names the user already approved in Claude settings, or never?
   Recommendation: approved names only.
4. **Claude in Chrome for workers**: allowed with a ticket opt-in while the owner is present, or never for workers (owner's
   own Assistant only)? Recommendation: ticket opt-in, owner present.
5. **Remote runners**: a user's local servers point at this PC (`C:\...cmd`). Proposal: `localOnly` servers are never sent;
   runners get plugin-contributed servers only (Playwright in the image). OK?
