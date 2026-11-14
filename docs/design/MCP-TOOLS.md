# MCP-TOOLS: agents work well with MCP tools on every harness

Ticket MCP-TOOLS-1 (b3bfc255), revised by MCP-TOOLS-1b (3b596521), 2026-11-14. Design only, no code. Core and SDK seams
in this page need the owner's OK before any ticket below starts (abstract tooling rule).

Owner, 2026-11-14:
- "we need a way where this app can work well with mcp tools"
- "we dont want a hack method for this"
- "Yeah we need a way to properly manage these tools. shouldnt be barried inside. user should have control of management"

Two hard rules follow, and every section below obeys them:

1. **No private-file parsing.** Infantry never reads (or writes) a CLI's own config or state files (`~/.claude.json`,
   `CLAUDE_CONFIG_DIR`, `~/.config/opencode/*`, `~/.pi/agent/mcp.json`). It learns about a CLI's servers only through that
   CLI's official interface (its `mcp` commands, its documented server API) and hands servers over only through official
   flags and settings. Where a CLI has no official path, this page says so and picks the least-invasive official option.
2. **The user manages MCP in the open.** One MCP Tools screen lists every server from every source and lets the user add,
   edit, remove, enable, disable, test and assign them. The CLI, the agent tool and the screen are three faces of one
   daemon backend.

## 0. Why now (what broke, and what is already fixed)

STORE-SETUP-1 (abc76a76) needed a browser. The owner's Playwright server is a Claude user-scope server. Interactive Claude
workers ran with `--strict-mcp-config` (CLI-PTY-1), which loads only the `--mcp-config` list, so those servers were dropped.

- MCP-USER-1 (99cd8cbf) fixed it by reading `~/.claude.json` inside the Claude plugin. The owner called that a hack.
- MCP-USER-2 (bfca1ac1, e85e79153) removed that read: interactive Claude now runs **non-strict** (`INFANTRY_CLAUDE_STRICT_MCP=1`
  turns strict back on) and loads its own user / local / project scopes; our `--settings` file carries
  `enableAllProjectMcpServers: true` so a project `.mcp.json` approval dialog cannot hang the PTY.

So the user's own servers reach Claude workers again, the official way. What is still missing is everything else: Infantry
knows about exactly ONE MCP server (its own), the user cannot see or steer what an agent gets, and a failed server is silent.

