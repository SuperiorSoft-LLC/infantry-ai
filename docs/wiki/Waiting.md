# Waiting flag: "active but waiting" (WAITING-FLAG-1)

An agent whose turn is about to end while it waits on a process (a build, a long test, a background job) looks exactly like an
agent that quit. The waiting flag lets the agent say so itself. A flagged ticket stays In Progress; its card shows that the
worker is active but waiting, why, and for how long. A watcher script nudges the idle sessions that are NOT flagged.

## Set and clear it (any role, any harness)

| Door | Use |
|---|---|
| CLI `infantry waiting on --reason "gradle test" [--minutes 20]`, `infantry waiting off`, `infantry waiting show` | always works, no MCP needed (see BUG MCP-RECONNECT-1). Prints why and exits 1 when it cannot flag (no session, daemon down) |
| MCP tool `infantry_waiting` (`mode` on / off / get, `reason`, `untilMinutes`; plugin `Infantry_Agents`) | while the bridge is connected |
| Lua: `ih.infantry_waiting{...}` | a script running as a session |

All three send the `waiting` frame (`Envelope.WAITING`, action `agents.report`, allowed to the agent tier) for the session in
`INFANTRY_SESSION_ID`. The model is `SessionWaiting` / `WaitingRequest` / `WaitingRules` in
`superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/session/Waiting.kt`.

## When it ends

1. **Explicitly**: `infantry waiting off`.
2. **By itself, when the session's next turn starts** (`SessionManager` clears it where it starts the turn). Always on.
3. **By output**: with `limits.waiting.clearOnOutput` = 1 the session's first output after the flag (past
   `limits.waiting.outputGraceSeconds`, so the flagging command's own answer does not count) clears it too. Default 0 (turn only).
4. **By expiry**: the flag counts for the asked minutes (default `limits.waiting.defaultMinutes`) plus
   `limits.waiting.graceMinutes`; after that the session counts as silent again and the card says `wait over Nm ago?`.

Settings (all named, `LimitsGuardTest`): `limits.waiting.defaultMinutes` 30, `limits.waiting.graceMinutes` 5,
`limits.waiting.clearOnOutput` 0, `limits.waiting.outputGraceSeconds` 10, `limits.waiting.maxMinutes` 10080 (7 days, the longest
wait `--minutes` accepts), `limits.waiting.reasonMaxChars` 200 (how much of the reason the chip and the JSON keep).

## Where it shows

- **Board card** (`WaitingChip` in `plugins/official/Infantry_Board/src/WaitingCard.kt`): a chip `active, waiting 3m: <reason>`
  on an In Progress card, red `wait over 2m ago? <reason>` once past its expiry. It is a state of the card, not a new lane: a lane
  would move the ticket out of In Progress, where the done/blocked report flow and every watcher expect a worked ticket, and the
  flag is the worker's own and clears itself.
- **Board JSON**: `infantry_board` lines carry `WAITING 3m: <reason>`; `waiting: true` filters to flagged workers only.
- **Session JSON**: `infantry_list_sessions` rows carry `waiting {reason, since, untilAt, expiresAt, active}` and `ticketId`.

## The watcher

`worker-waiting-nudge` ([Script Arsenal](script-arsenal.md)), a bundled global script on a short timer. A worker session on an
In Progress ticket that is idle (turn ended), runs no background job and is not flagged gets ONE plain nudge after
`idleSeconds` (60) through `queue_add`: update the ticket, report done or blocked, or set waiting. A flag that ran out gets one
"still waiting?" nudge. It never nudges a flagged session and never twice for one idle period. Kill switch: arg `enabled`.
The silent-worker alerts to the PM (`worker-silent-ping`) only look at busy sessions, so a flagged idle session raises none.
