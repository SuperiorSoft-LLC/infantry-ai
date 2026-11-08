# Daemon health and environment (DAEMON-HEALTH-1)

One report answers "is the daemon up, what restarted it, which MCP bridge dropped, and what machine am I on" so nobody
guesses or probes ports. It is the same report from three doors:

| Door | Use | Works when the daemon or MCP bridge is down? |
|---|---|---|
| CLI `infantry health [--json]` | a terminal, a hook, the PM | Yes: it falls back to the files in the state dir and says `source: files` |
| MCP tool `infantry_health` (plugin `Infantry_Health`) | an agent | Only while the bridge is connected (that is the point of the CLI) |
| Lua: `ih.call` on the `infantry_health` tool | a hook script | While the daemon is up |

Exit code of the CLI: 0 daemon up, 1 down.

## What it holds

- **daemon**: up/down, pid, port, build, uptime, last start and last restart cause. The cause is decided at the next start
  from the record the previous run left: a clean stop keeps its reason (`clean stop`, or the value of the system property
  `infantry.health.stopReason` such as `update`); a record still marked running whose pid is gone is `crash or kill`.
- **plugins**: every module's status (`loaded`, `refused: why`, `failed: why`; a plugin PluginGuard disabled keeps its reason)
  plus the rows plugins contribute through the existing `status` hook (the generic seam; core names no plugin).
- **mcp bridges**: per session id, connected or dropped, last seen, reconnect count. A bridge is recognised by the session id
  it sends with each tool call (`plugin_tool_call` `client.sessionId`); when that connection closes it is logged as dropped
  with the socket's close cause.
- **environment**: OS and arch, the shells and their syntax caveats, project and worktree path, git branch, JDK, the Gradle
  version the wrapper pins, the OWNER's off-limits ports (8787, 8788, 8797, 8798), the free test range (18900-18999),
  installed harnesses, the state dir. All read by the daemon.
- **recent restarts / disconnects**: the last 20 events of the log.

## Files (in `<stateDir>/health/`)

`status.json` (the current run, refreshed every `limits.health.heartbeatSeconds`) and `events.jsonl` (append-only,
trimmed to `limits.health.eventLogMax`). Both are plain JSON lines, so a script can read them directly.

## Limits

`limits.health.eventLogMax` (default 500 events) and `limits.health.heartbeatSeconds` (default 10). Read at start-up.

## Frame

`health` (Envelope.HEALTH, policy action `host.health.read`, a read): data `{"sessionId"?, "format"?: "text"}`; the answer is
a `health` frame with the `HealthReport` JSON (or its text). Code: `daemon/health/` in the `infantry` module.

## Not built yet

Hook count and hook-health failures (the hooks live in the scripting plugin and offer no read seam yet) and the start-brief
environment line (`EnvironmentProbe.briefLine` builds it; the first-turn composer does not call it yet).
