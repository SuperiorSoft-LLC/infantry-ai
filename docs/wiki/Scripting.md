[← Wiki index](index.md)

# Scripting with Lua

Scripts encode your process: what happens when a ticket is done, when mail arrives, when a build ends. They are
Lua 5.2 (LuaJ 3.0.1), sandboxed, and live in your project's `.infantry/scripts/` folder. Any agent or role may
author and run them. The engine is a plugin (`Infantry_Lua`) behind the `Infantry_Scripting` plugin, which owns
hooks, limits and the tools.

## How a script runs

A script does not do things itself. It **queues actions**; after it ends, the daemon runs them in order **as the
caller**, and the first failure stops the run. Globals: base, string, table, math, `os.time`, `os.clock`, `ih`, `input`.
There is no `io`, `require`, `load` or `debug`.

```lua
-- move a ticket, then comment with what happened
if not input.results then
  ih.move{ticket = input.args.ticket, status = "done"}
  ih.again()
else
  ih.comment{ticket = input.args.ticket, text = "moved: " .. input.results[1].text}
end
```

- `input.args`: the run's arguments (the hook's fixed args merged under the event payload).
- `input.phase`: `1`, then `2..` after `ih.again()`. `input.caller`: `{id, role}`.
- `input.results` (phase 2+): one entry per executed action, in order: `{name, tool, ok, text, json}` (`json` is the
  tool's answer when it is JSON).
- `ih.log("text")` adds to the run log.

### `ih.again` phases

A script cannot read a tool's answer in the same run. Queue the reads, call `ih.again()`, and the script re-runs
with `input.results`. Branch on `input.phase` (or `input.results == nil`). The cap is the setting
`scripting.maxPhases` (default 3, hard maximum 10); past it the run ends with `PHASES_EXHAUSTED`. A dry run
(`dryRun true`) shows the first phase's plan and runs nothing.

### Calling tools

Every tool the daemon has is callable: `ih.call("infantry_ticket_move", {ticket = "t1", status = "done"})`, or
`ih.<toolname>{...}`, or by alias. Arguments are always ONE table of named values. Aliases come from the setting
`scripting.aliases` (short name to tool name; defaults: `move`, `comment`, `message`, `start`, `close_session`,
`kill`, `prompt`, `ask`, `ticket_get`, `ticket_update`, `ticket_create`, `board`, `emit`) plus built-ins every project
has: `exec`, `cli`, `exec_status`, `progress`, `chat_note`, `chat_image`, `session_read`, `session_act`,
`notice_suppress`.

The live list of tools, aliases and engine notes: `infantry_script_api` (MCP tool; optional `tool` argument for one
tool in full). It is generated from the registry on each call, so it is always current.

## Saving, running, listing

MCP tools (preferred) and CLI:

| Tool | CLI |
|---|---|
| `infantry_script_save {name, source}` (parse-checked, runs at once) | `infantry script save <name> --file <path>` (or `--body -`) |
| `infantry_script_list` | `infantry script list` |
| `infantry_script_delete {name}` (refused while a hook uses it) | `infantry script delete <name>` |
| `infantry_script_run {name, args, dryRun}` | (MCP) |
| `infantry_script_hook {action: list\|add\|remove, on, script, args, id, to}` | `infantry script hook list \| add <on> <script> [--payload <json>\|-] \| remove <id>` |
| `infantry_script_stats` (turns saved, wakes by reason, latest runs) | (MCP) |

## Hooks

A hook says "when this happens, run this script with these fixed args". The `on` values:

| `on` | Fires | Payload keys (merged into `input.args`) |
|---|---|---|
| `lane enter <lane>` / `lane exit <lane>` | a ticket enters or leaves a lane (`todo`, `in_progress`, `pending`, `done`, or your own); case-insensitive | `ticketId`, `title`, `branch`, `tipSha`, `workerSession`, `from`, `to`, `lane` |
| `mail.incoming` | before a mail is delivered, synchronously; attaches to ONE session (`to=<session id>`, default yours) | `id`, `from`, `to`, `role`, `sessionId`, `subject`, `body`, `threadId` |
| `exec.done` | a background exec ends | `id`, `exitCode`, `script`, `session`, `timedOut` |
| `exec.tick` | every `scripting.exec.tickSec` seconds while a background exec runs | `id`, `script`, `session`, `elapsedMs`, `stdoutTail`, `stderrTail`, `bytesSinceLast` |
| `every <N>s` | a timer; minimum `scripting.hooks.minEverySec` (default 10) | `tick`, `at`, `hookId` |
| any event name | a named event: `ticket.commented`, `merge.done`, `ticket.*`, or your own via `infantry_event_emit` | the event's payload |
| `daemon.started` | once, when the daemon's port is bound after a start | `cutTurns` and `orphaned`: lists of `{sessionId, role, agentId, ticketId, projectId, lastAction, lastOutputAt, starter}` (a key is absent when unknown; `starter` = the ticket's starter, else the spawner); `orphanedIds` |
| `session.idle` | a session's turn ends and it is at rest | `sessionId`, `state`, `previous`, `role`, `agentId`, `ticketId`, `projectId` |
| `session.first-turn-timeout` | reserved, not published yet: the first-turn watch lives in the harness, which has no event seam (needs a harness API bump) | none |

