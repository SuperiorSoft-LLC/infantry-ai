# `infantry mcp serve` — Infantry AI as an MCP server (Phase 11, slices P11-A to P11-D)

`infantry mcp serve` runs an [MCP](https://modelcontextprotocol.io) server on **stdio**, so an MCP-capable
agent CLI can read the state of an Infantry AI daemon (P11-A) and act on it — spawn, prompt, message, kill —
within guardrails (P11-B). Project-scoped defaults and per-CLI registration are P11-C (below);
[Orchestrating agents](#orchestrating-agents-p11-d) at the end walks through a full run (P11-D).

```
infantry mcp serve                       # daemon at ws://127.0.0.1:8787/ws
infantry --url ws://host:8787/ws mcp serve --token-file ~/.infantry/token
infantry mcp serve --max-depth 2 --max-spawns 4      # the defaults, spelled out
infantry mcp serve --allow-kill-any                  # infantry_kill may stop ANY session
infantry mcp serve --project my-project              # default projectId for spawn / pod_state
```

The connection options are the CLI's own (`--url`, `--endpoint`, `--host`/`--port`, `--token-file`,
`INFANTRY_URL`, `INFANTRY_TOKEN[_FILE]`), resolved the same way as for every other command.

## Transport

- JSON-RPC 2.0, **one message per line** on stdin; one answer per line on stdout. EOF on stdin = clean exit (0).
- **stdout carries the protocol only.** Banner, reconnect notices and ignored notifications go to stderr.
- Methods: `initialize` (answers `serverInfo {name: "infantry", version: <daemon contract version>}`,
  `capabilities.tools`, and the requested `protocolVersion` when it is one of `2025-06-18`, `2025-03-26`,
  `2024-11-05`, else the newest), `notifications/initialized` (never answered), `ping`, `tools/list`,
  `tools/call`.
- Errors: unknown method `-32601`; malformed JSON `-32700` (id `null`); not a single object / no method
  `-32600`; unknown tool or missing tool name `-32602`.
- Hand-rolled on kotlinx.serialization: there is no MCP Kotlin SDK on the classpath or in the version catalog.

## Read-only tools (`readOnlyHint: true`)

Each result is one content block `{type: "text", text: "<JSON>"}`. Each call opens its own daemon
connection, so the server starts (and handshakes) with no daemon running; a call that cannot reach the
daemon returns `isError: true` with the fixed text `McpTools.DAEMON_UNREACHABLE`:

> Infantry AI daemon is not reachable. Start it with \`infantry daemon\` (or check --url / --port), then retry.

A refused token returns `isError: true` with the CLI's own refusal message.

| Tool | Arguments | Returns |
|---|---|---|
| `infantry_list_sessions` | `projectId?` (filter; omitted = the caller session's own project, `all` = every project; non-session callers see all) | `{sessions: [{id, sessionId (same value as `id`), agentId, role, provider, model, projectId?, status, lastAction?, lastOutputAt?, lastEventAt?, lastTurnAt?, backgroundWork?}]}` (the activity fields are absent until the session has produced an event; `lastOutputAt` = the last output line, which moves mid-turn too; `lastTurnAt` = the last finished turn, any harness and outcome) |
| `infantry_get_session` | `sessionId` (id or unique prefix, required), `lines` (1–200, default 50), `brief` (true = no transcript: state, `liveState`, `activity`, `ticketId`) | the summary fields + `runCount`, `lastExitCode`, `lastError`, `projectId`, `ticketId`, `transcript` (last `lines` lines), `transcriptTruncated`, optional `transcriptNote` |
| `infantry_board` | `projectId` (optional) | `{board: [line]}`: tickets by column (backlog, todo, in_progress, pending, done), one line each: id, title, worker role + session id + live state (busy / idle / running background process), last action, last output age, `branch@commit`. Same text as `infantry board` |
| `infantry_pod_state` | `projectId`, `podId` (both optional) | `{agents: {total, byRole: {role: [{id, name, model}]}}, sessions: {total, live, byStatus}, roster?, pod?}` |
| `infantry_list_harnesses` | — | `{harnesses: [{id, default, defaultModel, models (null = none listed), available, unavailableReason, note}], howToUse}` (BUG-ASSIGN-1) |

Terminology: a *profile* is a saved template (`agents` in `infantry_pod_state`, `infantry agents --profiles`); an *agent* is a running session in a project (`roster`, `infantry agents --live`); `infantry agents` lists both, one KIND column apart.

Only existing frames are used (`sessions`, `agent_list`, `agent_roster`, `pod_query`, `replay_request`): no
new frame, no contract bump. The transcript is the daemon's **replay buffer** for the session (text deltas
joined per turn, plus `[stderr]`, `[error]`, `[finished]` lines), so it covers what the running daemon
process still retains, not output from before a daemon restart; `transcriptNote` says when there was none.

## Write tools (P11-B, `readOnlyHint: false`)

Existing frames only — `new`, `prompt`, `kill`, `mail_send` — so no contract bump. `new`, a named `prompt`
and `kill` answer nothing on success and an `error` frame on refusal, so each write is followed by a
`sessions` request on the same connection as a **fence**: the daemon handles one connection's frames in
order, so every refusal arrives before the fence's answer, and that answer already reflects the write.
Every refusal (daemon or guardrail) is `isError: true` with a sentence saying what was hit.

| Tool | Arguments | Returns |
|---|---|---|
| `infantry_spawn` | `role` and/or `agentId` (at least one), `projectId`, `task` (optional first prompt); or `ticketId` | `{sessionId, agentId, role, provider, model, status, depth, taskSent, openSpawns, maxSpawns}`; with `ticketId`, the `infantry_ticket_start` answer |
| `infantry_prompt` | `sessionId` (id or unique prefix), `prompt` (required) | `{sessionId, status, runCount}` |
| `infantry_message` | `to` (agent id, role, or session id), `body` (required), `subject`, `replyTo` | `{messageId, threadId, from, to, state}` |
| `infantry_kill` | `sessionId` (id or unique prefix), `force` (default false) | `{sessionId, status, live, killOutcome?}` (`destructiveHint: true`) |
| `infantry_session_remove` | `sessionId` (id or unique prefix) | `{sessionId, removed, status, ticketId?}` (SESSION-RM-1, frame `session_delete`, `destructiveHint: true`). Takes an ENDED session (exited, failed, orphaned) off the session list the sidebar shows; any session, not only this server's spawns. Refused while it runs: `session <id> is still running (<state>); kill it first, then remove it`. Keeps the ticket, runs history, branch/worktree and mail; the session can no longer be resumed. Same as `infantry session rm <id>` |

- **spawn** is role-first like the `new` frame: `new {role, agentId, projectId}` binds the session at once,
  the new id is the one the fence's list adds (matching the role/agent); a `task` is then sent as a
  `prompt` NAMING that session, so it runs with the session's own persona and identity.
- **prompt** refuses text starting with `/`: that is a daemon slash command, not words for the agent, and
  could stop or swap sessions around the kill rule.
- **message** is `infantry send`: the sender is the session in `INFANTRY_SESSION_ID` (exported by the daemon
  into every spawned agent), else the human handle `user`; a role resolves within the sender's project.
  An UNDELIVERABLE message is an error, not a success.

### Reading mail (MAIL-MCP-1)

An agent reads its replies through its own MCP server, the same way `infantry inbox` / `infantry read` read them, over
the same frames (`mail_inbox`, `mail_read`). The identity is the same as for `infantry_message`: the session in
`INFANTRY_SESSION_ID`, else the human handle `user`.

| Tool | Arguments | Returns |
|---|---|---|
| `infantry_inbox` | `unreadOnly` (default false) | `{messages: [{id, threadId, from, to, subject, sentAt, state, read, preview}], count, unreadOnly}` |
| `infantry_read` | `messageId` (full id or unique prefix, required) | the row plus `body` (and `replyTo`); the message is marked read |

`infantry_read` refuses an unknown or ambiguous id, a message that failed delivery, and one that has not reached a
console yet, exactly as `infantry read` does. Reading a message twice returns it unchanged.

### Reporting progress (PROGRESS-1, contract 1.117.0)

`infantry_progress {mode, ...}` is the MCP form of `infantry progress`: the caller's own long-job progress, shown as a
live bar on its card. `mode` is `plan` (`steps` names or `count`), `step` (`step` = the one completed), `count` /
`bytes` (`done`, `total`), `percent` (`percent`), `status` (indeterminate, `label`), `done`, `fail` (`label` = reason),
`clear` or `get`. It answers one readable line. Outside a session it reports nothing and says so (not an error).
`infantry_list_sessions` / `infantry_get_session` carry `progress` (raw) and `progressLine`; the brief `activity` and the
board line end with it. See `docs/PROGRESS.md` for the CLI, the `##infantry` markers and `infantry run`.

### Asking the owner (ASK-1, contract 1.97.0)

`infantry_ask` is the MCP form of `infantry ask --recommend`: the same `question_ask` frame, the same rules. It is
NON-BLOCKING: it returns `{questionId, note}` at once, the agent carries on, and the owner's pick arrives as mail from
`owner` in thread `question:<questionId>` (read it with `infantry_inbox` / `infantry_read`). The asker is the session in
`INFANTRY_SESSION_ID`; outside a session the tool refuses (nobody to deliver the answer to).

| Argument | Required | Meaning |
|---|---|---|
| `question` | yes | The question, at most 2000 characters. |
| `options` | yes | 2 to 8 `{label, description}`; `description` is one line: what happens if the owner picks it. Labels are unique. |
| `recommended` | yes | The label of exactly ONE option, shown to the owner as "Recommended". |
| `reason` | yes | Why it is recommended, at most 200 characters. |
| `allowOther` | no (false) | The owner may type an answer of their own instead (or as well). |
| `defaultAfterMin` | no (off) | 1..1440. Unanswered after this many minutes, the daemon picks `recommended` and mails it marked "auto-selected after N min, owner did not answer". The owner can still override it; the override is a second mail in the same thread, marked `OVERRIDE`. |

Refused (an `isError` result naming the reason): a malformed question, a `recommended` that is not an option, or the
per-agent rate limit (at most 3 open questions and 6 asks in any rolling hour; settings `questions.maxOpenPerAgent`,
`questions.maxPerHour`). Questions are stored by the daemon and survive a restart until answered or cancelled. If the
owner cancels one, the agent gets a "cancelled" mail and decides by itself.

### Guardrails (enforced in the MCP server)

| Guardrail | Default | Flag | Refusal text starts with |
|---|---|---|---|
| Depth | max 2 | `--max-depth <n>` | `infantry_spawn refused: depth limit.` |
| Concurrency | 4 open spawns | `--max-spawns <n>` | `infantry_spawn refused: concurrency limit.` |
| Kill scope | own spawns only | `--allow-kill-any` | `infantry_kill refused: session <id> was not spawned by this MCP server.` |

**Depth.** A server runs at depth *d* = the number of MCP spawns above the session it serves; a spawn makes
a session at *d + 1*, refused when that exceeds `--max-depth`. With the default 2: a top-level server
(depth 0) spawns a depth-1 session, whose server spawns a depth-2 session, whose server is refused. The
server's depth is, in order of precedence:

1. `--mcp-depth <n>` on `infantry mcp serve`;
2. the env var **`INFANTRY_MCP_DEPTH`**;
3. the **lineage record** for this process's own session: on every spawn, the server writes the child's
   depth to `<lineage dir>/<childSessionId>`. The daemon (not the MCP server) starts the child's process,
   so the parent cannot set an env var there — but the daemon exports `INFANTRY_SESSION_ID` into it, and the
   child's own `infantry mcp serve` looks that id up in the lineage dir. The dir is `--lineage-dir <dir>`,
   else `INFANTRY_MCP_LINEAGE_DIR`, else `~/.infantry/mcp-lineage`; parent and child must see the same one;
4. otherwise 0.

**Concurrency.** A session this server spawned holds a slot while the daemon still knows it and it is
either running, or not ended (`EXITED`/`FAILED`/`ORPHANED`) and not yet killed through `infantry_kill`. A
bound-but-idle spawn holds its slot; killing it frees the slot; a killed session prompted back to life
counts again. The set of spawned sessions lives in the server process (it is not persisted).

**Kill scope.** `infantry_kill` only stops sessions this server process spawned, unless it was started
with `--allow-kill-any`.

The server logs its effective guardrails to stderr at start, e.g.
`infantry mcp: depth 0/2, max 4 open spawns, kill own spawns only`.

Code: `src/main/kotlin/.../cli/mcp/` (`McpServer` = JSON-RPC loop, `McpTools` = the read tools and dispatch, `McpWriteTools` = the
write tools, `McpGuardrails`, `McpLineage`). Tests: `src/test/kotlin/.../cli/mcp/McpServeTest.kt` and
`McpWriteToolsTest.kt` (piped stdin/stdout against the in-process daemon).

## Project-scoped config (P11-C)

`infantry mcp serve` reads an **optional** `.infantry/mcp.json` from its **working directory**. That is the
directory the MCP client starts it in, normally the repo root. The file sets defaults for that project:

```json
{
  "projectId": "my-project",
  "maxDepth": 2,
  "maxSpawns": 4,
  "allowKillAny": false
}
```

| Key | Flag | Built-in default | Effect |
|---|---|---|---|
| `projectId` | `--project <id>` | none | `projectId` used by `infantry_spawn` and `infantry_pod_state` when the call passes none. A `projectId` in the call always wins. |
| `maxDepth` | `--max-depth <n>` | 2 | depth guardrail |
| `maxSpawns` | `--max-spawns <n>` | 4 | concurrency guardrail |
| `allowKillAny` | `--allow-kill-any` | false | kill scope |

- **Precedence, per key:** flag > file > built-in default. Every key is optional, and there may be no file at all.
- `--allow-kill-any` is a presence flag, so it can only turn the wider kill scope **on**. If the file says
  `"allowKillAny": true` and you don't want that, change the file.
- **Strict file.** The server **stops at startup**, before `initialize`, with exit code 2 and one stderr line
  naming the file and the key, when the file:
  - is not valid JSON, or not a JSON object;
  - has an unknown key (e.g. `maxSpawn`);
  - has a wrong type: `"maxSpawns": "4"`, a negative number, an empty `projectId`, `"allowKillAny": "yes"`.

  Example: `error: .infantry/mcp.json (C:\repo\.infantry\mcp.json) 'maxSpawns' must be a whole number >= 0, got "four"`.
  The MCP client then shows the server as failed instead of running it with a guardrail you meant to narrow.
- The effective values are logged to stderr at start:
  `infantry mcp: depth 0/2, max 4 open spawns, kill own spawns only, project my-project` (or `no default project`).
- `--mcp-depth` and `--lineage-dir` can only be set by flag or env var, not in the file: depth belongs to each
  server process, not to the project.
- Inside a spawned agent the daemon exports `INFANTRY_PROJECT_ID`, but `mcp serve` does **not** read it as a
  default. Only `--project` and the file set one.

Code: `cli/mcp/McpServeConfig.kt`. Tests: `McpServeConfigTest.kt`.

## Ticket tools (MCP-1, `ticket_save` / `ticket_list`, same frames as the CLI)

| Tool | Arguments | Returns |
|---|---|---|
| `infantry_ticket_create` | `title` (required), `description`, `projectId`, `role`, `provider`, `model`, `images` (array of image paths: local files, uploaded, or paths already under the project's `.infantry/uploads/ticket/`; see Ticket images below) | `{ticketId, title, description, status, projectId, epicId, agentId, role, provider, model, branch, sessionId, images, saved, message}` |
| `infantry_ticket_update` | `ticketId` (full id or 8-char suffix, required), `title`, `description`, `role`, `provider`, `model`, `images` (replaces the list; `[]` removes all; a removed file is deleted once no ticket references it), or `addImages` / `removeImages` (edit the stored list; not with `images`) | same |
| `infantry_ticket_get` | `ticketId` (required) | the same without `saved` / `message` (`readOnlyHint: true`) |
| `infantry_ticket_delete` | `ticketId` (full id or 8-char suffix, required) | `{ticketId, title, deleted, message}` (MCP-DEL-1, frame `ticket_delete`, `destructiveHint: true`). One ticket per call, no bulk delete. An unknown or ambiguous id is refused and nothing is deleted; a ticket `in_progress` is refused (checked MCP-side, before the frame is sent): stop its worker with `infantry_kill` first. The ticket's links go with it, and its images unless another ticket still references the same file (IMG-3) |
| `infantry_ticket_start` | `ticketId` (full id or 8-char suffix, required) | `{ticketId, title, status, outcome (started / already_in_progress / queued), started, sessionId, agentId, role, provider, model, branch, worktree, workingDir, startedBy?, depth?, openSpawns?, maxSpawns?, note?, warning?, queuedFor?, message}` (BUG-ASSIGN-1); `note` is what a fresh branch was cut from, `cut from <ref> <short sha>`, also appended to `message` (BUG-REGRESS-1) |

- The `description` is sent verbatim (multi-line, quotes, shell metacharacters). The daemon's board trims its
  outer whitespace, as it does for the CLI and the UI.
- `role` is stored as `role:<id>`, like `infantry ticket create --role`. The daemon refuses an unknown provider or a
  model that provider does not list; the refusal is the tool error. A `role` that is not a role id (a display name
  such as `Backend Developer`) is refused, never matched to an id: the refusal lists the valid role ids (the daemon's
  catalog), so the agent can send the id instead.
- The project defaults to `--project` / `.infantry/mcp.json`, then the session's `INFANTRY_PROJECT_ID`.
- `saved` lists the fields the call set that the stored ticket now holds (`title`, `description`, `role`, `provider`,
  `model`, `images`), read from the daemon's answer; `message` says them with their stored values and the next step.

### Ticket images (IMG-AGENT-1)

An agent attaches an image by naming the FILE it has; no separate upload step.

- Each entry of `images` / `addImages` is either a local image file or a path already under the project's
  `.infantry/uploads/ticket/` (relPath or absolute, as `infantry_ticket_get` / `ticket show` print it), which is kept
  as is. A local file is sent with `image_upload` (purpose `ticket`, the frame `infantry image upload` sends), so the
  daemon stores it as `.infantry/uploads/ticket/<sha256>.<ext>`: identical bytes give the same path. The ticket's
  `images` holds those relPaths, which the board UI shows as attachments.
- Refusals save nothing: every local file is checked BEFORE the first upload is sent (it exists, it is at most 10 MB,
  its magic bytes are png, jpeg, gif or webp: the daemon's own limits), and a bad one refuses the whole call with no
  upload written and no ticket saved. A ticket with no project (or a project with no directory) cannot hold images.
- Update: `images` REPLACES the whole list (`[]` clears it). `addImages` appends to the stored list; `removeImages`
  drops entries, each named by its stored path, its file name, or the original local file (matched by sha256). A
  removal naming an image the ticket does not have is refused. `images` together with `addImages` / `removeImages` is
  refused. A dropped image's file is deleted once no ticket references it (IMG-3).
- The CLI has the same: `ticket create|update --image <file>` (repeatable; on update it ADDS) and
  `ticket update --remove-image <path>` (repeatable). `ticket show` prints `IMAGES:` with each image's absolute path,
  and a worker started on the ticket gets them in its brief under "Attached images" as `[image: <absolute path>]`.

Code: `cli/TicketImageFiles.kt` (shared by the CLI and `cli/mcp/McpTicketTools.kt`). Tests: `McpTicketImagesE2eTest`,
`TicketImageAttachE2eTest`.

### Starting a ticket (BUG-ASSIGN-1)

`infantry_ticket_start` is THE way an agent puts another agent to work on a ticket. It sends the board's own start,
`ticket_move` to `in_progress` (the frame a person's drag sends), so `TicketBoard.handOut` runs exactly as for the
board: the worker runs the ticket's pins (ticket > profile > role binding > default), in its own git worktree on
`ticket/<short-id>-<slug>`, and the ticket goes In Progress with the calling session (`INFANTRY_SESSION_ID`) as its
starter. Existing frames only (`ticket_move`, `ticket_list`, `sessions`): no contract bump. Code: `cli/TicketStarter.kt`,
shared with `infantry ticket start`. The branch is cut from the base ref's commit (the ticket's epic base, else its project base, else
`INFANTRY_BASE_BRANCH`; with none of them the start is refused, BUG-BASE-087), so
uncommitted changes in the project checkout never refuse a start and are not on the ticket's branch; `note` says which
commit it got (BUG-REGRESS-1).

- The answer is the board's `ticket` frame for that ticket carrying `start`. `started` and `queued` are results;
  `already_in_progress` starts nothing and names the worker it has. A held ticket (open prerequisites) and a refused
  start (no profile has the role, a refused provider / model / branch) are `isError: true` with the daemon's reason.
- Guarded like `infantry_spawn`: refused beyond the depth limit and this server's open-session limit; the worker takes
  one of this server's slots and is recorded in the lineage. Already-in-progress, queued and held starts take none.
- `infantry_spawn` with a `ticketId` delegates here. A `role` / `agentId` / `projectId` that disagrees with the ticket
  is refused (change the ticket with `infantry_ticket_update` first), and so is a `task`: the brief is the description.
- `infantry_list_harnesses` lists what may be pinned: each harness id (a ticket's `provider`) with its models (from
  the `providers` answer, else the `models` catalog), the same list as `infantry harnesses`.

## Every daemon-spawned session gets this server (MCP-1)

No registration is needed inside Infantry AI. The daemon adds `infantry mcp serve` to every session it spawns. The
launcher is the one it already puts on the session's `PATH` (`INFANTRY_BIN`; on Windows `cmd /c <infantry.bat|.cmd>`).
The entry names the session identity (`INFANTRY_SESSION_ID`, `_PROJECT_ID`, `_AGENT_ID`, `_ROLE`, `_URL`,
`_MAIL_BASE`). The token is never written into it; the server inherits it. Code: `daemon/InfantryMcp.kt`.

- **Claude**: one extra `--mcp-config <file>`, placed after `INFANTRY_CLAUDE_MCP_CONFIG`'s values and never replacing
  them. The file is content-addressed under `<tmp>/infantry-mcp/`, so a session keeps the same path every turn. It is
  a file and not inline JSON because a JVM `ProcessBuilder` on Windows does not escape the quotes inside an argument
  (claude.exe would receive `{mcpServers:{...}}`). `--allowedTools` carries `mcp__infantry__*` in every Tool approval
  mode, next to BUG-PERM-5's `Bash(infantry:*)`.
- **opencode**: `mcp.infantry` (local) and `permission."infantry_*": "allow"` are merged into `OPENCODE_CONFIG_CONTENT`
  on `run` and `serve`. The serve adapter answers a `permission.asked` for an `infantry_*` tool with `once` in every
  mode, and never sends it to the owner.
- A user config that already defines an `infantry` server keeps its own (Claude and opencode).
- **Pi / Codex: not wired.** Pi 1.3.0 reads MCP servers only from `~/.pi/agent/mcp.json` or a trusted project's
  `.pi/mcp.json`, and has no per-run flag. Codex's descriptor args are static, and its `-c mcp_servers...` TOML values
  would lose their quotes through the same Windows argv. Both still reach the board through the `infantry` CLI.
- Live check: `node docs/infantry-e2e-mcp-ticket.mjs` (throwaway daemon, Tool approval deny, Claude session).

## Registering `infantry mcp serve` in each CLI (P11-C)

Every block below starts the same stdio server.

- If `infantry` is not on the client's `PATH`, use the full path to the `installDist` launcher instead
  (on Windows: `...\build\install\infantry\bin\infantry.bat`).
- Put the project's defaults in `.infantry/mcp.json` and keep the client entry short. The guardrail flags appear
  in each block only to show where they go.
- If the daemon is not at `ws://127.0.0.1:8787/ws`, add `--url`, `--port` or `--token-file` (before or after `mcp serve`).

**Working directory.** The server reads `.infantry/mcp.json` from its working directory, which these clients
normally set to the project directory. If that is not the repo holding the file, set `cwd` where the client
supports it.

**Verification status.** "Format verified" means the block was checked against that CLI's own documentation
or a working config on this machine. It does **not** mean the CLI was run against `infantry mcp serve`:
none of these blocks has been run end to end yet.

| CLI | File | Format verified against |
|---|---|---|
| Claude Code | `.mcp.json` (project) | the `mcpServers` format of this repo's own working `.mcp.json`, which Claude Code reads |
| Codex | `~/.codex/config.toml`, or `.codex/config.toml` (trusted projects only) | Codex MCP docs (learn.chatgpt.com/docs/extend/mcp, read 2026-10-18), plus `codex mcp add --help` from codex-cli 0.162.1 |
| Gemini CLI | `.gemini/settings.json` (project) or `~/.gemini/settings.json` | gemini-cli `docs/tools/mcp-server.md` (main branch, read 2026-10-18). **Gemini CLI is not installed here**, so even its `mcp add` command is untested |
| opencode | `opencode.json` / `opencode.jsonc` (project) or `~/.config/opencode/opencode.jsonc` | a working `"type": "local"` entry in this machine's opencode 1.18.35 config |
| Pi (Oh My Pi) | `.pi/mcp.json` (project, after project trust) or `~/.pi/agent/mcp.json` | `docs/mcp.md` of the `@earendil-works/pi-coding-agent` package bundled with the installed `oh-my-pi` 0.2.0 |

### Claude Code: `.mcp.json` at the repo root

```json
{
  "mcpServers": {
    "infantry": {
      "command": "infantry",
      "args": ["mcp", "serve", "--max-depth", "2", "--max-spawns", "4"]
    }
  }
}
```

Or from a shell: `claude mcp add --scope project infantry -- infantry mcp serve --max-depth 2 --max-spawns 4`.
The tools then appear as `mcp__infantry__infantry_spawn` and so on. Claude Code asks for approval before it uses
a server from a project `.mcp.json`.

### Codex: `config.toml`

```toml
[mcp_servers.infantry]
command = "infantry"
args = ["mcp", "serve", "--max-depth", "2", "--max-spawns", "4"]
# cwd = "/path/to/repo"          # where .infantry/mcp.json lives, if not the session directory
startup_timeout_sec = 10
tool_timeout_sec = 120            # a spawn waits on the daemon; the default is 60

# [mcp_servers.infantry.env]
# INFANTRY_TOKEN_FILE = "/home/me/.infantry/token"
```

Or: `codex mcp add infantry -- infantry mcp serve --max-depth 2 --max-spawns 4`, which writes `~/.codex/config.toml`.

### Gemini CLI: `.gemini/settings.json`

```json
{
  "mcpServers": {
    "infantry": {
      "command": "infantry",
      "args": ["mcp", "serve", "--max-depth", "2", "--max-spawns", "4"],
      "timeout": 120000,
      "trust": false
    }
  }
}
```

Or: `gemini mcp add infantry infantry mcp serve --max-depth 2 --max-spawns 4`. It writes the project file by
default; add `-s user` to write `~/.gemini/settings.json` instead.

- `timeout` is in **milliseconds** here.
- Keep `"trust": false`, so Gemini still asks for confirmation before `infantry_spawn` or `infantry_kill`.
- Don't put `_` in the server name: Gemini's policy parser splits tool names on it.

### opencode: `opencode.json`

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "infantry": {
      "type": "local",
      "command": ["infantry", "mcp", "serve", "--max-depth", "2", "--max-spawns", "4"],
      "enabled": true
    }
  }
}
```

Unlike the other clients, `command` here is ONE array holding the executable and its arguments, and
environment variables go under `"environment"`, not `"env"`.

### Pi (Oh My Pi): `.pi/mcp.json`

```json
{
  "mcpServers": {
    "infantry": {
      "command": "infantry",
      "args": ["mcp", "serve", "--max-depth", "2", "--max-spawns", "4"],
      "timeout": 120,
      "description": "Infantry AI daemon: sessions, pod state, guarded spawn/prompt/message/kill"
    }
  }
}
```

Or: `pi mcp add -l infantry -- infantry mcp serve --max-depth 2 --max-spawns 4`. `-l` writes the project file;
without it, Pi writes `~/.pi/agent/mcp.json`.

- Pi reads the project file only after project trust is granted.
- `timeout` is in **seconds** here.
- Run `/reload` in a live session after editing the file.

### Nested agents

A session spawned through `infantry_spawn` runs its own CLI, which starts its own `infantry mcp serve` from the
same client config. Nothing from the parent's flags or file carries over; only its depth does, through the
lineage dir. So the child gets the same project and limits only because it reads the same
`.infantry/mcp.json` in the repo.

## Orchestrating agents (P11-D)

One agent (the **orchestrator**) drives another through its `infantry mcp serve`. This walkthrough follows the
capstone test step by step: `McpOrchestrationE2eTest` runs it against a real daemon with a scripted provider.
Every call below is a `tools/call`; answers are JSON in the result's single `text` content, and a refusal is
`isError: true`.

**0. The project.** The repo root has `.infantry/mcp.json`:

```json
{ "projectId": "e2e-project", "maxDepth": 2, "maxSpawns": 2 }
```

The orchestrator's CLI starts `infantry mcp serve` there. It logs
`infantry mcp: depth 0/2, max 2 open spawns, kill own spawns only, project e2e-project`.

**1. Handshake.** `initialize` returns `serverInfo.name = "infantry"`. `tools/list` returns the 7 tools:
`infantry_list_sessions`, `infantry_get_session`, `infantry_pod_state`, `infantry_spawn`, `infantry_prompt`,
`infantry_message`, `infantry_kill`.

**2. Spawn a worker with a task.**

```json
{"name": "infantry_spawn", "arguments": {"role": "qa", "task": "triage the login bug"}}
→ {"sessionId": "e059b476-…", "role": "qa", "depth": 1, "taskSent": true, "openSpawns": 1, "maxSpawns": 2, …}
```

The call names no `projectId`, so the session lands in `e2e-project` (from the file). The server records the
child's depth (1) in the lineage dir, and the task runs as the session's first turn.

**3. See it.** `infantry_list_sessions` lists `{id, role: "qa", status, …}`. `infantry_pod_state` with no
arguments counts it under `sessions.total` and `sessions.byStatus`. It also returns the `roster` of
`e2e-project`, the default project. Roster rows are agent **profiles** bound to the project, so a role-first
spawn with no profile does not get a row there.

**4. Talk to it.**

```json
{"name": "infantry_message", "arguments": {"to": "e059b476-…", "body": "status report please"}}
→ {"messageId": "…", "from": "user", "to": "e059b476-…", "state": …}
```

COMMS wakes the session at rest and resumes it with the message as its next turn. Once that turn
finishes, read the reply:

```json
{"name": "infantry_get_session", "arguments": {"sessionId": "e059b476", "lines": 200}}
→ {"runCount": 2, "transcript": ["…", "triage the login bug", "scripted reply: on it",
   "[finished] SUCCEEDED exit=0", "…", "[Infantry AI COMMS] message … from user …", "status report please",
   "scripted reply: on it", "[finished] SUCCEEDED exit=0"], …}
```

`infantry_get_session` is not a wait. Poll it (or `infantry_list_sessions`) until `status` is no longer
`STARTING`/`BUSY` and `runCount` has gone up.

**5. Stop it.** `infantry_prompt` gives it another turn. `infantry_kill` with `"force": true` stops that turn.
The session is then no longer live, `infantry_get_session` reports the ended state, and the transcript ends with
a `[finished]` line that is not `SUCCEEDED`. The slot it held is free again, so the orchestrator may spawn once
more. Killing a session that is **at rest** is a no-op on the daemon: it has no run to stop.

**6. The guardrails hold across levels.**

- *Depth.* The worker's own CLI starts its own `infantry mcp serve` with `INFANTRY_SESSION_ID` set to the
  worker's id (the daemon exports it). That server reads depth 1 from the lineage dir, so it logs
  `depth 1/2` and may spawn one level down: that child is at depth 2. The depth-2 child's server is refused:
  `infantry_spawn refused: depth limit. …`. Nothing is created.
- *Concurrency.* With `maxSpawns: 2`, an orchestrator holding two open spawns is refused:
  `infantry_spawn refused: concurrency limit. … Kill one with infantry_kill, or wait for one to end, then retry.`
- *Kill scope.* The orchestrator may not kill the depth-2 child: the worker spawned it, not the orchestrator.
  Only the server that spawned a session may kill it, unless that server runs with `--allow-kill-any`.

Test: `src/test/kotlin/.../cli/mcp/McpOrchestrationE2eTest.kt`. It runs in-process through the same
`Cli.run` stdin/stdout seam a child process uses, with one `mcp serve` per agent, all sharing the lineage dir.

## Notes scopes (NOTES-1)

`mcp serve` exposes no memory tools: an agent reads and writes notes through the CLI (`infantry memory ... --scope
project|role|session`, see AGENT-QUICKREF.md). The scope rides the `memory_*` frames (`scope`, `sessionId`, `role`);
the calling session comes from `INFANTRY_SESSION_ID`, so the role is the caller's own and an agent cannot address another
role's notes unless it is the `pm` (the owner, with no session, may). Session-scope notes are deleted with their
session, whichever way it ends (kill or removal).