**Side effect of MCP-USER-2 to fix early (seen while writing this page).** `claude mcp list` run in this worktree shows five
servers from `C:\Users\<owner>\.mcp.json` (an unrelated app's, "AgentsRoom-*") as "Pending approval": Claude walks up from the
worktree to the home folder's `.mcp.json`. With `enableAllProjectMcpServers: true`, every interactive Claude worker approves
and starts all five. Not yet seen running (no such process at the time of writing, the live daemon may predate e85e79153), so
this is a risk to verify, not a filed bug. The fix is in Claude's own settings: replace the blanket flag with
`enabledMcpjsonServers: [names]` (the official per-name approval) driven by the MCP Tools screen (ticket MCP-APPROVE-1, Q3).

## 1. What exists today (from the code)

| Piece | Where | What it does |
|---|---|---|
| The infantry server entry | `infantry/.../daemon/InfantryMcp.kt` | builds `infantry mcp serve` (launcher, identity env, never the token) |
| One launch handed to harnesses | `HarnessHost.mcpLaunch(sessionEnv): HarnessProcess?` (`infantryHarnessApi/.../HarnessHost.kt:45`) | the only MCP seam in the harness SDK: one process, no name, no list |
| Config merge parts | `ConfigParts(mcp: HarnessProcess?, ...)` (`HarnessApi.kt:206`) | again a single server |
| Link state | `SessionOutput.mcpState(McpState)`, `McpState {CONNECTED, DISCONNECTED, RECONNECTING}`, `MCP_PID_FILE_ENV` | state of the infantry server only; session `mcp` / `mcpChangedAt` (`DaemonApi.kt:319`) |
| Reconnect | `SessionConnection.reconnectMcp()`, protocol `session_mcp_reconnect` | opencode in place; Claude restart + resume |
| Claude | `Infantry_Claude/harness/.../ClaudeCommand.kt`, `ClaudeTtySession.kt:103-110` | `--mcp-config` (infantry + `INFANTRY_CLAUDE_MCP_CONFIG`), non-strict by default (MCP-USER-2), `enableAllProjectMcpServers` in `--settings` |
| opencode | `Infantry_OpenCode/harness/.../OpenCodeConfig.kt`, `OpenCodeServeSession.kt:123-140` | merges `mcp.infantry` into `OPENCODE_CONFIG_CONTENT` (opencode layers it over the user's own config, so the user's servers load); polls `GET /mcp` but reports only `infantry` |
| pi | `Infantry_Pi/harness/.../PiExtensions.kt:25-43` (`infantry-mcp.ts`, env `INFANTRY_PI_MCP`) | an extension bridges the ONE infantry server into pi tools. Written when pi had no MCP; pi 1.3.0 now has its own (section 2) |
| pi experiment | `infantry/.../experiments/pitoolbridge/` | older, OFF-by-default bridge writing `.pi/mcp.json`; superseded |
| Plugin tools | `DaemonTool` (`DaemonApi.kt:478`), module.json `daemon.tools` | served THROUGH the infantry server, so they already reach every harness |
| Health | `Infantry_Health` (`infantry_health`) | "MCP bridge per session": infantry link only |
| Role | `Infantry_Roles/.../Role.kt:48-49` `cards`, `tools` | "shape the briefing; never gate". No MCP field |
| Child-process lists | `ModelCatalogCache` + `HarnessModelCatalogs` (`infantryCore/.../provider/`) | the seam for "a list that costs a child process": last good answer, refresh behind it |
| Management-style UI | `ModulesSection.kt` (`ModuleCard`: status, toggle, edit, remove), Secrets section in `ConfigDialog.kt` (`SecretEntryCard` with scope badge, `NewSecretCard`), `AhqAlertDialog`, `CompactNavRow` | the cards the MCP Tools screen reuses (section 5) |

## 2. Each CLI's official MCP interface (checked 2026-11-14 on this PC)

Versions: Claude Code 2.1.325, opencode 1.18.35, pi 1.3.0. "Official" = a documented command, flag, setting or server API.

| | Claude Code | opencode | pi |
|---|---|---|---|
| **List** | `claude mcp list`: text, one line per server `name: target - status` (Connected / Failed / Needs auth / Pending approval). Health-checks every server, so it starts each one: seconds, not milliseconds. No `--json` | `opencode mcp list`: text with ANSI, name + status + command. No `--json`. On a running `opencode serve`: `GET /mcp` (JSON, per-server status) | `pi mcp list --json`: JSON `{servers, errors}` with state, tools, errors. Exits 1 on a failure |
| **Detail** | `claude mcp get <name>`: `Scope`, `Status`, `Type`, `Command`/`URL`, `Args`, `Environment`, and the exact remove command. Prints env values (mask them) | none beyond `list` | in `list --json` |
| **Add** | `claude mcp add [-s local\|user\|project] [-t stdio\|sse\|http] [-e K=V] [-H ...] <name> <cmd\|url> [args]`; `claude mcp add-json [-s] <name> <json>` | `opencode mcp add [name] [--url] [--env]`: partly interactive prompts | `pi mcp add <name> [-l] [--env] [--cwd] [--header] [--url] [--exposure] [--description] -- <cmd>` ("add or replace") |
| **Remove** | `claude mcp remove [-s scope] <name>` | **none** | `pi mcp remove <name> [-l]` |
| **Edit** | none: `remove` + `add-json` in the same scope (`get` gives the scope) | **none** | `add` replaces |
| **Enable / disable** | no command (the `/mcp` TUI toggles per project). Per run: `--disallowedTools "mcp__<name>"` hides its tools (the process still starts). Project servers: `enabledMcpjsonServers` / `disabledMcpjsonServers` settings | per run: `mcp.<name>.enabled: false` in `OPENCODE_CONFIG_CONTENT` (official, server not started) | per run: `--exclude-tools "mcp__<name>__*"`; all off: `--no-mcp` |
| **Auth** | `claude mcp login/logout <name>` | `opencode mcp auth/logout <name>` | `pi mcp login/logout <name>` |
| **Hand over a server for one run** | `--mcp-config <file\|json>` (additive while non-strict) | `OPENCODE_CONFIG_CONTENT` (already used) | no flag; an extension (`-e`) can add tools (today's `infantry-mcp.ts`) |
| **Live status in a session** | headless: `system/init.mcp_servers [{name, status}]`; interactive: nothing on the wire | `GET /mcp` | the extension reports its own servers; native servers: none on the wire |

Gaps, and the least-invasive official option for each:

- **Claude list is text and slow.** Parse the documented line format with captured fixtures in tests; a parse failure is
  loud (the screen shows "could not read Claude's list" plus the raw output). Run it through the `ModelCatalogCache`
  pattern (serve the last good list, refresh behind it, bounds in settings), never on every session start.
- **opencode has no remove/edit command and an interactive add.** Infantry shows opencode's servers read-only with the
  hint "managed in opencode's config"; enable/disable per run still works (`enabled: false`). Add uses
  `opencode mcp add` only when its flags cover the server, else read-only. Editing opencode's own config file would break
  rule 1 even though the file is documented; that is Q2.
- **Claude has no enable/disable command.** Infantry's "disabled" for a Claude user/local server means "hidden from
  Infantry sessions" (`--disallowedTools`), never a change to the user's own Claude. The screen says so on the toggle.
- **pi and Claude give an interactive session no per-server status.** Status there comes from tool errors (section 7).

Consequence (unchanged from MCP-TOOLS-1): the CLIs differ only in TRANSLATION, which is the harness plugin's job. The
description of a server, where it is shown and who gets it are generic.

## 3. The proposal in one picture

```
 harness plugins              daemon (generic mechanism)                    plugins / user
 ---------------              --------------------------                    --------------
 HarnessMcp (per CLI,     ->  McpRegistry: every server from every source <- Infantry-owned servers (Infantry data,
  official commands only):     (cached list, ModelCatalogCache pattern)        added in the screen / CLI / tool)
  list / get / add /                     |                                  <- module.json daemon.mcpServers
  remove / test                McpSelection: which servers THIS session        (e.g. Infantry_Browser: playwright)
                               gets (role / agent / ticket / settings data)
 deliver(list, deny)  <-                 |
  (--mcp-config + --disallowedTools /    HarnessHost.mcpServers(sessionEnv)
   OPENCODE_CONFIG_CONTENT /             |
   pi extension + --exclude-tools)  SessionOutput.mcpServers(statuses) -> session DTO, infantry_health, bus on failure
                                         |
                    ONE backend:  protocol mcp_* frames  <-  MCP Tools screen (Infantry_Mcp UI half)
                                                         <-  infantry mcp list|get|add|edit|remove|enable|disable|test|assign
                                                         <-  agent tool infantry_mcp (list/status; changes need owner approval)
```

## 4. Core vs plugin split (exact)

### 4.1 SDK: `infantryHarnessApi` (additive minor bump, every harness `module.json` contract updated in the same commit)

```kotlin
/** One MCP server, harness-neutral. Secrets live in env/headers and are never logged, shown or put on the wire. */
data class McpServerSpec(
    val name: String,                       // the key the agent sees: mcp__<name>__* / <name>_*
    val transport: McpTransport,
    val source: McpSource,                  // where it came from (shown to the owner)
    val scope: String? = null,              // the CLI's own scope label, as its CLI prints it ("user", "local", "project")
    val provides: Set<String> = emptySet(), // capability tags, e.g. "browser"; free strings, plugins add their own
    val localOnly: Boolean = false,         // command/paths exist only on this machine (never sent to a remote runner)
)
sealed interface McpTransport {
    data class Stdio(val command: List<String>, val env: Map<String, String> = emptyMap(), val workingDir: String? = null) : McpTransport
    data class Http(val url: String, val headers: Map<String, String> = emptyMap()) : McpTransport
}
enum class McpSource { INFANTRY, INFANTRY_USER, HARNESS, PLUGIN }   // INFANTRY_USER = added in Infantry's own screen
enum class McpServerState { CONFIGURED, CONNECTED, FAILED, NEEDS_AUTH, PENDING_APPROVAL, DISABLED, UNKNOWN }
data class McpServerStatus(val name: String, val state: McpServerState, val detail: String? = null, val toolCount: Int? = null)

/** A CLI's own MCP servers, reached ONLY through that CLI's official commands or API. Every member is optional. */
interface HarnessMcp {
    val canAdd: Boolean; val canRemove: Boolean; val canEdit: Boolean   // the screen greys out what the CLI cannot do
    val scopes: List<String>                                             // e.g. Claude: local, user, project
    suspend fun list(project: String): List<Pair<McpServerSpec, McpServerStatus>>
    suspend fun add(project: String, spec: McpServerSpec, scope: String): Result<Unit>
    suspend fun remove(project: String, name: String, scope: String?): Result<Unit>
    suspend fun test(project: String, name: String): McpServerStatus     // Claude: `mcp get`; pi: `mcp list --json`
}
```

New members (all with defaults, so an older plugin keeps working):

| Member | Purpose | Default |
|---|---|---|
| `Harness.mcp: HarnessMcp?` | the CLI's own servers, through its official commands (run via `HarnessHost` / `BoundedCommand`) | null = "this CLI's servers are not visible" |
| `Harness.loadsUserMcp: Boolean` | true = the CLI loads the user's own servers itself (all three do today), so the host never re-sends them, only denies | false |
| `HarnessHost.mcpServers(sessionEnv): McpPlan` | for a session: `add` (Infantry + Infantry-owned + plugin specs) and `deny` (names the selection turns off) | `add = listOfNotNull(mcpLaunch(...))`, empty deny |
| `SessionOutput.mcpServers(statuses: List<McpServerStatus>)` | per-server state; generalises `mcpState` | dropped |

`mcpLaunch`, `ConfigParts.mcp` and `mcpState` stay, documented as "the infantry entry of the plan" (no removal).

### 4.2 Daemon core (`infantry` / `infantryCore`): mechanisms only

- `McpRegistry`: merges the session harness's `HarnessMcp.list` (cached per harness + project through the
  `ModelCatalogCache` pattern; new `limits.mcp.*` settings for refresh age and timeout), the infantry entry
  (`InfantryMcp`), Infantry-owned servers (below) and plugin-contributed specs. Name collision: infantry wins, then
  Infantry-owned, then plugin, then the CLI's own (shown as "shadowed" in the screen, never silently dropped).
- Infantry-owned servers: stored in Infantry's own data (the settings store, global or project scope through
  `SettingScopes`; secrets in env/headers go to the existing Secrets store and are referenced by name, never inline).