Examples:

```
infantry script hook add "lane enter done" on-ticket-done --payload '{"devBranch":"main"}'
infantry script hook add "every 180s" worker-watch --payload '{"exemptRoles":["pm"]}'
```

`to` on `every <N>s` and `exec.tick` names a session the script acts for (its `progress` lands on that card).

### Project isolation (PROJECT-ISOLATION-1)

A hook belongs to the project whose `hooks.json` holds it, and it runs ONLY for that project's events. That covers ticket events
(`ticket.*`, `ticket.commented`, lane enter/exit), `session.*`, `exec.*`, `mail.incoming` / `mail.sent` and events raised with
`infantry_event_emit`. The daemon stamps each event with its `projectId`: the payload's own, else the project of its `ticketId`,
else the project of its `sessionId` / `workerSession` (remembered, so `ticket.deleted` and `session.ended` still carry it); an
emitted event with no project gets the emitter's. An event that still has no project reaches only hooks marked `global`:

```
infantry_script_hook {"action":"add","on":"merge.done","script":"on-merge","global":true}
```

A hook's script is confined to its project too: its tool calls (`ih.*`) refuse a ticket, session or project of another project,
exactly as an agent's do. Only cross-project mail (`allowCrossProject: true`, below) crosses on purpose. A call that names no
project (`ih.list_sessions{}`, `ih.board{}`) means the hook's own project, for every hook kind (timer, lane, event, mail,
`exec.*`); `projectId: "all"` is refused with "this project hook is confined to project ...".

### Shipped hooks

The Scripting plugin ships hooks of its own, so a default process works with no setup and is still a script. They sit in
the plugin jar (`builtin-scripts/builtins.json` and the scripts next to it), run daemon-wide (not per project) as the
daemon's automation identity, and go through the same loop guard and run log as a project hook.

| Id | `on` | What it does | Settings (its args) |
|---|---|---|---|
| `resume-after-restart` | `daemon.started` | Prompts every session whose turn the stop cut to continue where it left off; comments on its ticket (or mails its starter); raises one `session_resumed` attention item with the summary. A session whose process outlived the daemon (`orphaned`) is named, never prompted. | `scripting.restart.resume` (`true`; `false` = tell only), `scripting.restart.prompt` (`""` = "The daemon restarted and your last turn was cut. Continue where you left off.") |

`scripting.builtins.off` (comma-separated ids) turns a shipped hook off; bind your own script to the same event to
replace it. The script calls `infantry_attention_raise{kind, outcome, detail, ticketId?, sessionId?}` (Infantry_Attention)
to reach the owner.

### Intercepting mail

`mail.incoming` runs before any wake, bounded by the plugin call timeout (a couple of seconds), so keep it cheap.

- `ih.handled()`: the mail is marked read and wakes nobody.
- `ih.deliver_text{text = "..."}`: deliver this text instead of the notify line.
- Anything else, an error, or a timeout: delivered as usual. A mail is never dropped.

```lua
-- pong: answer "PING <nonce>" without waking the recipient
local nonce = input.args.body and input.args.body:match("^PING (%S+)")
if not nonce then return end
ih.message{to = input.args.from, subject = "PONG", body = "PONG " .. nonce}
ih.handled()
```

### Cross-project mail

An agent finds another project on demand, never from its start brief: the MCP tool `infantry_projects` (CLI `infantry project find [query]`) lists each project's id, name, one-line description and the roles with a live session. The description is project data: `infantry project config set <projectId> project.description "..."`. Then `infantry_message to: "pm@<project id or name>"` with `allowCrossProject: true` reaches that project's session for the role; it is refused without the flag (this is the one deliberate exception to project isolation), and errors clearly for an unknown or ambiguous project, a role with no live session, or several agents of the role.

Mail from a session of another project reaches the recipient project's `mail.incoming` hook with two extra args, `fromProjectId` and `fromProjectName` (absent for same-project mail), so each project screens cross-project mail with its own rules:

```lua
if input.args.fromProjectId then ih.deliver_text{text = "[from " .. input.args.fromProjectName .. "] " .. input.args.body} end
```

### Loop guards

Hooks can trigger events that trigger hooks. Guards (settings): `scripting.hooks.maxDepth` (3),
`scripting.hooks.maxRuns` per hook per `scripting.hooks.windowMs` (10 per 60 s), and `scripting.hooks.attributionMs`
(5 s, follow-up events attributed to the hook that caused them). Turn all hooks off with `scripting.hooks.enabled`.

## exec: running a process

A script is sandboxed and runs nothing itself; it queues `ih.exec{...}` (same as `ih.cli`; MCP tool `infantry_exec`)
and the daemon runs the process as the caller. The bridge is **off by default**.

Args: `command` (an argv list, run directly; or one string split on spaces/quotes, no shell), `cwd` (default the
project root), `env`, `stdin`, `timeoutSec`, `script` (label), `background`. A non-zero exit is a result, not a
failure. Result in `input.results[i].json`: `{exitCode, durationMs, stdoutTail, stderrTail, timedOut}`.
`background = true` answers `{id, state = "running"}`; read it with `infantry_exec_status {id}` or continue from an
`exec.done` hook.

```lua
if not input.results then
  ih.exec{command = input.args.cmd, timeoutSec = 300, script = "check"}
  ih.again()
else
  local r = input.results[1].json
  if r.exitCode ~= 0 then
    ih.prompt{session = input.caller.id, text = "command failed (" .. r.exitCode .. "): " .. r.stderrTail}
  end
end
```

### The allow-list

| Setting | Default | Meaning |
|---|---|---|
| `scripting.exec.enabled` | `false` | Master switch; when off the tool refuses and says how to enable it |
| `scripting.exec.allow` | empty (= all) | JSON array or one per line; an entry is a command-line **prefix**, or `re:<regex>` matching the whole line |
| `scripting.exec.roles` | empty (= any) | Comma-separated roles allowed |
| `scripting.exec.defaultTimeoutSec` / `maxTimeoutSec` | 60 / 600 | A timeout kills the whole process tree |
| `scripting.exec.tailChars` | 8000 | Output tail kept |
| `scripting.exec.tickSec` | 10 | Seconds between `exec.tick` events |

Enable it narrowly: `infantry settings set scripting.exec.enabled true` and
`infantry settings set scripting.exec.allow git`. Each run is logged once (session, role, script, command, exit code,
duration).

## Chat notes

`ih.chat_note{sessionId = ..., text = ..., level = "ok"}` posts a status line into a session's chat as an automation
card (level `info`, `ok`, `warn` or `fail`; optional `image`). `warn` draws a yellow WARNING line, not an
error: use it for alerts such as low memory; `fail` is red and marked FAILED. No turn and no mail, unless `wake = true`, which also prompts
the agent: use that for real exceptions only. Same project only, rate limited per minute, text length capped.

## Progress