- `McpSelection`: a pure function `(registry, rules) -> McpPlan`. Rules are open data read through existing seams; core
  names no server: role data (Roles plugin), agent registry entry, the ticket (board API), settings.
- Secret hygiene: the per-session `--mcp-config` file goes in the state dir (`stateDir`, not the worktree, not
  `java.io.tmpdir` as today), owner-only, deleted with the session. Specs never go to transcripts, logs or the wire; the
  DTO carries name, source, scope, state, detail, provides, toolCount, and env/header KEYS only. Values that a CLI prints
  (`claude mcp get` shows env) are masked before they leave the harness plugin.
- Protocol (additive): session DTO `mcpServers`; frames `mcp_list`, `mcp_get`, `mcp_add`, `mcp_update`, `mcp_remove`,
  `mcp_set_enabled`, `mcp_test`, `mcp_assign`, and the event `mcp_changed`. Each frame names a target (`infantry` or a
  harness id) and a scope; the daemon routes a CLI target to that harness's `HarnessMcp`.
- Module.json: a daemon half may declare `daemon.mcpServers` (name, transport template, provides). The host starts nothing;
  it only adds the spec to the registry. `PluginToolsDeclaredGuardTest` gets a sibling check.

### 4.3 Harness plugins: official discovery, official delivery