`ih.progress{...}` (`infantry_progress`) shows a live bar on a session's card. From a hook with `to`, it lands on
that session; otherwise on the caller, or the `sessionId` you name (same project only). Agents use the CLI form:
`infantry progress 40 "compiling"`, `progress plan "a" "b"`, `progress step`, `progress done`, `progress fail`.
`infantry run --label tests -- <cmd>` runs a command with progress reported on exit.

## The session bridge

Scripts can watch and steer sessions with built-in aliases:

- `session_read{sessionId}`: state, `lastAction`, `lastOutputAt` (last output line), `lastEventAt`, `lastTurnAt` (last finished turn, any harness), recent chat lines (capped).
- `session_act{sessionId, action = "interrupt" | "end_turn"}`: stop the turn in flight. `end_turn` is a no-op on an
  idle session.
- `notice_suppress{name, seconds}`: suppress a built-in daemon notice (e.g. `nudge.worker_idle`, or `*`) for up to
  3600 s so a hook can replace it.
- `prompt{session = ..., text = ...}` wakes a session; `close_session` removes one.

## Settings that govern scripting

Limits per run: `scripting.maxSteps` (5,000,000), `scripting.timeoutMs` (2000), `scripting.maxAllocBytes`
(64 MiB), `scripting.maxPhases` (3), `scripting.defaultEngine` (`lua`). Hooks and exec: see above. All are daemon
settings: `infantry settings get scripting.timeoutMs`, `infantry settings set <key> <value>`.

## Worked examples

Process rules as data: keep project-specific values in hook args, never in the Lua.

**Park a ticket's worker when it leaves a lane** (hooks `lane enter backlog`, `lane exit in_progress`):

```lua
local a = input.args
local ticket = a.ticketId or error("no ticketId")
local done = a.doneLane or error("needs doneLane")
if a.to and a.to:lower() == done:lower() then return end
if input.phase == 1 and a.workerSession then
  ih.infantry_session_remove{sessionId = a.workerSession, force = true}
  ih.again()
else
  ih.infantry_ticket_comment{ticketId = ticket, body = "moved " .. tostring(a.from) .. " -> " .. tostring(a.to)}
end
```

```
infantry script hook add "lane enter backlog" lane-park --payload '{"doneLane":"done"}'
infantry script hook add "lane exit in_progress" lane-park --payload '{"doneLane":"done"}'
```

**Run a build in the background and report** (an `exec` with `background = true` plus hooks on `exec.tick` for the
progress bar and `exec.done` for the result comment and chat note). The pattern: the starting script launches the
exec and puts a plan on the builder's card; the tick hook sets the percent from the output lines; the done hook posts
`chat_note` ok/fail and moves the ticket.

**Notify on checkpoint tickets** (Infantry_Notify; hook `lane enter done`). The checkpoint list and the sound are hook
args, so the script holds no project values:

```lua
local a = input.args
for _, id in ipairs(a.checkpoints or {}) do
  if id == a.ticketId then
    ih.infantry_notify{title = "Checkpoint done: " .. tostring(a.title), body = id .. " is done.",
      level = "milestone", sound = a.sound or "chime", ticketId = id}
  end
end
```

```
infantry script hook add "lane enter done" notify-checkpoint --payload '{"checkpoints":["ticket-1234abcd"],"sound":"bell"}'
```

**Wake the owner when a background build fails** (hook `exec.done`): `urgent` rings even in quiet hours.

```lua
local a = input.args
if a.exitCode ~= 0 then
  ih.infantry_notify{title = "Build failed", body = tostring(a.script) .. " exited " .. tostring(a.exitCode),
    level = "urgent", sound = "alarm"}
end
```

Scripts keep no state between runs, so "notify after N tickets" counts in phase 2 from a tool answer (for example
`infantry_board` with `columns = "done"`), or marks the Nth ticket with `infantry_notify_milestone` instead.

More shipped scripts: `plugins/official/Infantry_Scripting/project-scripts/` (`on-ticket-done.lua`,
`on-mail-sent.lua`) and its README.

## Notes on the code vs older docs

- An exec id can reach an `exec.done` hook as a number in older builds; compare as a string before calling
  `exec_status`.