| Plugin | `HarnessMcp` (management) | Delivery for a session | Status |
|---|---|---|---|
| Infantry_Claude | `list` = `claude mcp list` (parsed, cached); `get`/`test` = `claude mcp get <name>` (scope, type, command, masked env); `add` = `claude mcp add-json -s <scope>`; `remove` = `claude mcp remove -s <scope>`; edit = remove + add-json in the same scope. Scopes: local, user, project. No file read | non-strict (as MCP-USER-2 left it). Infantry + Infantry-owned + plugin specs in ONE `--mcp-config` file from the state dir. Denied CLI servers: `--disallowedTools "mcp__<name>"`. Project `.mcp.json`: `enabledMcpjsonServers: [approved names]` in our `--settings`, replacing the blanket `enableAllProjectMcpServers` (MCP-APPROVE-1) | headless: `system/init.mcp_servers`; interactive: `CONFIGURED`, infantry pid file, tool-error detection; the screen's Test runs `claude mcp get` |
| Infantry_OpenCode | `list` = `GET /mcp` when a serve process is up, else `opencode mcp list` (parsed, ANSI stripped); `add` = `opencode mcp add` only when flags cover the server; `canRemove = canEdit = false` (no official command; Q2) | `OPENCODE_CONFIG_CONTENT`: Infantry + plugin specs, plus `mcp.<name>.enabled: false` for denied ones | widen the existing `GET /mcp` poll to every server |
| Infantry_Pi | `list`/`test` = `pi mcp list --json`; `add` = `pi mcp add [-l]`; `remove` = `pi mcp remove [-l]`; scopes: global, project (`-l`). Needs pi >= the version with `pi mcp` (detected by `pi mcp --help`, else `mcp = null`) | the user's servers load natively (pi reads them itself). Infantry + plugin specs keep going through `infantry-mcp.ts`, generalised from one server to a list (pi has no per-run config flag, and writing `.pi/mcp.json` into the worktree would dirty the branch). Denied: `--exclude-tools "mcp__<name>__*"`; `none`: `--no-mcp` (Q1) | the extension reports each of its servers; native ones from tool errors |