- The script CLI lives in `ScriptCli.kt`; its usage line is
  `infantry script hook list | add <on> <script> [--payload <args json>|-] | remove <id> | save <name> [--file <path> | --body -] | list | delete <name>`.

## Reference: hooks.json, engine limits, adding a language

Added by DOCS-2. Sources: `plugins/official/Infantry_Scripting/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/scripting/daemon/ScriptEngine.kt`,
`ScriptEngineWire.kt` (same folder), `plugins/official/Infantry_Lua/daemon/.../LuaScriptEngine.kt`,
`plugins/official/Infantry_Scripting/module.json`.

### hooks.json

Hooks live in `<project>/.infantry/scripts/hooks.json`:

```json
{ "version": 1, "hooks": [
  { "id": "h1", "on": "lane enter review", "script": "notify-review", "args": { "text": "ready" }, "enabled": true }
] }
```

Fields per hook: `id`, `on`, `script`, `args`, `enabled`, `ownerSession`, `ownerRole`, `ownerIsOwner`, `to`, `global` (true = also runs for events with no project; see Project isolation).
`on` forms: `lane enter <lane>` and `lane exit <lane>` (the named event `ticket.moved`), a named event
(`mail.incoming`, `exec.done`, `exec.tick`, ticket events), a prefix ending in `.*` or `*`, and `every <N>s` (a timer).
Manage them with `infantry script hook list | add | remove` (see above) rather than editing the file by hand.

### Engine limits

| Limit | Value | Where |
|---|---|---|
| Queued actions per run | 100 (`MAX_ACTIONS`) | `LuaScriptEngine` |
| Log lines per run | 200 (`MAX_LOGS`), each cut to 2000 chars (`MAX_LOG_LEN`) | `LuaScriptEngine` |
| `ih.again` / nested depth | 16 (`MAX_DEPTH`) | `LuaScriptEngine` |
| Step check interval | every 1000 VM instructions (`HOOK_EVERY`) | `LuaScriptEngine` |
| Steps / time / memory | 5,000,000 steps, 2000 ms, 64 MiB (`ScriptLimits` defaults; the `scripting.*` settings change them) | `ScriptEngine.kt` |
| Run log kept | `limits.scripting.runLogMax` (default 200) | settings |

Going over a limit ends the run with `BUDGET_EXCEEDED`, `TIMEOUT` or `ALLOC_EXCEEDED`; a Lua error gives `ERROR`.
`string.rep` is guarded so a script cannot allocate past the memory limit.

### Adding a language (the `ScriptEngine` interface)

An engine plugin implements:

```kotlin
interface ScriptEngine {
    val id: String                      // "lua"
    val extensions: List<String>        // ["lua"], no dot, lower case
    val binding: String get() = ""      // how this language binds ih; shown to agents by script_api
    fun check(source: String): String? = null   // parse error, or null when it loads
    fun run(source: String, input: Map<String, Any?> = emptyMap(),
            limits: ScriptLimits = ScriptLimits(), caller: ScriptCaller = ScriptCaller()): ScriptResult
}
```

`run` never performs actions: it returns `ScriptResult(outcome, actions: List<QueuedAction(name, args)>, logs, error)`
and the caller runs the actions. Publish the engine with `ScriptEngineWire.publish(services, engine)`
(it registers the string endpoint `scripting.engine/<id>`). Scripting finds every published engine and routes by id or
file extension. Plugins have separate class loaders, so the wire is JSON: operation `describe` answers
`{id, extensions}`, `binding` answers the text, `check` answers the error or an empty string, `run` takes
`{source, input, limits, caller}` and answers `{outcome, actions:[{name,args}], logs, error}`. A third-party engine may
speak that directly or copy `ScriptEngineWire.kt`.

### Open questions

- `MAX_ACTIONS`, `MAX_LOGS`, `MAX_LOG_LEN` and `MAX_DEPTH` are constants in `LuaScriptEngine`; no `scripting.*` setting changes them (not checked beyond the module.json list).
- Whether `mail.incoming` and `exec.tick` hooks can use the `lane` form is not stated in the code; only `ticket.moved` is mapped to it.