The pitoolbridge experiment is retired by MCP-PI-1 (its README lists the removal steps).

### 4.4 Feature plugins

- **Infantry_Mcp (new official plugin)**: UI half = the MCP Tools screen (section 5); daemon half = the `infantry mcp ...`
  CLI verbs and the agent tool `infantry_mcp`, both calling the same protocol frames as the screen.
- Infantry_Roles: role field `mcp` (section 6). Infantry_Board: ticket field `mcp`. Infantry_Agents: agent override.
- Infantry_Health: per-session server table instead of the infantry link alone.
- Infantry_Browser (new, section 8).

## 5. The MCP Tools screen (user control)

A settings-style tab contributed by Infantry_Mcp (`UiPlugin.register`, its own `module.json`; core gains no screen).
No new component: it reuses the Plugins list cards and the Secrets entry cards (UI rule 10, owner rule "reuse, cards and
borders, one modal").

**Desktop** (one column of bordered cards, grouped by source; the group header carries the add button):

```
 MCP Tools                                            [ Refresh ]  last read 12s ago
 ── Infantry ──────────────────────────────────────────────────────────── [+ Add]
 [card] infantry        built in    ● connected   46 tools    (cannot be removed)
 [card] github-ro       Infantry    ● connected   12 tools    [Test] [Edit] [Remove]  (on)
 ── Claude Code ──────────────────────────────── scopes: local user project [+ Add]
 [card] playwright      user        ● connected   21 tools    [Test] [Edit] [Remove]  (on)
 [card] AgentsRoom-MCP  project     ⏸ pending approval        [Approve] [Remove]      (off)
 ── opencode ─────────────────────────────────── managed in opencode's config
 [card] projectmem      global      ● connected                [Test]                 (on)
 ── pi ───────────────────────────────────────────────────────────────── [+ Add]
    (no servers)
 ── Plugins ──────────────────────────────────────────────────────────────────────
 [card] browser         Infantry_Browser  ○ configured  provides: browser            (on)
```

- **Card** (same shape as `ModuleCard`): name, source + scope badge (as on `SecretEntryCard`), state dot and detail
  (the error text for FAILED / NEEDS_AUTH), tool count when known, the toggle, actions the source allows (greyed with a
  tooltip "opencode has no remove command" via `IconActionTooltipBox`). Expanding a card shows transport, command or URL,
  env and header keys (values masked), "Used by" (roles, agents, tickets) and "Shadowed by" on a name clash.
- **Add / Edit**: one `AhqAlertDialog` form: target (Infantry or a CLI that `canAdd`), scope (that target's `scopes`),
  transport (stdio / http), command + args or URL, env and headers (a value can point at a Secrets entry), provides tags.
  Saving a CLI target runs that CLI's official command and shows its output on failure.
- **Remove**: `AhqAlertDialog` confirm that names the command it will run (`claude mcp remove playwright -s user`).
- **Enable / disable**: for Infantry-owned and plugin servers it is real (not delivered). For a CLI's own server it means
  "Infantry sessions do not get it" (deny), the toggle's label says so; the CLI's own config is not changed.
- **Approve** (Claude project `.mcp.json` servers): adds the name to Infantry's approved list, which feeds
  `enabledMcpjsonServers` (MCP-APPROVE-1).
- **Test**: `mcp_test` (the CLI's `get` / `list --json`; for Infantry-owned servers an `initialize` handshake through
  `ProcessRunner`) and shows the state and tool count, with a `ProgressIndicator` while it runs.
- **Assign**: a second tab "Assignments": per role (and agent), choose `user: all | none | list`, allow / deny / require;
  the same block the role file holds (section 6). A ticket's `mcp` field is edited on the ticket itself (Board plugin) and
  shows here read-only.
- **Compact / Android**: the same cards in one column, actions in the card's `InfantryDropdownMenu`, the form full-width;
  "Assignments" opens as a sub-page with `CompactNavRow` back. Web the same as compact (wasm compiles the plugin in, D6/D9).
- Changes broadcast `mcp_changed`; every open client refreshes. Notices (a failed add, a server that failed in a session)
  go on the notification bus, never a toast of the plugin's own (NotificationGuardTest).

**CLI** (`infantry mcp`, the same frames): `list [--target t] [--project p] [--session id]`, `get <name>`,
`add <name> --target t --scope s (-- <cmd...> | --url u) [--env K=V] [--header K=V] [--provides tag]`, `edit`, `remove`,
`enable|disable <name>`, `test <name>`, `assign --role r (--allow|--deny|--require) <name|tag>`.

**Agent tool** `infantry_mcp`: `list` / `status` for the agent's own session ("what servers and tools do I have, which
failed"). Add / remove / assign from an agent go through the existing approval gate (the owner says yes), so an agent
cannot quietly widen its own tools.

## 6. Control: who gets which server

Data, never code (owner rule: preferences live in roles and data):

```yaml
# role front matter (Roles plugin), also editable in the screen's Assignments tab
mcp:
  user: all            # all | none | list : the CLI's own servers
  allow: [playwright]  # only when user: list
  deny: [github]
  require: [browser]   # names or capability tags; missing = start refused (section 7)
```

- Ticket: `mcp.require: [browser]` adds to the role's require for that run (STORE-SETUP-1 would carry it).
- Agent override: the same block on an agent registry entry.
- Settings: `mcp.userServers` (global / project, per harness: `all` | `none`), the default when a role says nothing.
- Order: ticket require > agent > role > project setting > global setting. Deny beats allow; require beats deny (and logs why).
- Cost: every server adds tool definitions to the model's context. Claude defers MCP tools (tool search) and pi defaults
  to `codemode` exposure, so the cost there is small; opencode loads every definition each turn. Rule 5 says a cost
  trade-off defaults to the cheapest choice. That is Q1.

## 7. Failure: loud, never a silent worker

1. Preflight at `ticket start` / session start: a `require` entry nothing satisfies refuses the start with one line
   ("ticket needs MCP capability `browser`; no server provides it. Add one in MCP Tools or enable Infantry_Browser") and
   posts it on the notification bus.
2. Runtime: a required server that reports FAILED / NEEDS_AUTH posts a bus notice to the owner (it opens the MCP Tools
   card) AND a chat note to the agent ("MCP server playwright failed: <detail>; ask, do not work around it"). An optional
   server that fails shows on its card and in session details only.
3. Interactive Claude and native pi servers give no status on the wire: a `ToolResult` error on `mcp__<name>__*` marks that
   server FAILED for the session.
4. The agent can always check: `infantry_mcp status`. Recovery reuses `session_mcp_reconnect` (per server where the CLI
   allows: opencode per name; Claude restart + resume).

## 8. Browser

| | Playwright MCP (`@playwright/mcp`) | Claude in Chrome |
|---|---|---|
| Harnesses | all (a stdio server) | Claude only (extension + `--chrome`) |
| Whose browser | its own browser and profile | the owner's real Chrome and sign-ins |
| Runners / Docker | yes (headless) | no |
| Risk | isolated | acts as the owner; a worker click once hit his Chrome (no-synthetic-input rule) |
| Use for | worker testing (wasm app, demo QA), scraping docs | owner-attended tasks that need his logins |

- Infantry_Browser, an official plugin, contributes one Playwright spec with `provides: [browser]`, an isolated profile
  per session under the state dir, settings for headless / channel, installed through `Downloads` with progress. It shows
  in MCP Tools under Plugins like any other server.
- Claude in Chrome stays opt-in: exposed as capability `browser.owner` only when set up, given (`--chrome`) only to a
  ticket that names it with the owner present (Q4).
- The owner's own Playwright keeps working (a Claude user server); when both exist, the plugin one wins for `browser`
  unless the role allow-lists the owner's.

## 9. Tickets (small, ordered)

| # | Id | Layer | What | Depends |
|---|---|---|---|---|
| 1 | MCP-APPROVE-1 | Claude plugin | verify the home `.mcp.json` side effect of MCP-USER-2 on a real worker; replace `enableAllProjectMcpServers` with `enabledMcpjsonServers` from a setting (default empty); prove on a real PTY that unapproved project servers then raise no dialog | - |
| 2 | MCP-SPEC-1 | SDK + core (owner OK) | `McpServerSpec`, status types, `HarnessMcp`, `McpPlan`, `HarnessHost.mcpServers`, `SessionOutput.mcpServers`, contract bump in every harness module.json, `Harnesses.md` | - |
| 3 | MCP-REGISTRY-1 | core | `McpRegistry` (cached CLI lists, `limits.mcp.*`), Infantry-owned servers in settings + Secrets refs, `McpSelection`, state-dir config files, protocol `mcp_*` frames + session DTO | 2 |
| 4 | MCP-CLAUDE-1 | Claude plugin | `HarnessMcp` over `claude mcp list/get/add-json/remove` (fixture tests, masked env); delivery: one `--mcp-config`, `--disallowedTools` for denied | 2, 3 |
| 5 | MCP-OPENCODE-1 | opencode plugin | `HarnessMcp` over `GET /mcp` / `opencode mcp list` (read-only remove/edit); `enabled:false` for denied; all-server status | 2, 3 |
| 6 | MCP-PI-1 | pi plugin | `HarnessMcp` over `pi mcp list --json/add/remove`; `infantry-mcp.ts` takes a list; `--exclude-tools` / `--no-mcp`; delete pitoolbridge | 2, 3 |
| 7 | MCP-CLI-1 | Infantry_Mcp daemon half | `infantry mcp list/get/add/edit/remove/enable/disable/test/assign`, agent tool `infantry_mcp` (changes behind the approval gate) | 3 |
| 8 | MCP-UI-1 | Infantry_Mcp UI half | MCP Tools screen: list by source, cards, add/edit dialog, remove confirm, toggle, test (desktop) | 3, 7 |
| 9 | MCP-UI-2 | Infantry_Mcp UI half | compact / Android / web layout; Assignments tab | 8, 10 |
| 10 | MCP-SELECT-1 | Roles, Board, Agents | role `mcp` block, ticket `mcp` field, agent override | 3 |
| 11 | MCP-STATUS-1 | Health, sessions | health table, session details rows, tool-error FAILED detection | 3 |
| 12 | MCP-FAIL-1 | core + Notify | preflight refusal, runtime bus notices that open the card, agent chat note | 10, 11 |
| 13 | MCP-PLUGIN-1 | core | module.json `daemon.mcpServers` + guard | 3 |
| 14 | BROWSER-1 | new plugin | Infantry_Browser (Playwright, isolated profile, Downloads install, runner-ready) | 13 |
| 15 | BROWSER-OWNER-1 | Claude plugin | Claude in Chrome as opt-in `browser.owner` | 4, 10 |

Each is one worker ticket with named test classes. 1 can start now (it only touches the Claude plugin). 4, 5, 6 run in
parallel after 3; 7 and 10 also after 3.

## 10. Open questions for the owner

1. **Default for the user's own servers in Infantry sessions**: `all` (works like the owner's own CLI) or `none` with per-role
   allow-lists (cheapest, rule 5)? Recommendation: `all` on Claude and pi (deferred / codemode exposure keeps it cheap),
   `none` on opencode (every tool definition every turn), one setting per harness, changeable in MCP Tools.
2. **opencode has no remove/edit command.** Show opencode's servers read-only (enable/disable per run still works) and ask
   opencode upstream for `mcp remove`, or let Infantry edit opencode's documented `opencode.json`? Recommendation:
   read-only; editing its file is the kind of reach-inside the owner ruled out.
3. **Project `.mcp.json` servers (Claude)**: auto-approve all (today, MCP-USER-2; also approves the home folder's
   unrelated servers) or approve per name in MCP Tools (`enabledMcpjsonServers`)? Recommendation: per name, default none.
4. **Claude in Chrome for workers**: ticket opt-in while the owner is present, or never for workers (owner's own Assistant
   only)? Recommendation: ticket opt-in, owner present.
5. **Remote runners**: a user's local servers point at this PC (`C:\...cmd`). Proposal: `localOnly` servers are never sent;
   runners get Infantry-owned http servers and plugin servers only (Playwright in the image). OK?
